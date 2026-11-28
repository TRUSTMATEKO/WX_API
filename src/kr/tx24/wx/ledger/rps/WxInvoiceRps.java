package kr.tx24.wx.ledger.rps;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.tx24.wx.ledger.enm.LedgerCode;
import kr.tx24.wx.ledger.ex.LedgerEx;

import static kr.tx24.wx.ledger.rps.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.rps.WxDatabaseRps.update;

/**
 * 월 청구서를 고객사에 청구한 것으로 종결한다.
 *
 * <p>청구서는 WX_TASK 가 월말 마감에서 만든다(전월 일별 정산의 합, {@code status='대기'}). 여기서
 * 하는 일은 운영자가 고객사에 청구했다는 사실을 기록하는 것 하나다. <b>금액은 바꾸지 않고 분개도
 * 만들지 않는다.</b> 돈은 일별 마감에서 이미 Wallet 에서 빠져나갔고, 월 청구서는 그 합을 고객사에
 * 알리는 문서다.</p>
 *
 * <p>왜 원장 프로세스가 가지는가. {@code STL_*} 은 일별 마감이 쓰는 표다. 마감은 월말에 이 표에
 * 청구서를 만들고, 그 트랜잭션과 상태 변경이 같은 행을 건드린다. 본사 화면이 직접 쓰면 마감과
 * 서로 다른 순서로 잠근다.</p>
 *
 * <p>상태는 한 방향으로만 간다. {@code 대기 → 청구} 이고 되돌리기는 없다. 청구는 고객사에 보낸
 * 사실이어서 잘못 보냈어도 그 사실이 남아야 하고, 조정은 다음 달 청구에서 한다. 같은 건을 두 번
 * 눌러도 {@code changed=false} 로 돌려주고 아무것도 바꾸지 않는다.</p>
 */
public final class WxInvoiceRps {

    private static final Logger logger = LoggerFactory.getLogger(WxInvoiceRps.class);

    private WxInvoiceRps() {

    }

    /**
     * 대기 청구서를 청구로 바꾸고 청구일을 기록한다.
     *
     * @param inMap {@code invoiceId}, {@code billDay}(YYYYMMDD), {@code userId}, {@code reason}
     * @return 청구번호와 바뀐 상태. 이미 청구된 건은 {@code changed=false}
     */
    public static SharedMap<String, Object> bill(SharedMap<String, Object> inMap) throws SQLException {

        String id       = WxPostingRps.field(inMap, "invoiceId", 64);
        String actor    = WxPostingRps.field(inMap, "userId", 24);
        String reason   = WxPostingRps.field(inMap, "reason", 128);
        String accessIp = inMap.getString("clientIp");
        int    billDay  = inMap.getInt("billDay");
        if (billDay < 19000101 || billDay > 99991231) {
            throw new LedgerEx(LedgerCode.INVALID, "청구일을 확인해 주세요.");
        }

        try (Connection db = WxDatabaseRps.open()) {
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                SharedMap<String, Object> resultMap = post(db, id, billDay, reason, actor, accessIp);
                db.commit();

                return resultMap;
            } catch (RuntimeException | SQLException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    /** 청구 처리 본문. 청구서 한 행을 잠그고 상태와 청구일만 바꾼다. */
    private static SharedMap<String, Object> post(Connection db, String id, int billDay, String reason,
            String actor, String accessIp) throws SQLException {

        List<SharedMap<String, Object>> foundList = rows(db, new Retrieve("WX.STL_MONTH")
                .columns("id,mchtId,baseMonth,status,billDay,totalAmt")
                .where("id=?")
                .forUpdate().build(), id);
        if (foundList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "월 청구서를 찾을 수 없습니다.");
        SharedMap<String, Object> invoiceMap = foundList.get(0);

        if (invoiceMap.isEquals("status", "청구")) {
            logger.info("이미 청구된 청구서다. 바꾸지 않는다: invoiceId={}, billDay={}",
                    id, invoiceMap.getInt("billDay"));
            return result(invoiceMap, invoiceMap.getInt("billDay"), false);
        }

        if (!invoiceMap.isEquals("status", "대기")) {
            throw new LedgerEx(LedgerCode.CONFLICT, "대기 상태의 청구서만 청구할 수 있습니다.");
        }

        LocalDateTime now = WxPostingRps.postingTime(db);
        if (update(db, """
                UPDATE WX.STL_MONTH SET status='청구',billDay=?,billedAt=?,billedBy=?
                 WHERE id=? AND status='대기'
                """, billDay, now, actor, id) != 1) {
            // 다른 운영자가 같은 순간에 처리했다. 돌려보내고 화면이 다시 조회하게 한다.
            throw new LedgerEx(LedgerCode.CONFLICT, "청구 상태가 바뀌었습니다. 목록을 새로 조회해 주세요.");
        }

        // 자금 처리와 같은 형식으로 변경 전후와 접속 IP 를 남긴다.
        update(db, """
                INSERT INTO WX.AUDIT(id,mchtId,unit,action,refType,refId,reason,beforeJson,afterJson,accessIp,
                                     regId,regDay)
                VALUES(?,?,'HQ','INVOICE_BILL','STL_MONTH',?,?,?,?,NULLIF(?,''),?,?)
                """, IDUtils.getRandom(32), invoiceMap.getString("mchtId"), id, reason,
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("status", "대기")),
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("status", "청구")
                        .add("billDay", billDay)),
                accessIp, actor, WxPostingRps.businessDay(now));

        logger.info("월 청구 처리: invoiceId={}, mchtId={}, baseMonth={}, billDay={}, totalAmt={}, actor={}",
                id, invoiceMap.getString("mchtId"), invoiceMap.getInt("baseMonth"), billDay,
                invoiceMap.getLong("totalAmt"), actor);
        return result(invoiceMap, billDay, true);
    }

    /** 화면이 읽는 응답. 금액은 바뀌지 않으므로 확인용으로만 담는다. */
    private static SharedMap<String, Object> result(SharedMap<String, Object> invoiceMap, int billDay,
            boolean changed) {

        return SharedMap.<String, Object>of()
                .add("id", invoiceMap.getString("id"))
                .add("mchtId", invoiceMap.getString("mchtId"))
                .add("baseMonth", invoiceMap.getInt("baseMonth"))
                .add("status", "청구")
                .add("billDay", billDay)
                .add("totalAmt", invoiceMap.getLong("totalAmt"))
                .add("changed", changed);
    }
}
