package kr.tx24.wx.ledger.rps;

import java.sql.ResultSet;
import java.sql.Statement;
import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.map.SharedMap;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import kr.tx24.lib.kms.KMSUtils;
import kr.tx24.wx.ledger.enm.LedgerCode;
import kr.tx24.wx.ledger.ex.LedgerEx;

/**
 * 원장 분개가 함께 쓰는 업무 시각·업무일과 요청값 검사를 모은다.
 *
 * <p>정산 출금 분개는 이 클래스에 없다. 마감은 WX_TASK가 직접 쓰므로
 * {@code WX_TASK}의 {@code DailyWalletTask.postMerchantWithdraw}에 있다.</p>
 */
public final class WxPostingRps {

    private WxPostingRps() {

    }

    // 업무 행을 모두 잠근 뒤 DB 시각을 한 번 읽어 원장과 완료 이벤트에 함께 쓴다.
    static LocalDateTime postingTime(Connection db) throws SQLException {

        try (Statement sql = db.createStatement(); ResultSet rs = sql.executeQuery("SELECT NOW()")) {
            rs.next();
            return rs.getObject(1,LocalDateTime.class);
        }
    }

    static int businessDay(LocalDateTime time) {

        return Integer.parseInt(DateUtils.toString(time.toLocalDate()));
    }

    /** 계좌번호와 기관·웹훅 본문을 암호화한다. 보호되지 않은 원문은 원장에 저장하지 않는다. */
    static String encryptForLedger(String value) throws SQLException {

        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = KMSUtils.encrypt(plain);
        if (encrypted == null || Arrays.equals(plain, encrypted)) {
            throw new SQLException("원장 민감정보 암호화에 실패했습니다.");
        }
        return Base64.getEncoder().encodeToString(encrypted);
    }

    static String field(SharedMap<String,Object> map, String key, int max) {

        String value = map.getString(key).trim();
        if (value.isEmpty() || value.length()>max || value.chars().anyMatch(Character::isISOControl)) throw new LedgerEx(LedgerCode.INVALID);
        return value;
    }


}
