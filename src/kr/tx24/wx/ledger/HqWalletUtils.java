package kr.tx24.wx.ledger;

import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import kr.tx24.lib.db.DBUtils;
import kr.tx24.lib.lang.IDUtils;

/** 본사 Wallet의 확정 입출금을 호출 업무와 같은 트랜잭션에 분개한다. */
public final class HqWalletUtils {

    private HqWalletUtils() {

    }

    public static String post(Connection db, String hqId, long delta, String source, String refId,
            int refSeq, String actor, LocalDateTime postedAt) throws SQLException {

        if (db.getAutoCommit() || !"WX".equals(db.getCatalog()) || delta == 0 || refSeq < 1) {
            throw new SQLException("본사 Wallet 분개 입력과 업무 트랜잭션을 확인하세요.");
        }
        String wallet;
        long before;
        long available;
        long previous;
        String lock = "SELECT w.id,b.balance,b.available,b.lastWalletIdx FROM WX.WALLET w "
                + "JOIN WX.WALLET_BALANCE b ON b.walletId=w.id WHERE w.hqWalletHqId=? FOR UPDATE";
        try (PreparedStatement query = db.prepareStatement(lock)) {
            query.setString(1, hqId);
            try (ResultSet row = query.executeQuery()) {
                // 설정 미비다. 호출부가 업무 거절로 다룰 수 있게 LedgerEx 로 올린다.
                if (!row.next()) throw new LedgerEx(LedgerCode.CONFLICT, "모계좌의 본사 Wallet을 확인해 주세요.");
                wallet = row.getString(1);
                before = row.getLong(2);
                available = row.getLong(3);
                previous = row.getLong(4);
            }
        }

        long amount = delta < 0 ? Math.negateExact(delta) : delta;
        // 잔액 부족은 업무 거절이다. SQLException 으로 올리면 호출부가 시스템 오류로 보아
        // W003 "내부 요청을 처리하지 못했습니다." 로 응답해 운영자가 원인을 알 수 없었다.
        if (delta < 0 && available < amount) {
            throw new LedgerEx(LedgerCode.CONFLICT, "본사 Wallet의 출금가능잔액이 부족합니다.");
        }
        long after = Math.addExact(before, delta);
        // 잔액은 잠근 WALLET_BALANCE 가 기준이다. 순번만 Wallet 단위 최대값에서 이어 붙인다.
        long sequence;
        try (PreparedStatement query = db.prepareStatement("SELECT COALESCE(MAX(`sequence`),0)+1 FROM WX.TRX_WALLET WHERE walletId=?")) {
            query.setString(1, wallet);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("본사 Wallet 원장 순번을 확인하세요.");
                sequence = row.getLong(1);
            }
        }
        String id = IDUtils.getUnique("T");
        String type = delta > 0 ? "입금" : "출금";
        String detail = switch (source) {
            case "STL_DAY_DTL" -> "정산";
            // Wallet 사이의 이전은 관리자 조작이므로 일반 입출금과 구분해 기록한다.
            case "WALLET_MOVE" -> "관리자";
            default -> "일반";
        } + (delta > 0 ? "입금" : "출금");
        int day = Integer.parseInt(postedAt.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        String insert = "INSERT INTO WX.TRX_WALLET(id,ownerType,walletId,hqId,refType,refId,refSeq,`sequence`,trxType,dtlType,"
                + "amount,depositAmt,withdrawnAmt,balance,balanceAfter,trxDay,trxDate,regId,regDay) "
                + "VALUES(?,'HQ',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement query = db.prepareStatement(insert)) {
            DBUtils.setValues(query, id, wallet, hqId, source, refId, refSeq, sequence, type, detail, delta,
                    delta > 0 ? amount : 0, delta < 0 ? amount : 0, before, after, day, postedAt, actor, day);
            if (query.executeUpdate() != 1) throw new SQLException("본사 Wallet 원장 기록에 실패했습니다.");
        }
        // WALLET_BALANCE의 balance·lastWalletIdx·available은 TR_TRX_WALLET_BALANCE_AI 트리거가 반영한다.
        return id;
    }
}
