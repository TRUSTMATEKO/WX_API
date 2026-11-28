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

/** 고객사 송금을 접수하고 기관 응답에 따라 원장을 확정한다. */
public final class TransferSvc {

    private static final Logger logger = LoggerFactory.getLogger(TransferSvc.class);

    private final WalletLedgerRps ledger;

    /** 고객사 송금 접수와 확정에 사용할 원장을 연결한다. */
    @Autowired
    public TransferSvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 송금: 접수와 선차감, 기관 전송, 결과 확정을 차례로 수행한다. */
    public SharedMap<String, Object> transfer(WalletContext etx) {

        // 인증한 Wallet과 요청 이력을 연결하고, 원장의 한도 확인과 선차감이 끝난 거래만 전송한다.
        SharedMap<String, Object> inputMap = etx.getInput();
        inputMap.put("walletId", etx.getWalletId());
        inputMap.put("ioId", etx.getIo().getString("ioId"));
        inputMap.put("requestHash", etx.getIo().getString("requestHash"));

        SharedMap<String, Object> receiptMap;
        try {
            receiptMap = ledger.accept(inputMap, etx.getKeyHash(), etx.getClientIp(), etx.getTrace());
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }

        if (!receiptMap.getBoolean("result")) {
            SharedMap<String, Object> refusedMap = WalletResult.ledger("payout", receiptMap, etx.getTrace());
            throw new WalletException(refusedMap.getString("resultCd"), refusedMap.getString("advanceMsg"));
        }

        SharedMap<String, Object> acceptedMap = receiptMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (acceptedMap == null || acceptedMap.isBlank("trxId")) {
            throw new WalletException("W999", "송금 접수 응답을 확인해 주세요.");
        }

        String trxId  = acceptedMap.getString("trxId");
        long   amount = acceptedMap.getLong("amount");
        logger.info("송금 접수와 선차감 완료, 기관 전송 시작: trxId={}, trackId={}, amount={}, trace={}",
                trxId, acceptedMap.getString("trackId"), amount, etx.getTrace());

        SharedMap<String, Object> repliedMap = send(etx, acceptedMap, trxId, amount);
        // 무응답은 재조회까지 선차감을 유지한다. 전송 전 오류와 기관 거절은 실패로 확정한다.
        String code    = repliedMap.getString("acqResultCd");
        String outcome = switch (code) {
            case WalletPolicy.SUCCEEDED   -> WalletPolicy.OUTCOME_SUCCESS;
            case WalletPolicy.NO_RESPONSE -> WalletPolicy.OUTCOME_UNKNOWN;
            default                       -> WalletPolicy.OUTCOME_FAILED;
        };
        if (WalletPolicy.OUTCOME_UNKNOWN.equals(outcome)) {
            logger.warn("기관 응답 없음, 결과 확인 대상으로 둔다: trxId={}, acqTrxId={}, trace={}",
                    trxId, repliedMap.getString("acqTrxId"), etx.getTrace());
        } else {
            logger.info("기관 송금 응답 수신: trxId={}, outcome={}, acqResultCd={}, acqTrxId={}, trace={}",
                    trxId, outcome, code, repliedMap.getString("acqTrxId"), etx.getTrace());
        }

        SharedMap<String, Object> resultMap = finish(etx, trxId, outcome, repliedMap, acceptedMap);
        if (WalletPolicy.OUTCOME_UNKNOWN.equals(outcome)) {
            NotificationUtils.incident("TRX_TRANSFER", trxId,
                    "[WX] 송금 응답 미확인. 거래번호 " + trxId);
        }
        return resultMap;
    }

    /**
     * 기관 전송. 전문 직전에 기관 원거래번호를 먼저 기록한다. 재조회의 유일한 열쇠다.
     *
     * <p>원거래번호 기록 콜백은 소켓 쓰기 직전에만 호출된다. 따라서 콜백이 실행되지 않은 채 끝난
     * 예외는 전문이 나가지 않았다는 뜻이고, 이때는 실패로 확정해 차감한 잔액을 바로 원복해야 한다.</p>
     */
    private SharedMap<String, Object> send(WalletContext etx, SharedMap<String, Object> acceptedMap, String trxId,
                                            long amount) {

        AtomicBoolean referenceRecorded = new AtomicBoolean(false);
        try {
            UpstreamSvc               channel    = UpstreamSvc.open(upstream(acceptedMap));
            SharedMap<String, Object> requestMap = SharedMap.<String, Object>of()
                    .add("amount", Long.toString(amount))
                    .add("recvBankCd", acceptedMap.getString("bankCd"))
                    .add("recvAccount", acceptedMap.getString("accntNo"))
                    .add("sender", acceptedMap.getString("sender"))
                    .add("description", acceptedMap.getString("holder"));

            // 은행에 쓰기 직전에 원거래번호를 저장해야 응답이 끊겨도 같은 거래를 조회할 수 있다.
            return channel.transfer(requestMap, acqTrxId -> {
                ledger.recordAcq(trxId, acqTrxId, etx.getTrace());
                referenceRecorded.set(true);
            });
        } catch (Exception failure) {
            if (referenceRecorded.get()) {
                // 원거래번호 기록 후에는 기관 수신 여부를 단정할 수 없어 재조회로 넘긴다.
                logger.error("기관 전송 후 예외, 결과를 미확인으로 둔다: trxId={}, trace={}",
                        trxId, etx.getTrace(), failure);
                return SharedMap.<String, Object>of()
                        .add("acqResultCd", WalletPolicy.NO_RESPONSE)
                        .add("acqResultMsg", "통신장애, 결과 확인 중");
            }
            // 전문을 보내지 못했다. 실패로 확정해 선차감한 잔액을 원복한다.
            logger.info("기관 전송 전 오류로 송금을 실패 처리한다: trxId={}, type={}, trace={}",
                    trxId, failure.getClass().getSimpleName(), etx.getTrace());
            logger.debug("송금 기관 전송 전 예외 상세: trxId={}, trace={}", trxId, etx.getTrace(), failure);
            return SharedMap.<String, Object>of()
                    .add("acqResultCd", WalletPolicy.NOT_SENT)
                    .add("acqResultMsg", "기관 전송 전 오류로 처리하지 못했습니다.");
        }
    }

    /** 모계좌에 등록된 기관 연동 설정과 실행 환경을 HYPHEN 전송 프로필로 만든다. */
    static SharedMap<String, Object> upstream(SharedMap<String, Object> rowMap) {

        SharedMap<String, Object> profileMap = rowMap.getMap("upstream", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (profileMap == null) {
            throw new IllegalStateException("모계좌 기관 연동 설정을 확인하세요.");
        }

        return profileMap.copy()
                .add("upstream.enabled", true)
                .add("env", WalletConfig.upstreamEnv());
    }

    /** 기관 응답으로 원장을 확정하고 고객사 응답을 만든다. */
    private SharedMap<String, Object> finish(WalletContext etx, String trxId, String outcome,
                                              SharedMap<String, Object> repliedMap,
                                              SharedMap<String, Object> acceptedMap) {

        SharedMap<String, Object> commandMap = SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("outcome", outcome)
                .add("responseCode", repliedMap.getString("acqResultCd"))
                .add("responseMsg", repliedMap.getString("acqResultMsg"))
                .add("acqTrxId", repliedMap.getString("acqTrxId"))
                .add("acqTrxDate", repliedMap.getString("acqTrxDate"));

        SharedMap<String, Object> confirmedMap = null;
        try {
            SharedMap<String, Object> settledMap = ledger.confirm(commandMap, etx.getTrace());
            if (settledMap.getBoolean("result")) {
                confirmedMap = settledMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
            } else {
                logger.error("송금 확정이 거절되었다: trxId={}, outcome={}, code={}, trace={}",
                        trxId, outcome, settledMap.getString("code"), etx.getTrace());
            }
        } catch (Exception failure) {
            // 원장 확정을 확인할 수 없으므로 고객사에는 미확인으로 응답한다.
            logger.error("송금 확정 호출 실패, 결과 확인 중으로 응답한다: trxId={}, outcome={}, trace={}",
                    trxId, outcome, etx.getTrace(), failure);
        }

        String status = confirmedMap == null ? WalletPolicy.OUTCOME_UNKNOWN
                : confirmedMap.getString("status", outcome);
        String code = switch (status) {
            case WalletPolicy.OUTCOME_SUCCESS -> WalletPolicy.SUCCEEDED;
            case WalletPolicy.OUTCOME_UNKNOWN -> "W00X";
            default -> repliedMap.getString("acqResultCd", "W999");
        };
        String message = WalletPolicy.OUTCOME_UNKNOWN.equals(status)
                ? "통신장애, 결과 확인 중" : repliedMap.getString("acqResultMsg", "처리 결과를 확인해 주세요.");
        SharedMap<String, Object> replyMap = WalletResult.of(code, message, etx.getTrace());

        replyMap.put("trxId", trxId);
        replyMap.put("trackId", acceptedMap.getString("trackId"));
        replyMap.put("status", status);
        replyMap.put("bankCd", acceptedMap.getString("bankCd"));
        // 고객사 응답에는 마스킹한 계좌번호만 내보낸다. 평문은 기관 전송에만 쓴다.
        replyMap.put("accntNo", acceptedMap.getString("accntNoMasked"));
        replyMap.put("sender", acceptedMap.getString("sender"));
        replyMap.put("holder", acceptedMap.getString("holder"));
        replyMap.put("amount", acceptedMap.getLong("amount"));

        WalletResult.addTransferFee(replyMap, acceptedMap.getLong("feeAmt"));
        if (confirmedMap != null && confirmedMap.containsKey("balance")) {
            replyMap.put("balance", confirmedMap.getLong("balance"));
            replyMap.put("availableBalance", confirmedMap.getLong("availableBalance"));
        }

        logger.info("송금 응답: trxId={}, status={}, resultCd={}, trace={}",
                trxId, status, code, etx.getTrace());
        return replyMap;
    }
}
