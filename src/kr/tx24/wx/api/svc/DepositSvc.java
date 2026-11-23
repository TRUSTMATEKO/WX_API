package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.utils.DepositAlert;
import kr.tx24.wx.api.vld.DepositValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 외부 시스템이 보낸 입금 통지를 처리한다.
 *
 * <p>SMS 중계와 기관 가상계좌 통지가 INet 으로 보낸다. 받는 쪽은 WX_API 하나이고 원장도 이
 * 프로세스가 가진다. 그래서 통지 기록과 Wallet 판정, 원장 반영이 한 트랜잭션에서 끝난다.</p>
 *
 * <p>Wallet 판정 기준은 둘이다. SMS 는 등록한 입금자명으로 찾고 후보가 정확히 하나일 때만
 * 반영한다. 가상계좌는 수취 번호가 곧 Wallet 이다. 찾지 못했거나 입금자명 후보가 둘 이상이면
 * 미매칭으로 두고 본사 운영자가 화면에서 Wallet 을 지정한다.</p>
 *
 * <p>접속 주소와 전용 키 확인은 수신 경로가 한다. 통지를 보내는 주체는 본사 화면도 작업기도
 * 아니므로 사설 대역 판정({@code WalletZone.isInternalRequest})을 적용하지 않는다. 허용 주소는
 * {@code notify.allowedIps} 설정 하나로 정한다.</p>
 */
public final class DepositSvc {

    private static final Logger logger = LoggerFactory.getLogger(DepositSvc.class);

    private final DepositValidator validator;
    private final WalletLedgerRps ledger;

    @Autowired
    public DepositSvc(DepositValidator validator, WalletLedgerRps ledger) {

        this.validator = validator;
        this.ledger = ledger;
    }

    /** 입금 통지 한 건을 기록하고 Wallet 이 하나로 정해지면 바로 반영한다. */
    public SharedMap<String, Object> receive(SharedMap<String, Object> in, String clientIp, String trace) {

        SharedMap<String, Object> input = validator.receive(in).add("clientIp", clientIp);
        // 계좌번호 전문은 로그에 남기지 않는다. 어떤 경로인지와 금액·시각만 남긴다.
        logger.info("입금 통지 접수: 경로={}, occurredAt={}, amount={}, clientIp={}, trace={}",
                input.isBlank("vaAccntNo") ? "sms" : "va", input.getString("occurredAt"),
                input.getLong("amount"), clientIp, trace);

        SharedMap<String, Object> reply = ledger.receiveDeposit(input, trace);
        if (!reply.getBoolean("result")) {
            SharedMap<String, Object> refused = WalletResult.ledger("deposit/receive", reply, trace);
            logger.warn("입금 통지 거절: resultCd={}, occurredAt={}, trace={}",
                    refused.getString("resultCd"), input.getString("occurredAt"), trace);
            throw new WalletException(refused.getString("resultCd"), refused.getString("advanceMsg"));
        }
        SharedMap<String, Object> data = reply.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (data == null) throw new WalletException("W999", "원장 응답을 확인해 주세요.");

        logger.info("입금 통지 처리: depositId={}, status={}, matched={}, duplicate={}, trace={}",
                data.getString("id"), data.getString("status"), data.getBoolean("matched"),
                data.getBoolean("duplicate"), trace);

        // 매칭이 끝나면 모계좌 잔액을 기관 값으로 맞춘다. 더하기만으로는 시간차를 메울 수 없다.
        if (!data.getBoolean("duplicate")) {
            syncBankBalance(data, input, trace);
        }

        // 미매칭은 돈은 들어왔는데 Wallet 을 정하지 못한 상황이다. 운영자가 지정하기 전에는
        // 고객사 잔액에 반영되지 않으니 바로 알린다. 이미 기록한 통지는 다시 알리지 않는다.
        if (!data.getBoolean("matched") && !data.getBoolean("duplicate")) {
            DepositAlert.mismatch(data.getString("id"), input.isBlank("vaAccntNo") ? "sms" : "va",
                    input.getString("depositName"), input.getString("occurredAt"),
                    input.getLong("amount"), trace);
        }

        SharedMap<String, Object> result = WalletResult.of("0000",
                data.getBoolean("matched") ? "입금을 Wallet 에 반영했습니다."
                        : "입금을 미매칭으로 기록했습니다. 본사에서 Wallet 을 지정합니다.", trace);
        for (String name : new String[] {"id", "status", "mchtId", "walletId", "walletTrxId", "amount",
                "balanceAfter", "matched", "duplicate"}) {
            if (data.containsKey(name)) result.put(name, data.get(name));
        }
        return result;
    }
    /**
     * 입금 반영 뒤 모계좌 잔액을 기관 값으로 맞춘다.
     *
     * <p>들어온 금액만큼 더하는 것만으로는 시간차를 메울 수 없다. 통지가 늦거나 순서가 바뀌거나 WX 가
     * 모르는 입출금이 있으면 장부잔액이 실제와 벌어진다. 그래서 매칭이 끝나면 기관에 잔액을 물어
     * 그 값으로 맞춘다. 차이는 모계좌 원장의 조정 분개로 남는다.</p>
     *
     * <p>실패해도 입금 기록을 되돌리지 않는다. 돈은 이미 들어왔고 기록은 끝났다. 다음 입금이나
     * 운영자 조회에서 다시 맞출 기회가 있다.</p>
     *
     * <p><b>시험 환경에서는 건너뛴다.</b> {@code env=test} 의 더미 잔액은 Redis 의 시험
     * 값이어서 원장과 아무 관계가 없다. 그 값으로 맞추면 시험 원장이 깨진다.</p>
     */
    private void syncBankBalance(SharedMap<String, Object> data, SharedMap<String, Object> input, String trace) {

        String hqId      = data.getString("hqId", input.getString("hqId"));
        String depositId = data.getString("id");
        if (hqId.isBlank() || depositId.isBlank()) return;
        if (!WalletConfig.upstreamReady()) {
            logger.info("기관 연동 설정이 없어 모계좌 잔액을 맞추지 않는다: hqId={}, trace={}", hqId, trace);
            return;
        }
        if ("test".equals(WalletConfig.upstreamEnv())) {
            logger.info("시험 환경이어서 모계좌 잔액을 맞추지 않는다. 더미 잔액은 원장과 무관하다: "
                    + "hqId={}, trace={}", hqId, trace);
            return;
        }
        SharedMap<String, Object> profile = data.getMap("upstream", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (profile == null) {
            logger.warn("모계좌 기관 연동 설정이 없어 잔액을 맞추지 못한다: hqId={}, trace={}", hqId, trace);
            return;
        }
        try {
            SharedMap<String, Object> replied = new HyphenSvc(TransferSvc.upstream(data)).balance();
            if (!WalletPolicy.SUCCEEDED.equals(replied.getString("acqResultCd"))
                    || !replied.containsKey("balance")) {
                logger.warn("기관 잔액 조회가 성공하지 않아 맞추지 않는다: hqId={}, acqResultCd={}, trace={}",
                        hqId, replied.getString("acqResultCd"), trace);
                return;
            }
            SharedMap<String, Object> synced = ledger.syncBankBalance(hqId, depositId,
                    replied.getLong("balance"), trace);
            SharedMap<String, Object> result = synced.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
            if (!synced.getBoolean("result") || result == null) {
                logger.warn("모계좌 잔액 동기화가 거절되었다: hqId={}, code={}, trace={}",
                        hqId, synced.getString("code"), trace);
                return;
            }
            logger.info("모계좌 잔액 동기화: hqId={}, 장부={}, 기관={}, 차이={}, 조정={}, trace={}",
                    hqId, result.getLong("before"), result.getLong("reported"), result.getLong("delta"),
                    result.getBoolean("changed"), trace);
        } catch (Exception failure) {
            // 입금은 이미 기록했다. 잔액 맞추기 실패로 그것을 되돌리지 않는다.
            logger.warn("모계좌 잔액을 맞추지 못했다: hqId={}, type={}, trace={}",
                    hqId, failure.getClass().getSimpleName(), trace);
        }
    }

}
