package kr.tx24.wx.api.svc;

import java.util.List;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** WX_TASK 요청으로 미확인 송금과 본사 출금의 원거래 결과를 조회해 확정한다. */
public final class RecoverySvc {

    private static final Logger logger = LoggerFactory.getLogger(RecoverySvc.class);

    private final WalletLedgerRps ledger;

    /** 작업기 재조회에 사용할 송금 원장을 연결한다. */
    @Autowired
    public RecoverySvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 원장에서 선점한 미결 송금을 한 건씩 확인한다. */
    public SharedMap<String, Object> recover(int limit, String trace) throws Exception {

        int                             batchSize = Math.min(Math.max(limit, 1), WalletPolicy.RECOVERY_LIMIT);
        List<SharedMap<String, Object>> rowsList  = ledger.pending(batchSize, trace);

        // 본사 출금 재조회가 실패해도 이미 선점한 고객사 송금은 계속 처리한다.
        SharedMap<String, Object> hqMap;
        try {
            hqMap = new HqWithdrawSvc(ledger).recover(batchSize);
        } catch (RuntimeException failure) {
            logger.info("본사 출금 재조회 목록 조회 실패: type={}, trace={}", failure.getClass().getSimpleName(), trace);
            logger.debug("본사 출금 재조회 목록 예외 상세: trace={}", trace, failure);
            hqMap = counts(0, 0, 0);
        }

        if (rowsList.isEmpty()) {
            return hqMap;
        }

        int settled  = 0;
        int requeued = 0;
        for (SharedMap<String, Object> rowMap : rowsList) {
            String trxId = rowMap.getString("trxId");
            try {
                if (resolve(rowMap, trace)) {
                    settled++;
                } else {
                    requeued++;
                }
            } catch (Exception failure) {
                logger.info("송금 재조회 처리 실패, 선점 만료 후 재시도: trxId={}, trace={}", trxId, trace);
                logger.debug("송금 재조회 처리 예외 상세: trxId={}, trace={}", trxId, trace, failure);
                requeued++;
            }
        }

        logger.info("송금 재조회 결과: checked={}, settled={}, requeued={}, trace={}",
                rowsList.size(), settled, requeued, trace);
        return counts(rowsList.size() + hqMap.getInt("checked"), settled + hqMap.getInt("settled"), requeued + hqMap.getInt("requeued"));
    }

    /** 작업기 주기에 입금 이후 보류된 모계좌 한 건을 새로운 기관 잔액으로 대조한다. */
    public SharedMap<String, Object> recoverBalance(String trace) throws Exception {

        if (!"prod".equals(WalletConfig.upstreamEnv())) {
            return counts(0, 0, 0);
        }

        SharedMap<String, Object> jobMap = ledger.claimBalanceReview();
        if (jobMap == null) {
            return counts(0, 0, 0);
        }

        boolean completed = DepositSvc.syncBankBalance(ledger, jobMap, trace);
        return counts(1, completed ? 1 : 0, completed ? 0 : 1);
    }

    /** 미전송 접수를 원복하거나 기관 원거래 결과를 조회해 확정한다. */
    private boolean resolve(SharedMap<String, Object> rowMap, String trace) throws Exception {

        String trxId = rowMap.getString("trxId");
        if (rowMap.isEquals("status", "접수")) {
            return ledger.expireUnsent(trxId);
        }

        String acqTrxId = rowMap.getString("bankTrxId");
        SharedMap<String, Object> inquiryMap;
        try {
            inquiryMap = UpstreamSvc.open(TransferSvc.upstream(rowMap)).transferResult(acqTrxId);
        } catch (Exception failure) {
            logger.debug("기관 원거래 조회 호출 실패: trxId={}, acqTrxId={}, trace={}",
                    trxId, acqTrxId, trace, failure);
            return false;
        }

        if (isUnresolved(inquiryMap)) {
            logger.info("기관 원거래 결과 미확정: trxId={}, resultCd={}, acqResultCd={}, trace={}",
                    trxId, inquiryMap.getString("resultCd"), inquiryMap.getString("acqResultCd"), trace);
            return false;
        }

        String outcome = WalletPolicy.SUCCEEDED.equals(inquiryMap.getString("resultCd"))
                ? WalletPolicy.OUTCOME_SUCCESS : WalletPolicy.OUTCOME_FAILED;
        // 조회 전문 자체의 성공 여부를 확인한 뒤 원거래 결과 코드로 원장을 확정한다.
        SharedMap<String, Object> commandMap = SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("outcome", outcome)
                .add("responseCode", inquiryMap.getString("resultCd"))
                .add("responseMsg", inquiryMap.getString("resultMsg"))
                .add("acqTrxId", acqTrxId);
        SharedMap<String, Object> settledMap = ledger.confirm(commandMap, trace);
        if (!settledMap.getBoolean("result")) {
            throw new IllegalStateException("송금 재조회 확정이 거절되었습니다. code=" + settledMap.getString("code"));
        }

        logger.info("송금 통신장애 확정: trxId={}, outcome={}, resultCd={}, acqTrxId={}, trace={}",
                trxId, outcome, inquiryMap.getString("resultCd"), acqTrxId, trace);
        return true;
    }

    /**
     * 기관 조회 결과가 성공·실패로 확정되지 않았는지 본다.
     *
     * <p>기관 조회가 성공했고 원거래 결과 코드가 있는 응답만 확정에 사용한다.
     * 미확정 응답은 다음 주기에 다시 확인한다.</p>
     */
    private static boolean isUnresolved(SharedMap<String, Object> inquiryMap) {

        String resultCd    = inquiryMap.getString("resultCd");
        String acqResultCd = inquiryMap.getString("acqResultCd");
        return resultCd.isBlank() || resultCd.startsWith("X") || !WalletPolicy.SUCCEEDED.equals(acqResultCd);
    }

    /** 재조회 처리 건수를 WX_TASK가 읽을 형식으로 만든다. */
    private static SharedMap<String, Object> counts(int checked, int settled, int requeued) {

        return SharedMap.<String, Object>of()
                .add("checked", checked)
                .add("settled", settled)
                .add("requeued", requeued);
    }
}
