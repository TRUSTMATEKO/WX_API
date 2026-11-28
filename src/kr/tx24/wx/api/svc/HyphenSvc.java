package kr.tx24.wx.api.svc;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import kr.tx24.wx.api.hyphen.KRWHead;
import kr.tx24.wx.api.hyphen.HyphenPisp;
import kr.tx24.wx.api.hyphen.HyphenStub;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.MaskUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.utils.NotificationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 모계좌 환경에 따라 HYPHEN 운영 전문 또는 시험 응답을 처리한다. */
public final class HyphenSvc implements UpstreamSvc {

    private static final Logger logger = LoggerFactory.getLogger(HyphenSvc.class);

    private final SharedMap<String, Object> profileMap;

    /** 기관 연동용 보호 프로필을 준비한다. {@code env} 가 test 면 전문을 보내지 않는다. */
    public HyphenSvc(SharedMap<String, Object> profileMap) {

        if (!profileMap.getBoolean("upstream.enabled")
                || !Set.of("test", "prod").contains(profileMap.getString("env"))
                || !profileMap.getString("hqId").matches("[A-Za-z0-9_-]{1,24}")
                || !WalletPolicy.isValidAcqSid(profileMap.getString("acqSid"))
                || !profileMap.getString("acqBankCd").matches("[0-9]{2,3}")
                || !profileMap.getString("acqAccount").matches("[0-9]{1,20}") || profileMap.getString("acqKey2").isBlank()) {
            throw new IllegalArgumentException("HYPHEN 보호 프로필을 확인하세요.");
        }
        this.profileMap = profileMap.copy();
    }

    /** 직접 송금: 호출자는 영속 예약·기관 거래 식별자·재조회 계약을 확보한 뒤 한 번만 호출한다. */
    @Override
    public SharedMap<String, Object> transfer(SharedMap<String, Object> inputMap, Consumer<String> recordBeforeSend) {

        String amount  = inputMap.getString("amount");
        String bank    = inputMap.getString("recvBankCd");
        String account = inputMap.getString("recvAccount");
        if (!amount.matches("[1-9][0-9]{0,11}") || !bank.matches("[0-9]{3}") || !account.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("기관 지급 입력을 확인하세요.");
        }
        INet command = request()
                .data("amount", amount)
                .data("sign", "+")
                .data("recvBankCd", bank)
                .data("recvAccount", account)
                .data("sender", inputMap.getString("sender"))
                .data("description", inputMap.getString("description"));
        Objects.requireNonNull(recordBeforeSend);
        if (isTest()) {
            // 시험 환경은 기관에 전문을 보내지 않는다. 원거래번호 기록과 잔액 차감은 그대로 따른다.
            return warnLowBalance(
                    HyphenStub.transfer(profileMap.getString("hqId"), Long.parseLong(amount), recordBeforeSend));
        }
        HyphenPisp client = new HyphenPisp() {
            @Override
            public byte[] execute(byte[] bytes) {

                KRWHead head = new KRWHead(bytes);
                if (!head.getTrxDay().matches("[0-9]{8}") || !head.getSeqNo().matches("[0-9]{6}")) {
                    throw new IllegalStateException("기관 원거래번호 확인 실패");
                }
                // 이 번호로 장애 거래를 재조회하므로 기록에 실패하면 소켓에 전문을 쓰기 전에 중단한다.
                recordBeforeSend.accept(head.getTrxDay().substring(2) + head.getSeqNo());
                return super.execute(bytes);
            }
        };

        return warnLowBalance(new SharedMap<>(client.transfer(command)));
    }

    /** 본사 미결 송금 확인에 원거래 기관번호를 사용해 HYPHEN 결과 조회 전문을 구성한다. */
    @Override
    public SharedMap<String, Object> transferResult(String acqTrxId) {

        if (acqTrxId == null || !acqTrxId.matches("[0-9]{12}")) {
            throw new IllegalArgumentException("기관 원거래번호의 일자 6자리와 일련번호 6자리를 확인하세요.");
        }
        if (isTest()) {
            return HyphenStub.transferResult(profileMap.getString("hqId"), acqTrxId);
        }

        return new SharedMap<>(new HyphenPisp().inquery(request().data("acqTrxId", acqTrxId)));
    }

    /** 직접 잔액조회: 동일한 보호 프로필의 본사 계좌를 사용한다. */
    @Override
    public SharedMap<String, Object> balance() {

        if (isTest()) {
            return HyphenStub.balance(profileMap.getString("hqId"));
        }

        return new SharedMap<>(new HyphenPisp().balance(request()));
    }

    /**
     * 지급이 성공한 응답의 모계좌 잔액이 알림 기준 이하이면 경고를 남긴다.
     *
     * <p>기관이 돌려준 잔액은 전문이 정상 처리된 답에서만 믿을 수 있다. 실패와 무응답은 기관이
     * 처리했는지를 알 수 없어 잔액도 확정된 값이 아니므로 보지 않는다. 기준 금액은 모계좌에 등록한
     * {@code HQ_ACCNT.alarmBalance}이고 전송 프로필로 함께 받는다.</p>
     *
     * <p>경고와 SMS 발송을 처리하고 이번 지급은 그대로 끝낸다. 다음 지급을 받을지는
     * 원장이 모계좌 지급 가능액으로 판정한다. 로그에는 모계좌 계좌번호 원문을 남기지 않는다.</p>
     */
    private SharedMap<String, Object> warnLowBalance(SharedMap<String, Object> repliedMap) {

        if (!WalletPolicy.SUCCEEDED.equals(repliedMap.getString("acqResultCd")) || !repliedMap.containsKey("balance")) {
            return repliedMap;
        }

        long balance = repliedMap.getLong("balance");
        long alarm   = profileMap.getLong("alarmBalance", 0L);
        if (alarm > 0 && balance <= alarm) {
            logger.warn("모계좌 잔액이 알림 기준 이하입니다, 입금이 필요합니다: hqId={}, 은행코드={}, 계좌={}, 잔액={}원, 알림기준={}원",
                    profileMap.getString("hqId"), profileMap.getString("acqBankCd"),
                    MaskUtils.account(profileMap.getString("acqAccount")), balance, alarm);
            NotificationUtils.lowBalance(profileMap.getString("hqId"), balance);
        }

        return repliedMap;
    }

    /**
     * 시험 환경인지 판정한다.
     *
     * <p>{@code env}는 생성자에서 {@code test}와 {@code prod} 둘 중 하나로 확인했다. 따라서
     * 시험이 아니면 운영이고, 설정이 빠진 채 실수로 기관에 전문이 나가는 일은 없다.</p>
     */
    private boolean isTest() {

        return profileMap.isEquals("env", "test");
    }

    /** 기관 직접 호출에 공통 계좌·환경 설정을 지정한다. */
    private INet request() {

        return new INet()
                .head("development", profileMap.isEquals("env", "test"))
                // 기관 거래 일련번호를 모계좌별로 나누기 위해 모계좌 ID 를 함께 보낸다.
                .data("hqId", profileMap.getString("hqId"))
                .data("acqSid", profileMap.getString("acqSid"))
                .data("acqBankCd", profileMap.getString("acqBankCd"))
                .data("acqAccount", profileMap.getString("acqAccount"))
                .data("acqKey2", profileMap.getString("acqKey2"));
    }
}
