package kr.tx24.wx.api.svc;

import java.util.List;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 송금 통신장애의 원거래 결과를 확인해 확정한다.
 *
 * <p>기관 연동은 WX_API에, 원장은 WX_ADM에, 주기 실행은 WX_TASK에 있다. 그래서 WX_TASK가
 * 1분마다 이 경로를 부르고 실제 조회와 확정은 여기서 한다.</p>
 *
 * <p>계약은 OPL_TASK의 {@code PayoutNetErrTask}와 같다. 기관이 결과를 확정해 주지 않으면
 * 다시 큐에 넣어 다음 주기에 확인하고, 실패로 확인되면 원장이 잔액을 원복한다.</p>
 */
public final class RecoverySvc {

    private static final Logger logger = LoggerFactory.getLogger(RecoverySvc.class);

    private final WalletLedgerRps ledger;

    @Autowired
    public RecoverySvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 원장에서 선점한 미결 송금을 한 건씩 확인한다. */
    public SharedMap<String, Object> recover(int limit, String trace) throws Exception {

        if (!WalletConfig.upstreamReady()) {
            logger.warn("기관 전송 환경이 없어 송금 재조회를 수행하지 않는다: trace={}", trace);
            return counts(0, 0, 0);
        }

        List<SharedMap<String, Object>> rows = ledger.pending(Math.min(Math.max(limit, 1), WalletPolicy.RECOVERY_LIMIT), trace);
        SharedMap<String,Object> hq = counts(0,0,0);
        try {
            hq = new HqWithdrawSvc(ledger).recover(Math.min(Math.max(limit,1),WalletPolicy.RECOVERY_LIMIT));
        } catch (RuntimeException failure) {
            logger.error("본사 출금 재조회 목록 조회 실패: trace={}",trace,failure);
        }
        if (rows.isEmpty()) {
            return hq;
        }

        int settled = 0;
        int requeued = 0;
        for (SharedMap<String, Object> row : rows) {
            String trxId = row.getString("trxId");
            try {
                if (resolve(row, trace)) settled++;
                else requeued++;
            } catch (Exception failure) {
                logger.error("송금 재조회 처리 실패, 선점 만료 후 재시도: trxId={}, trace={}", trxId, trace, failure);
                requeued++;
            }
        }
        logger.info("송금 재조회 결과: checked={}, settled={}, requeued={}, trace={}",
                rows.size(), settled, requeued, trace);
        return counts(rows.size()+hq.getInt("checked"), settled+hq.getInt("settled"), requeued+hq.getInt("requeued"));
    }

    /** 작업기 주기에 입금 이후 보류된 모계좌 한 건을 새로운 기관 잔액으로 대조한다. */
    public SharedMap<String, Object> recoverBalance(String trace) throws Exception {

        if (!"prod".equals(WalletConfig.upstreamEnv())) return counts(0, 0, 0);
        SharedMap<String, Object> job = ledger.claimBalanceReview();
        if (job == null) return counts(0, 0, 0);
        boolean completed = DepositSvc.syncBankBalance(ledger, job, trace);
        return counts(1, completed ? 1 : 0, completed ? 0 : 1);
    }

    /** 한 건의 기관 원거래 결과를 조회해 확정한다. 확정하지 못하면 재조회 대기로 돌린다. */
    private boolean resolve(SharedMap<String, Object> row, String trace) throws Exception {

        String trxId    = row.getString("trxId");
        String acqTrxId = row.getString("bankTrxId");
        SharedMap<String, Object> inquiry;
        try {
            inquiry = new HyphenSvc(TransferSvc.upstream(row)).transferResult(acqTrxId);
        } catch (Exception failure) {
            logger.warn("기관 원거래 조회 호출 실패: trxId={}, acqTrxId={}, trace={}",
                    trxId, acqTrxId, trace, failure);
            return false;
        }

        if (unresolved(inquiry)) {
            logger.info("기관 원거래 결과 미확정: trxId={}, resultCd={}, acqResultCd={}, trace={}",
                    trxId, inquiry.getString("resultCd"), inquiry.getString("acqResultCd"), trace);
            return false;
        }

        String outcome = WalletPolicy.SUCCEEDED.equals(inquiry.getString("resultCd"))
                ? WalletPolicy.OUTCOME_SUCCESS : WalletPolicy.OUTCOME_FAILED;
        SharedMap<String, Object> settled = ledger.confirm(SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("outcome", outcome)
                .add("responseCode", inquiry.getString("resultCd"))
                .add("responseMsg", inquiry.getString("resultMsg"))
                .add("acqTrxId", acqTrxId), trace);
        if (!settled.getBoolean("result")) {
            throw new IllegalStateException("송금 재조회 확정이 거절되었습니다. code=" + settled.getString("code"));
        }
        logger.info("송금 통신장애 확정: trxId={}, outcome={}, resultCd={}, acqTrxId={}, trace={}",
                trxId, outcome, inquiry.getString("resultCd"), acqTrxId, trace);
        return true;
    }

    /**
     * 기관 조회 결과가 성공·실패로 확정되지 않았는지 본다.
     *
     * <p>기관 조회가 성공했고 원거래 결과 코드가 있는 응답만 확정에 사용한다.
     * 미확정 응답은 다음 주기에 다시 확인한다.</p>
     */
    private static boolean unresolved(SharedMap<String, Object> inquiry) {

        String resultCd    = inquiry.getString("resultCd");
        String acqResultCd = inquiry.getString("acqResultCd");
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
