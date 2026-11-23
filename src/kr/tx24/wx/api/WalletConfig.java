package kr.tx24.wx.api;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import kr.tx24.inet.conf.INetConfigLoader;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.hyphen.HyphenPisp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 실행 설정을 한 번 읽어 두는 자리다.
 *
 * <p>OPL_API 의 {@code CoreConstants} 와 같은 구조다. 전용 Launcher 없이 범용
 * {@code kr.tx24.inet.server.INetServer} 로 뜨므로, 이 클래스가 처음 쓰일 때 설정을 읽는다.
 * 서버 생성자가 {@code INetConfigLoader.start()} 를 먼저 끝내고 {@code Router.start()} 로
 * 컨트롤러를 적재하므로, 그 적재 시점에 설정은 이미 메모리에 있다.</p>
 *
 * <p>정적 초기화에서 예외를 던지지 않는다. Router 의 적재 루프가 예외를 삼키고 넘어가므로
 * 던지면 경로 하나가 조용히 빠진다. 값이 없으면 비워 두고, 거절은 요청 시점에 한다.</p>
 */
public final class WalletConfig {

    private static final Logger logger = LoggerFactory.getLogger(WalletConfig.class);

    /** {@code inet.json} 의 {@code property}. 없으면 빈 맵이다. */
    private static final SharedMap<String, Object> PROPERTY = load();

    private static final boolean ENABLED;
    private static final String GATEWAY_PEERS;
    private static final String INTERNAL_PEERS;
    private static final String INTERNAL_KEY;
    private static final String UPSTREAM_ENV;
    private static final List<String> ALERT_RECEIVERS;

    static {
        // 기관 원본은 전문 본문을 기록하므로 이 로거를 끄고 호출부의 처리 결과 로그만 남긴다.
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(HyphenPisp.class.getName()).setLevel(Level.OFF);
        SystemUtils.disableDeepView();

        ENABLED = PROPERTY.getBoolean("enabled");
        if (!ENABLED) {
            // 키와 허용 주소를 비워 모든 업무 경로가 요청 시점에 거절하도록 둔다.
            logger.error("WX_API 가 비활성(enabled=false)이다. 업무 경로를 모두 거절한다.");
        }
        GATEWAY_PEERS  = ENABLED ? peers(PROPERTY.getString("gateway.allowedIps", "127.0.0.1")) : "";
        INTERNAL_PEERS = ENABLED ? peers(PROPERTY.getString("internal.allowedIps", "127.0.0.1")) : "";
        INTERNAL_KEY   = ENABLED ? PROPERTY.getString("internal_api.key") : "";
        if (INTERNAL_KEY.length() < 32) {
            logger.warn("내부 경로 키(internal_api.key)가 없어 /api/internal/** 경로를 거절한다.");
        }

        ALERT_RECEIVERS = receivers(PROPERTY.getString("alert.receiver"));
        logger.info("미매칭 입금 알림 수신자: {}명", ALERT_RECEIVERS.size());

        // 설정 키는 env 다. test 와 prod 둘만 받는다. 비었거나 다른 값이면 송금을 접수하지 않는다.
        UPSTREAM_ENV = java.util.Set.of("test", "prod").contains(PROPERTY.getString("env"))
                ? PROPERTY.getString("env") : "";
        if (UPSTREAM_ENV.isEmpty()) {
            logger.warn("실행 환경(env)이 test 도 prod 도 아니어서 송금 접수를 중지한다. 조회 업무만 제공한다.");
        } else if ("test".equals(UPSTREAM_ENV)) {
            // 설정대로 동작하는 상태이므로 기동 사실만 남긴다.
            logger.info("기관 전송 환경이 test 이다. HYPHEN 과 통신하지 않고 더미 응답으로 처리한다. "
                    + "실제 송금은 일어나지 않으며 모계좌 잔액은 Redis 의 시험 잔액을 쓴다.");
        } else {
            logger.info("기관 전송 환경이 prod 이다. HYPHEN 으로 실제 전문을 보낸다.");
        }
    }

    private WalletConfig() {

    }

    /** 설정을 읽는다. 설정이 없어도 던지지 않는다. */
    private static SharedMap<String, Object> load() {

        try {
            SharedMap<String, Object> map = INetConfigLoader.getMap("property", TypeRegistry.MAP_SHAREDMAP_OBJECT);
            return map == null ? SharedMap.<String, Object>of() : map;
        } catch (RuntimeException failure) {
            return SharedMap.<String, Object>of();
        }
    }

    /** 확정된 설정. */
    public static SharedMap<String, Object> get() {

        return PROPERTY;
    }

    /** 업무 경로를 열어도 되는 상태인지. */
    public static boolean enabled() {

        return ENABLED;
    }

    /**
     * 고객사 경로의 허용 접속 주소.
     *
     * <p>TX24_HTTP 가 {@code /api/v1/wallet/**} 로 보내는 요청만 이 목록으로 판정한다. 고객사
     * 자신의 IP 는 API 키에 등록한 {@code MCHT_WALLET_API_KEY.ips} 로 원장이 따로 대조한다.</p>
     */
    public static String gatewayAllowedIps() {

        return GATEWAY_PEERS;
    }

    /**
     * 내부 경로의 허용 접속 주소.
     *
     * <p>WX_ADM·WX_TASK 가 {@code /api/internal/**} 로 보내는 요청만 이 목록으로 판정한다.
     * 수신부는 공통 INet 서버 하나이고, 경로마다 허용 주소와 키를 다르게 둔다.</p>
     *
     * <p>설정이 없으면 루프백만 허용한다. 비워 두어 아무 데서나 들어오게 하지 않는다.</p>
     */
    public static String internalAllowedIps() {

        return INTERNAL_PEERS;
    }

    /** 내부 경로 전용 키. WX_ADM·WX_TASK·입금 통지 시스템이 모두 이 키 하나를 쓴다. */
    public static String internalKey() {

        return INTERNAL_KEY;
    }

    /** 내부 경로를 열어도 되는 상태인지. 키가 없으면 경로를 거절한다. */
    public static boolean internalReady() {

        return INTERNAL_KEY.length() >= 32;
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

        return ALERT_RECEIVERS;
    }

    /** 기관 전송 환경. 비어 있으면 기관 연동을 쓰지 않는다. */
    public static String upstreamEnv() {

        return UPSTREAM_ENV;
    }

    /** 기관으로 전문을 보낼 수 있는 상태인지. */
    public static boolean upstreamReady() {

        return !UPSTREAM_ENV.isEmpty();
    }

    /** 허용 주소 목록. 비어 있으면 루프백만 허용한다. */
    private static String peers(String value) {

        return value == null || value.isBlank() ? "127.0.0.1" : value.strip();
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
