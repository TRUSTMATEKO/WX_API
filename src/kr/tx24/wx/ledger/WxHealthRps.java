package kr.tx24.wx.ledger;

import java.sql.Statement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 원장을 쓰는 프로세스가 거래·마감 실행 없이 WX 연결과 스키마만 확인한다.
 *
 * <p>종전에는 WX_ADM의 내부 INet 경로({@code /api/internal/wallet/api-health},
 * {@code /task-health})가 이 확인을 대신했다. 원장 쓰기가 WX_API와 WX_TASK로 옮겨졌으므로
 * 각 프로세스가 자기 DB 연결로 직접 확인한다.</p>
 */
public final class WxHealthRps {

    /**
     * 업무 트리거는 일곱 개가 모두 있어야 한다. 하나라도 없으면 잔액이 맞지 않는다.
     *
     * <p>{@code TR_TRX_WALLET_BALANCE_AI}·{@code _AU}·{@code TR_TRX_WALLET_NO_DELETE}는
     * {@code ai/db/10-wallet-balance-trigger.sql}이 만든다. 이 세 개가 없으면 원장을 써도
     * {@code WALLET_BALANCE}가 갱신되지 않아 잔액이 0에 머문다. 종전 진단은 네 개만 세어
     * 그 상태를 통과시켰다.</p>
     */
    private static final int TRIGGERS = 7;

    private WxHealthRps() {

    }

    /** WX_API: 키·Wallet·잔액·운영정책·거래 테이블과 업무 트리거를 조회 전용으로 확인한다. */
    public static void api() throws SQLException {

        verify("""
                SELECT k.idx,k.ips,w.ownerType,b.available,c.firmStopStart,c.firmStopEnd,t.udtDate,h.accntNo
                FROM WX.MCHT_WALLET_API_KEY k,WX.WALLET w,WX.WALLET_BALANCE b,WX.CONFIG c,WX.TRX_TRANSFER t,WX.TRX_HOLDER h LIMIT 0
                """);
    }

    /** WX_TASK: 마감이 쓰는 정산·집계 테이블과 업무 트리거를 마감 실행 없이 확인한다. */
    public static void task() throws SQLException {

        verify("""
                SELECT d.feeRateIdx,t.hqWalletId,b.available,h.baseDay,w.holderCnt,w.totalFee,m.issueDay
                FROM WX.STL_DAY d,WX.STL_DAY_DTL t,WX.WALLET_BALANCE b,WX.TOT_HQ_DAY h,WX.TOT_WALLET_DAY w,WX.STL_MONTH m LIMIT 0
                """);
    }

    /** 확인 공통: 업무 컬럼이 모두 있는지와 잔액 트리거가 걸려 있는지를 본다. */
    private static void verify(String columns) throws SQLException {

        try (Connection db = WxDatabaseRps.open(); Statement probe = db.createStatement()) {
            probe.setQueryTimeout(3);
            try (ResultSet result = probe.executeQuery(columns)) {
                result.getMetaData();
            }
            try (ResultSet result = probe.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND
                    ((event_object_table='HQ_ACCNT' AND trigger_name='TR_HQ_ACCNT_CREATE_WALLET')
                    OR (event_object_table='WALLET' AND trigger_name='TR_WALLET_CREATE_BALANCE')
                    OR (event_object_table='TOT_WALLET_DAY' AND trigger_name IN ('TR_TOT_WALLET_DAY_FEE_BI','TR_TOT_WALLET_DAY_FEE_BU'))
                    OR (event_object_table='TRX_WALLET' AND trigger_name IN ('TR_TRX_WALLET_BALANCE_AI','TR_TRX_WALLET_BALANCE_AU','TR_TRX_WALLET_NO_DELETE')))
                    """)) {
                if (!result.next() || result.getInt(1) != TRIGGERS) {
                    throw new SQLException("WX 업무 트리거를 확인해 주세요.");
                }
            }
            // 출금가능잔액 계산은 프로시저 한곳에만 둔다. 트리거가 이것을 호출한다.
            try (ResultSet result = probe.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.routines
                     WHERE routine_schema=DATABASE() AND routine_type='PROCEDURE'
                       AND routine_name='WX_WALLET_AVAILABLE'
                    """)) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("WX_WALLET_AVAILABLE 프로시저를 확인해 주세요.");
                }
            }
        }
    }
}
