package kr.tx24.wx.api;

import java.util.ArrayList;
import java.util.List;

import kr.tx24.lib.map.SharedMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 기동 시 한 번 확정하는 실행 설정이다.
 *
 * <p>업무 계층은 요청마다 새로 만들어지므로 설정을 생성자로 받지 않고 여기서 읽는다.
 * {@link #configure(SharedMap)}는 Launcher에서만 호출한다.</p>
 */
public final class WalletConfig {

    private static final Logger logger = LoggerFactory.getLogger(WalletConfig.class);

    private static volatile SharedMap<String, Object> property;
    private static volatile String upstreamEnv = "";
    private static volatile String internalPeers = "127.0.0.1";
    private static volatile List<String> alertReceivers = List.of();

    private WalletConfig() {

    }

    /** 기동 설정을 확정한다. 기관 전송 환경이 없으면 송금 접수를 중지로 둔다. */
    public static void configure(SharedMap<String, Object> config) {

        property = config.copy();
        // 내부 경로의 허용 주소다. 생략하면 루프백만 허용한다.
        internalPeers = config.getString("internal.allowedIps", "127.0.0.1").isBlank()
                ? "127.0.0.1" : config.getString("internal.allowedIps", "127.0.0.1").strip();
        alertReceivers = receivers(config.getString("alert.receiver"));
        logger.info("미매칭 입금 알림 수신자: {}명", alertReceivers.size());
        // 설정 키는 env 다. test 와 prod 둘만 받는다. 비었거나 다른 값이면 송금을 접수하지 않는다.
        upstreamEnv = java.util.Set.of("test", "prod").contains(config.getString("env"))
                ? config.getString("env") : "";
        if (upstreamEnv.isEmpty()) {
            logger.warn("실행 환경(env)이 test 도 prod 도 아니어서 송금 접수를 중지한다. 조회 업무만 제공한다.");
        } else if ("test".equals(upstreamEnv)) {
            // 실수로 시험 설정으로 운영하는 일이 없도록 기동 로그에 크게 남긴다.
            logger.warn("기관 전송 환경이 test 이다. HYPHEN 과 통신하지 않고 더미 응답으로 처리한다. "
                    + "실제 송금은 일어나지 않으며 모계좌 잔액은 Redis 의 시험 잔액을 쓴다.");
        } else {
            logger.info("기관 전송 환경이 prod 이다. HYPHEN 으로 실제 전문을 보낸다.");
        }
    }

    /** 확정된 설정. 기동 전에 호출하면 설정 오류로 본다. */
    public static SharedMap<String, Object> get() {

        SharedMap<String, Object> config = property;
        if (config == null) throw new IllegalStateException("WX_API 실행 설정이 확정되지 않았습니다.");
        return config;
    }

    /**
     * 내부 경로의 허용 접속 주소.
     *
     * <p>WX_ADM·WX_TASK 가 {@code /api/internal/**} 로 보내는 요청만 이 목록으로 판정한다. 고객사
     * 경로({@code /api/v1/wallet})는 TX24_HTTP 주소인 {@code gateway.allowedIps} 를 따로 쓴다.
     * 수신부는 공통 INet 서버 하나이고, 경로마다 허용 주소와 키를 다르게 둔다.</p>
     *
     * <p>설정이 없으면 루프백만 허용한다. 비워 두어 아무 데서나 들어오게 하지 않는다.</p>
     */
    public static String internalAllowedIps() {

        return internalPeers;
    }

    /**
     * 미매칭 입금 알림 수신자.
     *
     * <p>{@code alert.receiver} 에 쉼표로 나눠 둔다. 예: {@code 01011112222,01033334444}.
     * 비어 있으면 SMS 를 보내지 않고 로그 경고만 남긴다.</p>
     *
     * <p>숫자만 남기고 8~15자리만 받는다. 모양이 아닌 값은 버리고 기동 로그에 건수를 남긴다.
     * 수신자는 10명까지 둔다. 그 이상은 알림이 아니라 공지다.</p>
     */
    public static List<String> alertReceivers() {

        return alertReceivers;
    }

    /** 기관 전송 환경. 비어 있으면 기관 연동을 쓰지 않는다. */
    public static String upstreamEnv() {

        return upstreamEnv;
    }

    /** 기관으로 전문을 보낼 수 있는 상태인지. */
    public static boolean upstreamReady() {

        return !upstreamEnv.isEmpty();
    }

    /** 수신자 목록을 숫자만 남겨 확인한다. 모양이 아닌 값은 버린다. */
    private static List<String> receivers(String value) {

        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String item : value.split(",")) {
            String phone = item.replaceAll("[^0-9]", "");
            if (phone.length() < 8 || phone.length() > 15) {
                if (!item.isBlank()) logger.warn("미매칭 알림 수신자 모양을 확인하세요. 한 건을 버렸다.");
                continue;
            }
            if (!out.contains(phone)) out.add(phone);
            if (out.size() >= 10) break;
        }
        return List.copyOf(out);
    }

}
