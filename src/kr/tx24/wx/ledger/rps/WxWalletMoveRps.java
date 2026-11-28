package kr.tx24.wx.ledger.rps;

import java.util.List;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.tx24.wx.ledger.enm.LedgerCode;
import kr.tx24.wx.ledger.ex.LedgerEx;
import kr.tx24.wx.ledger.utils.HqWalletUtils;

import static kr.tx24.wx.ledger.rps.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.rps.WxDatabaseRps.update;

/**
 * 고객사 Wallet과 본사 Wallet 사이의 금액 이전을 분개한다.
 *
 * <p>기준은 OPL_API의 {@code /core/wallet/hq/collect}와 {@code /hq/payout}이다.
 * 본사 차감(집금)은 고객사 Wallet 출금과 본사 Wallet 입금을, 본사 지급은 그 반대를 한 트랜잭션에 기록한다.</p>
 *
 * <p><b>실제 은행 자금 이동이 없다.</b> 모계좌({@code HQ_ACCNT}) 잔액은 바꾸지 않는다.
 * 두 Wallet 잔액이 같은 금액만큼 반대로 움직이므로 Wallet 잔액 합계가 유지되고,
 * 대조식 {@code 모계좌 잔액 = Σ Wallet 잔액 + 미배분 입금액}도 그대로 성립한다.</p>
 *
 * <p>잔액은 트리거가 반영한다. 이 클래스는 분개와 상태만 기록한다.
 * 잠금 순서는 {@code WxCloseRps.charge}와 같다. 고객사 Wallet을 먼저 잠그고 본사 Wallet을 뒤에 잠근다.</p>
 */
public final class WxWalletMoveRps {

    private static final Logger logger = LoggerFactory.getLogger(WxWalletMoveRps.class);

    /** 이전 한 건의 상한. OPL과 같은 13자리 정수 범위를 쓴다. */
    private static final long AMOUNT_MAX = 9_999_999_999_999L;

    private WxWalletMoveRps() {

    }

    /** 본사 차감: 고객사 Wallet에서 빼고 본사 Wallet에 넣는다. */
    public static SharedMap<String, Object> collect(SharedMap<String, Object> inMap) throws SQLException {

        return move(inMap, true);
    }

    /** 본사 지급: 본사 Wallet에서 빼고 고객사 Wallet에 넣는다. */
    public static SharedMap<String, Object> payout(SharedMap<String, Object> inMap) throws SQLException {

        return move(inMap, false);
    }

    /**
     * 이전 본문. 두 분개와 감사 이력을 한 트랜잭션에 기록한다.
     *
     * @param collecting 참이면 고객사에서 본사로, 거짓이면 본사에서 고객사로 옮긴다
     */
    private static SharedMap<String, Object> move(SharedMap<String, Object> inMap, boolean collecting)
            throws SQLException {

        String track    = WxPostingRps.field(inMap, "trackId", 40);
        String company  = WxPostingRps.field(inMap, "mchtId", 24);
        String walletId = WxPostingRps.field(inMap, "walletId", 32);
        String actor    = WxPostingRps.field(inMap, "userId", 24);
        String note     = WxPostingRps.field(inMap, "note", 128);
        String accessIp = inMap.getString("clientIp");
        long   amount   = inMap.getLong("amount");
        if (amount < 1 || amount > AMOUNT_MAX) {
            throw new LedgerEx(LedgerCode.INVALID, "이전 금액을 확인해 주세요.");
        }

        try (Connection db = WxDatabaseRps.open()) {
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                SharedMap<String, Object> resultMap = post(db, collecting, track, company, walletId, amount, note, actor, accessIp);
                db.commit();

                return resultMap;
            } catch (RuntimeException | SQLException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    /** 분개 본문. 호출자가 업무 트랜잭션을 연 상태여야 한다. */
    private static SharedMap<String, Object> post(Connection db, boolean collecting, String track, String company,
                                                   String walletId, long amount, String note, String actor,
                                                   String accessIp) throws SQLException {

        // 같은 요청번호는 한 번만 처리한다. 두 분개가 모두 이 번호를 참조한다.
        List<SharedMap<String, Object>> existsList = rows(db, "SELECT id FROM WX.TRX_WALLET WHERE refType='WALLET_MOVE' AND refId=? LIMIT 1", track);
        if (!existsList.isEmpty()) {
            logger.info("Wallet 이전 요청번호 중복: trackId={}, id={}", track, existsList.get(0).getString("id"));
            throw new LedgerEx(LedgerCode.CONFLICT, "이미 처리된 요청번호입니다.");
        }

        List<SharedMap<String, Object>> walletsList = rows(db, new Retrieve("WX.WALLET w")
                .columns("w.id,w.mchtId,w.hqId,b.balance,b.held,b.available")
                .joinInner("WX.WALLET_BALANCE b", "b.walletId=w.id")
                .where("w.id=? AND w.mchtId=? AND w.ownerType='MCHT' AND w.status='사용'")
                .forUpdate().build(), walletId, company);
        if (walletsList.size() != 1) throw new LedgerEx(LedgerCode.CONFLICT, "고객사 Wallet 사용 상태를 확인해 주세요.");
        SharedMap<String, Object> walletMap = walletsList.get(0);
        String                    hqId      = walletMap.getString("hqId");

        if (collecting && walletMap.getLong("available") < amount) {
            logger.info("본사 차감 잔액 부족: walletId={}, amount={}, available={}",
                    walletId, amount, walletMap.getLong("available"));
            throw new LedgerEx(LedgerCode.CONFLICT, "고객사 Wallet의 출금가능잔액이 부족합니다.");
        }

        LocalDateTime postedAt = WxPostingRps.postingTime(db);
        long delta = collecting ? Math.negateExact(amount) : amount;

        // 고객사 Wallet 분개를 먼저 쓴다. 잠금 순서를 WxCloseRps.charge 와 같게 유지한다.
        String merchantEntry = entry(db, walletMap, delta, track, note, actor, postedAt);

        // 본사 Wallet 분개. 출금가능잔액 확인과 분개는 HqWalletUtils 가 수행한다.
        String hqEntry = HqWalletUtils.post(db, hqId, Math.negateExact(delta), "WALLET_MOVE", track, 2, actor, postedAt);

        int today = WxPostingRps.businessDay(postedAt);
        audit(db, collecting ? "WALLET_COLLECT" : "WALLET_PAYOUT", company, track, note, accessIp, actor, today,
                SharedMap.<String, Object>of()
                        .add("walletId", walletId)
                        .add("balance", walletMap.getLong("balance")),
                SharedMap.<String, Object>of()
                        .add("walletId", walletId)
                        .add("amount", delta)
                        .add("hqId", hqId)
                        .add("walletTrxId", merchantEntry)
                        .add("hqWalletTrxId", hqEntry));

        logger.info("Wallet 이전 완료: direction={}, trackId={}, mchtId={}, walletId={}, hqId={}, amount={}, actor={}",
                collecting ? "본사차감" : "본사지급", track, company, walletId, hqId, amount, actor);

        // 잔액은 트리거가 반영했으므로 확정 후 값을 다시 읽어 응답에 담는다.
        List<SharedMap<String, Object>> balanceList = rows(db, "SELECT balance,available FROM WX.WALLET_BALANCE WHERE walletId=?", walletId);
        return SharedMap.<String, Object>of()
                .add("trackId", track)
                .add("mchtId", company)
                .add("walletId", walletId)
                .add("hqId", hqId)
                .add("amount", amount)
                .add("walletTrxId", merchantEntry)
                .add("hqWalletTrxId", hqEntry)
                .add("balance", balanceList.isEmpty() ? 0 : balanceList.get(0).getLong("balance"))
                .add("availableBalance", balanceList.isEmpty() ? 0 : balanceList.get(0).getLong("available"));
    }

    /**
     * 고객사 Wallet 분개를 기록한다.
     *
     * <p>{@code WxPostingRps.entry}는 정산 출금 전용으로 잠겨 있어 쓰지 않는다. 이전은 입금과 출금
     * 양방향이고 출처가 {@code WALLET_MOVE}다.</p>
     */
    private static String entry(Connection db, SharedMap<String, Object> walletMap, long delta, String track,
                                 String note, String actor, LocalDateTime postedAt) throws SQLException {

        if (db.getAutoCommit() || delta == 0) throw new SQLException("Wallet 이전 트랜잭션을 확인하세요.");

        String walletId = walletMap.getString("id");
        long   before   = walletMap.getLong("balance");
        long   after    = Math.addExact(before, delta);
        // 잔액은 잠근 WALLET_BALANCE 가 기준이다. 순번만 Wallet 단위 최대값에서 이어 붙인다.
        long next = rows(db, "SELECT COALESCE(MAX(`sequence`),0)+1 AS nextSequence FROM WX.TRX_WALLET WHERE walletId=?",
                walletId).get(0).getLong("nextSequence");
        String id = IDUtils.getUnique("T");
        int day = WxPostingRps.businessDay(postedAt);
        update(db, """
                INSERT INTO WX.TRX_WALLET(id,ownerType,mchtId,walletId,hqId,refType,refId,refSeq,`sequence`,
                                          trxType,dtlType,status,amount,depositAmt,withdrawnAmt,balance,balanceAfter,
                                          note,summary,trxDay,trxDate,regId,regDay)
                VALUES(?,'MCHT',?,?,?,'WALLET_MOVE',?,1,?,?,?,'성공',?,?,?,?,?,?,?,?,?,?,?)
                """, id, walletMap.getString("mchtId"), walletId, walletMap.getString("hqId"), track, next,
                delta > 0 ? "입금" : "출금", delta > 0 ? "관리자입금" : "관리자출금", delta,
                delta > 0 ? delta : 0, delta < 0 ? Math.negateExact(delta) : 0, before, after,
                note, delta > 0 ? "본사 지급" : "본사 차감", day, postedAt, actor, day);
        // WALLET_BALANCE의 balance·lastWalletIdx·available은 TR_TRX_WALLET_BALANCE_AI 트리거가 반영한다.
        return id;
    }

    /** 자금 처리 공통: 변경 사유와 접근 IP를 포함한 감사 이력을 같은 트랜잭션에 기록한다. */
    private static void audit(Connection db, String action, String company, String ref, String reason,
                               String accessIp, String actor, int today, SharedMap<String, Object> beforeMap,
                               SharedMap<String, Object> afterMap) throws SQLException {

        update(db, """
                INSERT INTO WX.AUDIT(id,mchtId,unit,action,refType,refId,reason,beforeJson,afterJson,accessIp,
                                     regId,regDay)
                VALUES(?,?,'HQ',?,'TRX_WALLET',LEFT(?,32),?,?,?,?,?,?)
                """, IDUtils.getRandom(32), company, action, ref, reason,
                JacksonUtils.get().toJson(beforeMap), JacksonUtils.get().toJson(afterMap), accessIp, actor, today);
    }
}
