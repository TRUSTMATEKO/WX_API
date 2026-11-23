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

    /** 장애 큐에서 꺼낸 송금을 한 건씩 확인한다. 꺼낸 건은 어떤 경우에도 잃지 않는다. */
    public SharedMap<String, Object> recover(int limit, String trace) throws Exception {

        if (!WalletConfig.upstreamReady()) {
            logger.warn("기관 전송 환경이 없어 송금 재조회를 수행하지 않는다: trace={}", trace);
            return counts(0, 0, 0);
        }

        List<SharedMap<String, Object>> rows = ledger.pending(Math.min(Math.max(limit, 1), WalletPolicy.RECOVERY_LIMIT), trace);
        if (rows.isEmpty()) {
            logger.debug("송금 재조회 대상 없음: trace={}", trace);
            return counts(0, 0, 0);
        }

        int settled = 0;
        int requeued = 0;
        for (SharedMap<String, Object> row : rows) {
            String trxId = row.getString("trxId");
            try {
                if (resolve(row, trace)) settled++;
                else requeued++;
            } catch (Exception failure) {
                // 꺼낸 건을 잃지 않는다. 확정하지 못한 건은 반드시 큐로 돌려보낸다.
                logger.error("송금 재조회 처리 실패, 재조회 대기로 돌린다: trxId={}, trace={}", trxId, trace, failure);
                park(trxId, row.getInt("retry"), "재조회 처리 예외", trace);
                requeued++;
            }
        }
        logger.info("송금 재조회 결과: checked={}, settled={}, requeued={}, trace={}",
                rows.size(), settled, requeued, trace);
        return counts(rows.size(), settled, requeued);
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
            park(trxId, row.getInt("retry"), "기관 조회 호출 실패", trace);
            return false;
        }

        if (unresolved(inquiry)) {
            logger.info("기관 원거래 결과 미확정: trxId={}, resultCd={}, acqResultCd={}, trace={}",
                    trxId, inquiry.getString("resultCd"), inquiry.getString("acqResultCd"), trace);
            park(trxId, row.getInt("retry"), "기관 결과 미확정", trace);
            return false;
        }

        String outcome = WalletPolicy.SUCCEEDED.equals(inquiry.getString("resultCd"))
                ? WalletPolicy.OUTCOME_SUCCESS : WalletPolicy.OUTCOME_FAILED;
        SharedMap<String, Object> settled = ledger.confirm(SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("outcome", outcome)
                .add("responseCode", inquiry.getString("resultCd"))
                .add("responseMsg", inquiry.getString("resultMsg"))
                .add("acqTrxId", inquiry.getString("acqTrxId", acqTrxId))
                .add("acqTrxDate", inquiry.getString("acqTrxDate")), trace);
        if (!settled.getBoolean("result")) {
            throw new IllegalStateException("송금 재조회 확정이 거절되었습니다. code=" + settled.getString("code"));
        }
        logger.info("송금 통신장애 확정: trxId={}, outcome={}, resultCd={}, acqTrxId={}, trace={}",
                trxId, outcome, inquiry.getString("resultCd"), inquiry.getString("acqTrxId", acqTrxId), trace);
        return true;
    }

    /**
     * 기관 조회 결과가 성공·실패로 확정되지 않았는지 본다.
     *
     * <p>결과 코드가 비었거나 업무·기관 코드가 X로 시작하면 통신 구간에서 끝난 응답이다.
     * 이때는 원거래 결과를 단정하지 않는다. OPL_TASK의 판정과 같다.</p>
     */
    private static boolean unresolved(SharedMap<String, Object> inquiry) {

        String resultCd    = inquiry.getString("resultCd");
        String acqResultCd = inquiry.getString("acqResultCd");
        return resultCd.isBlank() || resultCd.startsWith("X") || acqResultCd.startsWith("X");
    }

    /** 확정하지 못한 송금을 재조회 큐로 돌려보낸다. */
    private void park(String trxId, int retry, String reason, String trace) {

        try {
            ledger.park(trxId, retry, reason, trace);
        } catch (Exception failure) {
            // 큐 복귀까지 실패하면 자동 해소 경로가 없다. 운영자가 미확정 목록에서 확인해야 한다.
            logger.error("송금 재조회 대기 복귀 실패, 수동 확인 필요: trxId={}, reason={}, trace={}",
                    trxId, reason, trace, failure);
        }
    }

    /** 재조회 처리 건수를 WX_TASK가 읽을 형식으로 만든다. */
    private static SharedMap<String, Object> counts(int checked, int settled, int requeued) {

        return SharedMap.<String, Object>of()
                .add("checked", checked)
                .add("settled", settled)
                .add("requeued", requeued);
    }
}
