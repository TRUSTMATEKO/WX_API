package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.ctx.WalletContext;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.utils.NotificationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 송금을 처리한다.
 *
 * <p>계약은 예약이 아니다. 원장이 잔액을 먼저 차감하고, 즉시 기관으로 전문을 보낸 뒤 그 응답으로
 * 결과를 확정한다. 기관이 응답 없이 끝난 경우에만 미확인으로 두어 차감을 유지하고,
 * WX_TASK의 재조회가 원거래 결과를 확인해 성공 또는 실패로 확정한다. 실패 확정 때 잔액이 원복된다.</p>
 */
public final class TransferSvc {

    private static final Logger logger = LoggerFactory.getLogger(TransferSvc.class);

    private final WalletLedgerRps ledger;

    @Autowired
    public TransferSvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 송금: 접수와 선차감, 기관 전송, 결과 확정을 차례로 수행한다. */
    public SharedMap<String, Object> transfer(WalletContext etx) {

        if (!WalletConfig.upstreamReady()) throw new WalletException("W003", "기관 환경을 확인해 주세요.");
        SharedMap<String, Object> accepted = accept(etx);
        String trxId  = accepted.getString("trxId");
        long   amount = accepted.getLong("amount");
        logger.info("송금 접수와 선차감 완료, 기관 전송 시작: trxId={}, trackId={}, amount={}, trace={}",
                trxId, accepted.getString("trackId"), amount, etx.getTrace());

        SharedMap<String, Object> replied = send(etx, accepted, trxId, amount);
        String code    = replied.getString("acqResultCd");
        String outcome = WalletPolicy.SUCCEEDED.equals(code) ? WalletPolicy.OUTCOME_SUCCESS
                : (WalletPolicy.NO_RESPONSE.equals(code) ? WalletPolicy.OUTCOME_UNKNOWN
                : WalletPolicy.OUTCOME_FAILED);
        if (WalletPolicy.OUTCOME_UNKNOWN.equals(outcome)) {
            logger.warn("기관 응답 없음, 결과 확인 대상으로 둔다: trxId={}, acqTrxId={}, trace={}",
                    trxId, replied.getString("acqTrxId"), etx.getTrace());
        } else {
            logger.info("기관 송금 응답 수신: trxId={}, outcome={}, acqResultCd={}, acqTrxId={}, trace={}",
                    trxId, outcome, code, replied.getString("acqTrxId"), etx.getTrace());
        }
        SharedMap<String, Object> result = finish(etx, trxId, outcome, replied, accepted);
        if (WalletPolicy.OUTCOME_UNKNOWN.equals(outcome)) {
            NotificationUtils.incident("TRX_TRANSFER", trxId,
                    "[WX] 송금 기관 응답 미확인. 거래번호 " + trxId + ". 송금내역에서 확인해 주세요.");
        }
        return result;
    }

    /**
     * 접수와 선차감. 원장이 한도·잔액·기관 설정을 모두 확인한 뒤 차감한다.
     *
     * <p>여기까지 성공하면 Wallet 장부잔액은 이미 줄어 있다. 이후 어떤 경로로 끝나든
     * 성공·실패·미확인 중 하나로 확정해야 한다.</p>
     */
    private SharedMap<String, Object> accept(WalletContext etx) {

        SharedMap<String, Object> input = etx.getInput();
        input.put("authorizedWalletId", etx.getWalletId());
        input.put("ioId", etx.getIo().getString("ioId"));
        input.put("requestHash", etx.getIo().getString("requestHash"));

        SharedMap<String, Object> reply;
        try {
            reply = ledger.accept(input, etx.getKeyHash(), etx.getClientIp(), etx.getTrace());
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }
        if (!reply.getBoolean("result")) {
            SharedMap<String, Object> refused = WalletResult.ledger("payout", reply, etx.getTrace());
            throw new WalletException(refused.getString("resultCd"), refused.getString("advanceMsg"));
        }
        SharedMap<String, Object> accepted = reply.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (accepted == null || accepted.isBlank("trxId")) {
            throw new WalletException("W999", "송금 접수 응답을 확인해 주세요.");
        }
        etx.setLedger(accepted);
        return accepted;
    }

    /**
     * 기관 전송. 전문 직전에 기관 원거래번호를 먼저 기록한다. 재조회의 유일한 열쇠다.
     *
     * <p>원거래번호 기록 콜백은 소켓 쓰기 직전에만 호출된다. 따라서 콜백이 실행되지 않은 채 끝난
     * 예외는 전문이 나가지 않았다는 뜻이고, 이때는 실패로 확정해 차감한 잔액을 바로 원복해야 한다.</p>
     */
    private SharedMap<String, Object> send(WalletContext etx, SharedMap<String, Object> accepted, String trxId,
                                            long amount) {

        AtomicBoolean sent = new AtomicBoolean(false);
        try {
            return new HyphenSvc(upstream(accepted)).transfer(SharedMap.<String, Object>of()
                            .add("amount", Long.toString(amount))
                            .add("recvBankCd", accepted.getString("bankCd"))
                            .add("recvAccount", accepted.getString("accntNo"))
                            .add("sender", accepted.getString("sender"))
                            .add("description", accepted.getString("holder")),
                    acqTrxId -> {
                        ledger.recordAcq(trxId, acqTrxId, etx.getTrace());
                        sent.set(true);
                    });
        } catch (Exception failure) {
            if (sent.get()) {
                // 전문이 나간 뒤의 예외다. 차감을 유지하고 재조회로 결과를 확인한다.
                logger.error("기관 전송 후 예외, 결과를 미확인으로 둔다: trxId={}, trace={}",
                        trxId, etx.getTrace(), failure);
                return SharedMap.<String, Object>of()
                        .add("acqResultCd", WalletPolicy.NO_RESPONSE)
                        .add("acqResultMsg", "통신장애, 결과 확인 중");
            }
            // 전문을 보내지 못했다. 실패로 확정해 선차감한 잔액을 원복한다.
            logger.error("기관 전송 전 오류로 송금을 실패 처리한다: trxId={}, trace={}",
                    trxId, etx.getTrace(), failure);
            return SharedMap.<String, Object>of()
                    .add("acqResultCd", WalletPolicy.NOT_SENT)
                    .add("acqResultMsg", "기관 전송 전 오류로 처리하지 못했습니다.");
        }
    }

    /** 모계좌에 등록된 기관 연동 설정과 실행 환경을 HYPHEN 전송 프로필로 만든다. */
    static SharedMap<String, Object> upstream(SharedMap<String, Object> row) {

        SharedMap<String, Object> profile = row.getMap("upstream", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (profile == null) throw new IllegalStateException("모계좌 기관 연동 설정을 확인하세요.");
        return profile.copy()
                .add("upstream.enabled", true)
                .add("env", WalletConfig.upstreamEnv());
    }

    /** 기관 응답으로 원장을 확정하고 고객사 응답을 만든다. */
    private SharedMap<String, Object> finish(WalletContext etx, String trxId, String outcome,
                                              SharedMap<String, Object> replied,
                                              SharedMap<String, Object> accepted) {

        SharedMap<String, Object> command = SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("outcome", outcome)
                .add("responseCode", replied.getString("acqResultCd"))
                .add("responseMsg", replied.getString("acqResultMsg"))
                .add("acqTrxId", replied.getString("acqTrxId"))
                .add("acqTrxDate", replied.getString("acqTrxDate"));

        SharedMap<String, Object> confirmed = null;
        try {
            SharedMap<String, Object> settled = ledger.confirm(command, etx.getTrace());
            if (settled.getBoolean("result")) {
                confirmed = settled.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
            } else {
                logger.error("송금 확정이 거절되었다: trxId={}, outcome={}, code={}, trace={}",
                        trxId, outcome, settled.getString("code"), etx.getTrace());
            }
        } catch (Exception failure) {
            // WX_ADM은 확정 중 예외를 만나면 스스로 미확정으로 주차한다. 결과는 재조회로 해소된다.
            logger.error("송금 확정 호출 실패, 결과 확인 중으로 응답한다: trxId={}, outcome={}, trace={}",
                    trxId, outcome, etx.getTrace(), failure);
        }

        String status = confirmed == null ? WalletPolicy.OUTCOME_UNKNOWN
                : confirmed.getString("status", outcome);
        String code = switch (status) {
            case WalletPolicy.OUTCOME_SUCCESS -> WalletPolicy.SUCCEEDED;
            case WalletPolicy.OUTCOME_UNKNOWN -> "W00X";
            default -> replied.getString("acqResultCd", "W999");
        };
        SharedMap<String, Object> reply = WalletResult.of(code, WalletPolicy.OUTCOME_UNKNOWN.equals(status) ? "통신장애, 결과 확인 중"
                : replied.getString("acqResultMsg", "처리 결과를 확인해 주세요."), etx.getTrace());
        reply.put("trxId", trxId);
        reply.put("trackId", accepted.getString("trackId"));
        reply.put("status", status);
        reply.put("bankCd", accepted.getString("bankCd"));
        // 고객사 응답에는 마스킹한 계좌번호만 내보낸다. 평문은 기관 전송에만 쓴다.
        reply.put("accntNo", accepted.getString("accntNoMasked"));
        reply.put("sender", accepted.getString("sender"));
        reply.put("holder", accepted.getString("holder"));
        reply.put("amount", accepted.getLong("amount"));
        // 고객사가 정산에 바로 쓰도록 이 건에 적용한 수수료를 함께 내보낸다. VAT 별도 원화 정수다.
        reply.put("feeAmt", accepted.getLong("feeAmt"));
        if (!replied.isBlank("acqTrxId")) reply.put("acqTrxId", replied.getString("acqTrxId"));
        if (confirmed != null && confirmed.containsKey("balance")) {
            reply.put("balance", confirmed.getLong("balance"));
            reply.put("availableBalance", confirmed.getLong("availableBalance"));
        }
        logger.info("송금 응답: trxId={}, status={}, resultCd={}, trace={}",
                trxId, status, code, etx.getTrace());
        return reply;
    }
}
