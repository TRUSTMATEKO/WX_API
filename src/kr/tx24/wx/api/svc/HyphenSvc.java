package kr.tx24.wx.api.svc;

import kr.tx24.wx.api.hyphen.KRWHead;
import kr.tx24.wx.api.hyphen.HyphenPisp;
import kr.tx24.wx.api.hyphen.HyphenStub;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.MaskUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 기관 전문을 보내는 단일 창구다.
 *
 * <p>모계좌 설정의 {@code env}가 {@code prod}면 HYPHEN과 실제로 통신하고, {@code test}면
 * {@link HyphenStub}의 더미 응답을 쓴다. 분기를 여기 한 고데만 두어 업무 계층은 시험과 운영에서
 * 같은 코드를 탄다. 그렇지 않으면 시험에서 맞춘 것이 운영을 보증하지 못한다.</p>
 */
public final class HyphenSvc {

    private static final Logger logger = LoggerFactory.getLogger(HyphenSvc.class);

    private final SharedMap<String, Object> profile;

    /** 기관 연동용 보호 프로필을 준비한다. {@code env} 가 test 면 전문을 보내지 않는다. */
    public HyphenSvc(SharedMap<String, Object> profile) {

        if (!profile.getBoolean("upstream.enabled")
                || !java.util.Set.of("test", "prod").contains(profile.getString("env"))
                || !profile.getString("hqId").matches("[A-Za-z0-9_-]{1,24}")
                || !profile.getString("acqSid").matches("[A-Za-z0-9_-]{1,32}")
                || !profile.getString("acqBankCd").matches("[0-9]{2,3}")
                || !profile.getString("acqAccount").matches("[0-9]{1,20}") || profile.getString("acqKey2").isBlank()) {
            throw new IllegalArgumentException("HYPHEN 보호 프로필을 확인하세요.");
        }
        this.profile = profile.copy();
    }

    /** 직접 송금: 호출자는 영속 예약·기관 거래 식별자·재조회 계약을 확보한 뒤 한 번만 호출한다. */
    public SharedMap<String, Object> transfer(SharedMap<String, Object> input, java.util.function.Consumer<String> recordBeforeSend) {

        String amount  = input.getString("amount");
        String bank    = input.getString("recvBankCd");
        String account = input.getString("recvAccount");
        if (!amount.matches("[1-9][0-9]{0,11}") || !bank.matches("[0-9]{3}") || !account.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("기관 지급 입력을 확인하세요.");
        }
        INet command = request()
                .data("amount", amount)
                .data("sign", "+")
                .data("recvBankCd", bank)
                .data("recvAccount", account)
                .data("sender", input.getString("sender"))
                .data("description", input.getString("description"));
        java.util.Objects.requireNonNull(recordBeforeSend);
        if (isTest()) {
            // 시험 환경은 기관에 전문을 보내지 않는다. 원거래번호 기록과 잔액 차감은 그대로 따른다.
            return warnLowBalance(
                    HyphenStub.transfer(profile.getString("hqId"), Long.parseLong(amount), recordBeforeSend));
        }
        HyphenPisp client = new HyphenPisp() {
            @Override
            public byte[] execute(byte[] bytes) {

                KRWHead head = new KRWHead(bytes);
                if (!head.getTrxDay().matches("[0-9]{8}") || !head.getSeqNo().matches("[0-9]{6}"))
                    throw new IllegalStateException("기관 원거래번호 확인 실패");
                recordBeforeSend.accept(head.getTrxDay().substring(2)+head.getSeqNo());
                return super.execute(bytes);
            }
        };
        return warnLowBalance(new SharedMap<>(client.transfer(command)));
    }

    /** WX 예금주 조회에서 HYPHEN 기본 계좌명 조회 전문을 구성한다. */
    public SharedMap<String,Object> holder(String bank,String account) {

        if (bank==null || !bank.matches("[0-9]{3}") || account==null || !account.matches("[0-9]{1,20}"))
            throw new IllegalArgumentException("예금주 조회 계좌를 확인하세요.");
        if (isTest()) return HyphenStub.holder(profile.getString("hqId"), bank, account);
        return new SharedMap<>(new HyphenPisp().holder(request().data("recvBankCd",bank).data("recvAccount",account)));
    }

    /** 본사 미결 송금 확인에 원거래 기관번호를 사용해 HYPHEN 결과 조회 전문을 구성한다. */
    public SharedMap<String, Object> transferResult(String acqTrxId) {

        if (acqTrxId == null || !acqTrxId.matches("[0-9]{12}")) {
            throw new IllegalArgumentException("기관 원거래번호의 일자 6자리와 일련번호 6자리를 확인하세요.");
        }
        if (isTest()) return HyphenStub.transferResult(profile.getString("hqId"), acqTrxId);
        return new SharedMap<>(new HyphenPisp().inquery(request().data("acqTrxId", acqTrxId)));
    }

    /** 직접 잔액조회: 동일한 보호 프로필의 본사 계좌를 사용한다. */
    public SharedMap<String, Object> balance() {

        if (isTest()) return HyphenStub.balance(profile.getString("hqId"));
        return new SharedMap<>(new HyphenPisp().balance(request()));
    }

    /**
     * 지급이 성공한 응답의 모계좌 잔액이 알림 기준 이하이면 경고를 남긴다.
     *
     * <p>기관이 돌려준 잔액은 전문이 정상 처리된 답에서만 믿을 수 있다. 실패와 무응답은 기관이
     * 처리했는지를 알 수 없어 잔액도 확정된 값이 아니므로 보지 않는다. 기준 금액은 모계좌에 등록한
     * {@code HQ_ACCNT.alarmBalance}이고 전송 프로필로 함께 받는다.</p>
     *
     * <p>경고만 남기고 이번 지급은 그대로 끝낸다. 전문은 이미 기관이 처리했고, 다음 지급을 받을지는
     * 원장이 모계좌 지급 가능액으로 판정한다. 로그에는 모계좌 계좌번호 원문을 남기지 않는다.</p>
     */
    private SharedMap<String, Object> warnLowBalance(SharedMap<String, Object> replied) {

        if (!WalletPolicy.SUCCEEDED.equals(replied.getString("acqResultCd")) || !replied.containsKey("balance")) {
            return replied;
        }
        long balance = replied.getLong("balance");
        long alarm   = profile.getLong("alarmBalance", 0L);
        if (balance <= alarm) {
            logger.warn("모계좌 잔액이 알림 기준 이하입니다, 입금이 필요합니다: hqId={}, 은행코드={}, 계좌={}, 잔액={}원, 알림기준={}원",
                    profile.getString("hqId"), profile.getString("acqBankCd"),
                    MaskUtils.account(profile.getString("acqAccount")), balance, alarm);
        }
        return replied;
    }

    /**
     * 시험 환경인지 판정한다.
     *
     * <p>{@code env}는 생성자에서 {@code test}와 {@code prod} 둘 중 하나로 확인했다. 따라서
     * 시험이 아니면 운영이고, 설정이 빠진 채 실수로 기관에 전문이 나가는 일은 없다.</p>
     */
    private boolean isTest() {

        return profile.isEquals("env", "test");
    }

    /** 기관 직접 호출에 공통 계좌·환경 설정을 지정한다. */
    private INet request() {

        return new INet()
                .head("development", profile.isEquals("env", "test"))
                // 기관 거래 일련번호를 모계좌별로 나누기 위해 모계좌 ID 를 함께 보낸다.
                .data("hqId", profile.getString("hqId"))
                .data("acqSid", profile.getString("acqSid"))
                .data("acqBankCd", profile.getString("acqBankCd"))
                .data("acqAccount", profile.getString("acqAccount"))
                .data("acqKey2", profile.getString("acqKey2"));
    }
}
