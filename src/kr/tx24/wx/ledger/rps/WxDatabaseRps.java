package kr.tx24.wx.ledger.rps;

import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import kr.tx24.lib.db.DBFactory;
import kr.tx24.lib.db.DBUtils;
import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.map.SharedMap;

/** 보호된 INet 업무가 WX 카탈로그와 서울 시간 기준으로 DB 트랜잭션을 구성한다. */
public final class WxDatabaseRps {

    private WxDatabaseRps() {

    }

    /** INet 업무: 기존 TX24_LIB 풀에서 WX 카탈로그 연결만 사용한다. */
    public static Connection open() throws SQLException {

        Connection db = DBFactory.get().getConnection();
        try {
            if (!"WX".equals(db.getCatalog())) throw new SQLException("WX 카탈로그 연결을 확인해 주세요.");
            return db;
        } catch (SQLException failure) {
            db.close();
            throw failure;
        }
    }

    /** INet 업무 트랜잭션: 고정 SQL과 바인딩 값으로 조회한다. */
    static List<SharedMap<String,Object>> rows(Connection db, String sql, Object... args) throws SQLException {

        try (PreparedStatement statement = db.prepareStatement(sql)) {
            DBUtils.setValues(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                return new RecordSet(result).getRows();
            }
        }
    }

    /** INet 업무 트랜잭션: 열린 업무 연결에서 승인된 SQL을 실행한다. */
    static int update(Connection db, String sql, Object... args) throws SQLException {

        try (PreparedStatement statement = db.prepareStatement(sql)) {
            DBUtils.setValues(statement, args);
            return statement.executeUpdate();
        }
    }
}
