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
    private static final String BALANCE_ALERT_KEY = "WX:API:BALANCE_ALERT:";
    private static final long BALANCE_ALERT_INTERVAL = 600;

    private NotificationUtils() {

    }

    /** 미매칭 입금과 기관 무응답이 발생하면 운영자에게 알린다. */
    public static void incident(String table, String id, String message) {

        sendSms("incidentAlertPhones", table, id, message, "");
    }

    /** 송금 후 잔액이 기준 이하이면 같은 모계좌에서 10분에 한 번 알린다. */
    public static void lowBalance(String hqId, long balance, long threshold) {

        sendSms("balanceAlertPhones", "HQ_ACCNT", hqId,
                String.format("[WX] 모계좌 %s 잔액 %,d원, 알림 기준 %,d원. 송금 재원을 확인해 주세요.", hqId, balance, threshold),
                BALANCE_ALERT_KEY + hqId);
    }

    /** 알림 실패는 이미 처리한 입금·송금의 결과에 영향을 주지 않는다. */
    private static void sendSms(String column, String table, String id, String message, String throttleKey) {

        boolean throttled = false;
        try {
            RecordSet records = new Retrieve().table("WX.CONFIG").columns(column).where("id", 1).select();
            if (records == null) throw new IllegalStateException("알림 설정 조회 실패");
            SharedMap<String, Object> config = records.getRow(0);
            if (config == null) return;
            List<String> recipients = Arrays.stream(config.getString(column).split(","))
                    .filter(phone -> phone.matches("[0-9]{8,15}")).distinct().toList();
            if (recipients.isEmpty()) return;
            if (!throttleKey.isEmpty()) {
                if (!RedisTextUtils.setNx(throttleKey, "1", BALANCE_ALERT_INTERVAL)) return;
                throttled = true;
            }

            Sms sms = new Sms();
            sms.system  = "default";
            sms.table   = table;
            sms.id      = id;
            sms.message = message;
            sms.receiver.addAll(recipients);
            Long size = RedisUtils.lpush(SystemUtils.REDIS_STORAGE_MESSAGE, sms);
            if (size == null || size <= 0L) throw new IllegalStateException("알림 큐 적재 실패");
        } catch (RuntimeException failure) {
            if (throttled) {
                try {
                    RedisTextUtils.del(throttleKey);
                } catch (RuntimeException cleanupFailure) {
                    logger.warn("잔액 알림 간격 초기화 실패: 대상={}, 오류={}", id, cleanupFailure.getClass().getSimpleName());
                }
            }
            logger.warn("운영 알림 큐 적재 실패: 구분={}, 대상={}, 오류={}", table, id, failure.getClass().getSimpleName());
        }
    }
}
