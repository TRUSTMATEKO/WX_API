package kr.tx24.wx.api.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.redis.RedisUtils;
import kr.tx24.lib.shared.Sms;
import kr.tx24.wx.api.WalletConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 미매칭 입금을 운영자에게 알린다.
 *
 * <p>입금은 들어왔는데 어느 Wallet 인지 정하지 못한 상태다. 그대로 두면 고객사는 돈이 들어오지 않은
 * 것으로 보인다. 그래서 로그에 경고를 남기고 지정한 수신자에게 SMS 를 보낸다. 운영자가 본사 입금
 * 화면에서 Wallet 을 지정하면 그때 반영된다.</p>
 *
 * <p>발송은 기존 TX24_MSG 큐를 쓴다. 큐가 막혀도 입금 기록은 이미 끝난 일이므로 예외를 올리지 않고
 * 로그만 남긴다. 알림 실패가 원장을 되돌리게 하지 않는다.</p>
 *
 * <p>문구에는 계좌번호를 넣지 않는다. 운영자가 건을 찾는 데 필요한 입금번호와 금액, 입금자명,
 * 입금시각만 담는다.</p>
 */
public final class DepositAlert {

    private static final Logger logger = LoggerFactory.getLogger(DepositAlert.class);

    /** 통지가 알려 준 입금시각의 모양. */
    private static final DateTimeFormatter OCCURRED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 문구에 넣는 입금시각의 모양. */
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private DepositAlert() {

    }

    /**
     * 미매칭 입금 한 건을 경고로 남기고 지정 수신자에게 SMS 를 보낸다.
     *
     * @param depositId   WX 입금번호
     * @param sourceType  {@code sms} 또는 {@code va}
     * @param depositName 통지가 알려 준 입금자명
     * @param occurredAt  통지가 알려 준 입금시각 {@code yyyyMMddHHmmss}
     * @param amount      입금액
     */
    public static void mismatch(String depositId, String sourceType, String depositName, String occurredAt,
                                 long amount, String trace) {

        List<String> receivers = WalletConfig.alertReceivers();
        logger.warn("입금자명 확인 불일치, 미매칭으로 두었다. 본사 입금 화면에서 Wallet 을 지정해야 한다: "
                        + "depositId={}, 경로={}, 입금자명={}, 입금시각={}, 금액={}원, 수신자={}명, trace={}",
                depositId, sourceType, depositName, occurredAt, amount, receivers.size(), trace);
        if (receivers.isEmpty()) {
            logger.warn("미매칭 알림 수신자(alert.receiver)가 없어 SMS 를 보내지 않는다: depositId={}", depositId);
            return;
        }
        try {
            Sms sms = new Sms();
            sms.system = "default";
            sms.table = "TRX_DEPOSIT";
            sms.id = depositId;
            sms.message = message(depositId, sourceType, depositName, occurredAt, amount);
            sms.receiver.addAll(receivers);
            Long size = RedisUtils.lpush(SystemUtils.REDIS_STORAGE_MESSAGE, sms);
            if (size == null || size <= 0L) {
                logger.warn("미매칭 알림을 메시지 큐가 받지 않았다: depositId={}", depositId);
                return;
            }
            logger.info("미매칭 알림 발송 요청: depositId={}, 수신자={}명", depositId, receivers.size());
        } catch (RuntimeException failure) {
            // 알림 실패로 입금 기록을 되돌리지 않는다. 미매칭 목록과 로그에 건은 그대로 남는다.
            logger.warn("미매칭 알림 발송 실패: depositId={}, type={}", depositId, failure.getClass().getSimpleName());
        }
    }

    /** 운영자가 건을 바로 찾을 수 있는 짧은 문구. 계좌번호는 넣지 않는다. */
    private static String message(String depositId, String sourceType, String depositName, String occurredAt,
                                   long amount) {

        String when = occurredAt;
        try {
            when = LocalDateTime.parse(occurredAt, OCCURRED).format(SHOWN);
        } catch (RuntimeException ignored) {
            // 통지가 준 값이 모양에 맞지 않으면 원문을 그대로 보여 준다.
        }
        return "[WX] 입금자명 확인 불일치. " + ("va".equals(sourceType) ? "가상계좌" : "SMS") + " 입금 "
                + String.format("%,d", amount) + "원, 입금자명 " + depositName + ", " + when
                + ". 입금번호 " + depositId + ". 본사 입금 화면에서 Wallet 을 지정해 주세요.";
    }
}
