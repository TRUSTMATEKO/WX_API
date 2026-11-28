package kr.tx24.wx.api.utils;

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
 * <p>SMS에는 입금번호, 수신 경로와 금액을 담는다. 입금시각은 로그에서 확인한다.</p>
 */
public final class DepositAlert {

    private static final Logger logger = LoggerFactory.getLogger(DepositAlert.class);

    private DepositAlert() {

    }

    /**
     * 미매칭 입금 한 건을 경고로 남기고 지정 수신자에게 SMS 를 보낸다.
     *
     * @param depositId   WX 입금번호
     * @param sourceType  {@code sms} 또는 {@code va}
     * @param occurredAt  통지가 알려 준 입금시각 {@code yyyyMMddHHmmss}
     * @param amount      입금액
     */
    public static void mismatch(String depositId, String sourceType, String occurredAt, long amount, String trace) {

        logger.warn("입금자명 확인 불일치, 미매칭으로 두었다: depositId={}, 경로={}, 입금시각={}, 금액={}원, trace={}",
                depositId, sourceType, occurredAt, amount, trace);

        String source = "va".equals(sourceType) ? "가상계좌" : "SMS";
        NotificationUtils.incident("TRX_DEPOSIT", depositId,
                String.format("[WX] 입금 미매칭(%s) 입금번호 %s %,d원", source, depositId, amount));
    }
}
