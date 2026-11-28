package kr.tx24.wx.api.utils;

import java.util.Arrays;
import java.util.List;

import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisTextUtils;
import kr.tx24.lib.redis.RedisUtils;
import kr.tx24.lib.shared.Sms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** WX 시스템 설정의 운영 알림 수신자에게 TX24_MSG 큐로 SMS 발송을 요청한다. */
public final class NotificationUtils {

    private static final Logger logger = LoggerFactory.getLogger(NotificationUtils.class);
    private static final String BALANCE_ALERT_KEY      = "WX:API:BALANCE_ALERT:";
    private static final long   BALANCE_ALERT_INTERVAL = 600;
    private static final int    SMS_MAX_LENGTH        = 90;

    private NotificationUtils() {

    }

    /** 미매칭 입금과 기관 무응답이 발생하면 운영자에게 알린다. */
    public static void incident(String table, String id, String message) {

        sendSms("incidentAlertPhones", table, id, message, "");
    }

    /** 타행이체불능명세로 성공 송금이 정정되면 설정된 운영자에게 알린다. */
    public static void transferFailure(String trxId) {

        sendSms("alert.receiver", "TRX_TRANSFER", trxId,
                "[WX] 타행이체불능으로 송금 성공을 실패로 정정했습니다. 거래번호 " + trxId, "");
    }

    /** 송금 후 잔액이 기준 이하이면 같은 모계좌에서 10분에 한 번 알린다. */
    public static void lowBalance(String hqId, long balance) {

        sendSms("balanceAlertPhones", "HQ_ACCNT", hqId,
                String.format("[WX] 모계좌 %s 잔액 부족 %,d원", hqId, balance),
                BALANCE_ALERT_KEY + hqId);
    }

    /** 알림 실패는 이미 처리한 입금·송금의 결과에 영향을 주지 않는다. */
    private static void sendSms(String column, String table, String id, String message, String throttleKey) {

        boolean throttled = false;
        try {
            String phones;
            if ("alert.receiver".equals(column)) {
                phones = System.getProperty("alert.receiver", "");
            } else {
                RecordSet records = new Retrieve().table("WX.CONFIG").columns(column).where("id", 1).select();
                if (records == null) throw new IllegalStateException("알림 설정 조회 실패");
                SharedMap<String, Object> configMap = records.getRow(0);
                if (configMap == null) return;
                phones = configMap.getString(column);
            }
            List<String> recipientsList = Arrays.stream(phones.split(",")).map(String::trim)
                    .filter(phone -> phone.matches("[0-9]{8,15}")).distinct().toList();
            if (recipientsList.isEmpty()) return;
            if (!throttleKey.isEmpty()) {
                if (!RedisTextUtils.setNx(throttleKey, "1", BALANCE_ALERT_INTERVAL)) return;
                throttled = true;
            }

            Sms sms = new Sms();
            sms.system  = "default";
            sms.table   = table;
            sms.id      = id;
            sms.message = limitSmsMessage(message);
            sms.receiver.addAll(recipientsList);

            Long size = RedisUtils.lpush(SystemUtils.REDIS_STORAGE_MESSAGE, sms);
            if (size == null || size <= 0L) throw new IllegalStateException("알림 큐 적재 실패");
        } catch (RuntimeException failure) {
            if (throttled) {
                try {
                    RedisTextUtils.del(throttleKey);
                } catch (RuntimeException cleanupFailure) {
                    logger.info("잔액 알림 간격 초기화 실패: 대상={}, 오류={}", id, cleanupFailure.getClass().getSimpleName());
                }
            }
            logger.error("운영 알림 큐 적재 실패: 구분={}, 대상={}, 오류={}", table, id, failure.getClass().getSimpleName());
        }
    }

    /** SMS 본문은 유니코드 문자 기준 90자까지 보낸다. */
    private static String limitSmsMessage(String message) {

        if (message.codePointCount(0, message.length()) <= SMS_MAX_LENGTH) return message;

        return message.substring(0, message.offsetByCodePoints(0, SMS_MAX_LENGTH - 1)) + "…";
    }
}
