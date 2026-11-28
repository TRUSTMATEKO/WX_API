package kr.tx24.wx.ledger.rps;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * 고객사 Wallet의 자금 보류를 설정하고 해제한다.
 *
 * <p>보류는 장부잔액을 바꾸지 않는다. {@code WALLET_BALANCE.held}만 올리고
 * {@code WX_WALLET_AVAILABLE} 프로시저가 {@code available = GREATEST(0, balance - held)}를
 * 다시 계산한다. 그래서 출금만 막히고 입금과 집계는 그대로 돈다.</p>
 *
 * <p>왜 원장 프로세스가 가지는가. {@code available}은 송금 접수가 잠금 안에서 읽는 값이다.
 * 본사 화면이 직접 바꾸면 접수와 보류가 서로 다른 잠금 순서로 같은 행을 건드린다. 송금 접수와
 * 같은 프로세스에서 같은 순서로 잠가야 교착과 경합을 피할 수 있다.</p>
 *
 * <p>변경 이력은 {@code WALLET_HOLD}에 남기고 그 번호는 거래번호와 같은 방식으로 만든다. 송금 {@code S},
 * 원장 {@code T} 처럼 보류는 {@code H} 로 시작한다. 운영자가 번호만 보고 무엇인지 알 수 있어야 한다.</p>
 *
 * <p>변경 이력은 {@code WALLET_HOLD}에 남긴다. 일별 마감이 그 시점의 {@code afterAmt}를 읽어
 * 집계에 넣으므로, 이력은 추가 전용이고 지우지 않는다.</p>
 */
public final class WxHoldRps {

    private static final Logger logger = LoggerFactory.getLogger(WxHoldRps.class);

    /** 보류 금액 상한. Wallet 이전과 같은 13자리 정수 범위를 쓴다. */
    private static final long AMOUNT_MAX = 9_999_999_999_999L;

    private WxHoldRps() {

    }

    /**
     * 보류 금액을 지정한 값으로 바꾼다.
     *
     * <p>더하거나 빼지 않고 <b>목표 금액을 그대로 지정한다.</b> 화면이 현재 값을 보고 새 값을
     * 보내므로 두 번 눌러도 같은 결과가 된다. 0을 보내면 해제다.</p>
     *
     * <p>장부잔액보다 큰 보류도 받는다. 분쟁 금액이 잔액을 넘을 수 있고, 프로시저가
     * {@code GREATEST(0, ...)}로 출금가능잔액을 0까지만 내리므로 음수가 생기지 않는다.</p>
     *
     * @param inMap {@code walletId}, {@code amount}, {@code userId}, {@code reason}
     * @return 변경 전후 보류액과 다시 계산한 잔액
     */
    public static SharedMap<String, Object> apply(SharedMap<String, Object> inMap) throws SQLException {

        String walletId = WxPostingRps.field(inMap, "walletId", 32);
        String actor    = WxPostingRps.field(inMap, "userId", 24);
        String reason   = WxPostingRps.field(inMap, "reason", 512);
        String accessIp = inMap.getString("clientIp");
        long   target   = inMap.getLong("amount");
        if (target < 0 || target > AMOUNT_MAX) {
            throw new LedgerEx(LedgerCode.INVALID, "보류 금액을 확인해 주세요.");
        }

        try (Connection db = WxDatabaseRps.open()) {
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                SharedMap<String, Object> resultMap = post(db, walletId, target, reason, actor, accessIp);
                db.commit();

                return resultMap;
            } catch (RuntimeException | SQLException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    /**
     * 보류 변경 본문.
     *
     * <p>송금 접수와 같은 순서로 잠근다. 고객사 Wallet의 잔액 행을 먼저 잠그고 그 안에서만
     * 보류액을 바꾼다. 모계좌는 건드리지 않는다.</p>
     */
    private static SharedMap<String, Object> post(Connection db, String walletId, long target, String reason,
            String actor, String accessIp) throws SQLException {

        SharedMap<String, Object> walletMap = lock(db, walletId);
        long                      before    = walletMap.getLong("held");
        if (before == target) {
            logger.info("보류 금액이 이미 같아 바꾸지 않는다: walletId={}, held={}", walletId, before);
            return result(walletMap, before, target, false);
        }

        if (update(db, "UPDATE WX.WALLET_BALANCE SET held=?,udtId=? WHERE walletId=?",
                target, actor, walletId) != 1) {
            throw new SQLException("Wallet 보류 금액 반영에 실패했습니다.");
        }
        // 출금가능잔액 재계산의 단일 지점이다. held 를 바꾼 뒤 반드시 부른다.
        available(db, walletId);

        LocalDateTime now = WxPostingRps.postingTime(db);
        update(db, """
                INSERT INTO WX.WALLET_HOLD(id,mchtId,walletId,beforeAmt,afterAmt,reason,regId,regDay,regDate)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, IDUtils.getUnique("H"), walletMap.getString("mchtId"), walletId, before, target, reason,
                actor, WxPostingRps.businessDay(now), now);

        // 자금 처리와 같은 형식으로 변경 전후와 접속 IP 를 남긴다.
        update(db, """
                INSERT INTO WX.AUDIT(id,mchtId,unit,action,refType,refId,reason,beforeJson,afterJson,accessIp,
                                     regId,regDay)
                VALUES(?,?,'HQ',?,'WALLET',?,?,?,?,?,?,?)
                """, IDUtils.getRandom(32), walletMap.getString("mchtId"),
                target > before ? "WALLET_HOLD_SET" : "WALLET_HOLD_RELEASE",
                walletId, reason,
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("held", before)),
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("held", target)),
                accessIp, actor, WxPostingRps.businessDay(now));

        logger.info("Wallet 보류 변경: walletId={}, {} -> {}, actor={}", walletId, before, target, actor);
        return result(lock(db, walletId), before, target, true);
    }

    /** 보류 대상 Wallet의 잔액 행을 잠근다. 고객사 Wallet만 보류할 수 있다. */
    private static SharedMap<String, Object> lock(Connection db, String walletId) throws SQLException {

        List<SharedMap<String, Object>> foundList = rows(db, new Retrieve("WX.WALLET W")
                .columns("W.id,W.mchtId,B.balance,B.held,B.available")
                .joinInner("WX.WALLET_BALANCE B", "B.walletId=W.id")
                .where("W.id=? AND W.ownerType='MCHT'")
                .forUpdate().build(), walletId);
        if (foundList.size() != 1) {
            throw new LedgerEx(LedgerCode.CONFLICT, "고객사 Wallet만 보류할 수 있습니다.");
        }
        return foundList.get(0);
    }

    /** 출금가능잔액 재계산. 프로시저 한 곳만 이 계산식을 가진다. */
    private static void available(Connection db, String walletId) throws SQLException {

        try (PreparedStatement call = db.prepareStatement("CALL WX.WX_WALLET_AVAILABLE(?)")) {
            call.setString(1, walletId);
            call.execute();
        }
    }

    /** 응답 공통: 변경 전후 보류액과 다시 계산한 잔액을 담는다. */
    private static SharedMap<String, Object> result(SharedMap<String, Object> walletMap, long before, long after,
            boolean changed) {

        return SharedMap.<String, Object>of()
                .add("walletId", walletMap.getString("id"))
                .add("mchtId", walletMap.getString("mchtId"))
                .add("beforeAmt", before)
                .add("afterAmt", after)
                .add("balance", walletMap.getLong("balance"))
                .add("availableBalance", walletMap.getLong("available"))
                .add("changed", changed);
    }
}
