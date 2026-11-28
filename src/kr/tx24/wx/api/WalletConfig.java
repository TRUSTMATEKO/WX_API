package kr.tx24.wx.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import kr.tx24.inet.conf.INetConfigLoader;
import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisText;
import kr.tx24.lib.redis.RedisTextUtils;
import kr.tx24.wx.api.hyphen.HyphenPisp;
import kr.tx24.wx.config.InternalApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * WX.CONFIG의 기관 환경을 기동 시 확정하고 내부 연동 설정을 제공한다.
 * INet 경로를 적재할 때 이 클래스를 초기화한다.
 */
public final class WalletConfig {

    private static final Logger logger = LoggerFactory.getLogger(WalletConfig.class);

    private static final InternalApiConfig CONFIG = new InternalApiConfig(true);
    private static final String HYPHEN_KEY = "WX:CONFIG:HYPHEN:API_KEY";
    private static final String UPSTREAM_ENV;

    static {
        // 기관 원본은 전문 본문을 기록하므로 이 로거를 끄고 호출부의 처리 결과 로그만 남긴다.
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(HyphenPisp.class.getName()).setLevel(Level.OFF);
        SystemUtils.disableDeepView();

        int port = INetConfigLoader.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("WX_API INet 포트를 확인하세요.");
        }

        UPSTREAM_ENV = readEnvironment();
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "wx-hyphen-key");
            thread.setDaemon(true);
            return thread;
        }).scheduleWithFixedDelay(WalletConfig::publishHyphenKey, 0, 1, TimeUnit.SECONDS);
        if ("test".equals(UPSTREAM_ENV)) {
            // 설정대로 동작하는 상태이므로 기동 사실만 남긴다.
            logger.info("기관 전송 환경이 test 이다. 송금은 더미 응답으로 처리하고 예금주 조회는 테스트예금주를 반환한다. "
                    + "실제 송금은 일어나지 않으며 모계좌 잔액은 Redis 의 시험 잔액을 쓴다.");
        } else {
            logger.info("기관 전송 환경이 prod 이다. 송금과 예금주 조회는 각각 설정된 운영 기관으로 전문을 보낸다.");
        }
    }

    /** 기동 시 연결된 환경별 DB에서 기관 연동 환경을 읽는다. */
    private static String readEnvironment() {

        RecordSet records;
        try {
            records = new Retrieve("WX.CONFIG").column("apiEnv").limit(2).select();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("WX.CONFIG에서 apiEnv를 읽을 수 없습니다.", failure);
        }

        SharedMap<String, Object> rowMap = records == null ? null : records.getRow(0);
        if (rowMap == null || records.getRow(1) != null) {
            throw new IllegalStateException("WX.CONFIG 설정 행이 정확히 하나여야 합니다.");
        }

        String env = rowMap.getString("apiEnv");
        if (!"test".equals(env) && !"prod".equals(env)) {
            throw new IllegalStateException("WX.CONFIG.apiEnv에 test 또는 prod를 설정하세요.");
        }
        return env;
    }

    /** HYPHEN 수신기가 읽을 설정 버전과 현재 키 암호문을 한 값으로 게시한다. */
    private static void publishHyphenKey() {

        try {
            SharedMap<String, Object> configMap = CONFIG.read();
            String snapshot = configMap.getString("apiConfigVersion") + ":" + configMap.getString("apiKeyEnc");
            if (snapshot.equals(RedisTextUtils.get(HYPHEN_KEY))) return;

            // 다른 서버의 게시와 경합하면 다음 주기에 재시도한다. WATCH는 전용 연결에서만 사용한다.
            try (var connection = RedisText.getClient().connect()) {
                var commands = connection.sync();
                commands.watch(HYPHEN_KEY);
                configMap = CONFIG.reload();
                commands.multi();
                commands.set(HYPHEN_KEY, configMap.getString("apiConfigVersion") + ":" + configMap.getString("apiKeyEnc"));
                commands.exec();
            }
        } catch (RuntimeException failure) {
            logger.warn("HYPHEN 내부 키 게시 실패. 다음 주기에 다시 확인합니다. type={}", failure.getClass().getSimpleName());
        }
    }

    private WalletConfig() {

    }

    /** 서버 기동 중 호출하여 JVM이 이 클래스의 정적 초기화를 마치도록 한다. */
    public static void initialize() {

        // 정적 초기화 블록에서 설정을 적용한다.
    }

    /**
     * 고객사 경로의 허용 접속 주소.
     *
     * <p>TX24_HTTP 가 {@code /api/v1/wallet/**} 로 보내는 요청만 이 목록으로 판정한다. 고객사
     * 자신의 IP 는 Wallet 에 등록한 {@code WALLET_API_IPS} 목록으로 원장이 따로 대조한다.</p>
     */
    public static String gatewayAllowedIps() {

        return CONFIG.read().getString("apiGatewayIps");
    }

    /** 내부 요청 한 건의 IP와 현재·이전 인증키를 같은 설정 버전으로 확인한다. */
    public static SharedMap<String, Object> internal() {

        return CONFIG.read();
    }

    /** 교체 직후 허용된 송신자가 새 키로 먼저 접속한 경우 DB 버전을 다시 확인한다. */
    public static SharedMap<String, Object> reloadInternal() {

        return CONFIG.reload();
    }

    /** 기동 시 확인한 기관 전송 환경(test 또는 prod). */
    public static String upstreamEnv() {

        return UPSTREAM_ENV;
    }

    /** 고객사 포털 웹훅 재접수 경로의 전용 키를 읽는다. */
    public static String portalRetryKey() {

        return SystemUtils.getSystemProperty("portal.webhook.key", "");
    }

    /** 고객사 포털 웹훅 재접수를 요청할 수 있는 서버 주소를 읽는다. */
    public static String portalPeers() {

        String value = SystemUtils.getSystemProperty("portal.allowedIps", "");
        return value.isBlank() ? "127.0.0.1" : value.strip();
    }

}
