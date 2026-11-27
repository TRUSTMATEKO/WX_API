package kr.tx24.wx.api;

import java.nio.file.Files;
import kr.tx24.lib.db.DBFactory;
import kr.tx24.wx.config.InternalApiConfig;
import java.sql.*;

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
    private static final InternalApiConfig CONFIG = new InternalApiConfig(true);
    private static final String UPSTREAM_ENV;

    static {
        // 기관 원본은 전문 본문을 기록하므로 이 로거를 끄고 호출부의 처리 결과 로그만 남긴다.
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(HyphenPisp.class.getName()).setLevel(Level.OFF);
        SystemUtils.disableDeepView();

        ENABLED = PROPERTY.getBoolean("enabled");
        if (!ENABLED) {
            // 키와 허용 주소를 비워 모든 업무 경로가 요청 시점에 거절하도록 둔다.
            logger.error("WX_API 가 비활성(enabled=false)이다. 업무 경로를 모두 거절한다.");
        }
        UPSTREAM_ENV = ENABLED ? activateEnvironment() : "";
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

    /** 프로세스 시작 시에만 예정 환경을 적용한다. 전환 중 미결 업무가 있으면 기관 연동을 닫는다. */
    private static String activateEnvironment() {

        try (Connection db = DBFactory.get().getConnection()) {
            db.setAutoCommit(false);
            try (Statement query = db.createStatement()) {
                String env;
                String applied;
                try (ResultSet row = query.executeQuery("SELECT apiEnv,apiAppliedEnv FROM WX.CONFIG WHERE id=1 FOR UPDATE")) {
                    if (!row.next()) throw new SQLException("설정 없음");
                    env = row.getString(1);
                    applied = row.getString(2);
                }
                if (!java.util.Set.of("test", "prod").contains(env)) throw new SQLException("환경 미등록");
                if (!env.equals(applied)) {
                    try (ResultSet row = query.executeQuery("""
                            SELECT EXISTS(SELECT 1 FROM WX.TRX_TRANSFER WHERE status IN ('접수','처리중','미확인'))
                                OR EXISTS(SELECT 1 FROM WX.TRX_HQ_WITHDRAW WHERE status IN ('접수','처리중','미확인'))
                                OR EXISTS(SELECT 1 FROM WX.HQ_BALANCE_RETRY)
                            """)) {
                        row.next();
                        if (row.getBoolean(1)) throw new SQLException("미결 업무");
                    }
                    try (PreparedStatement update = db.prepareStatement("UPDATE WX.CONFIG SET apiAppliedEnv=? WHERE id=1")) {
                        update.setString(1, env);
                        update.executeUpdate();
                    }
                }
                db.commit();
                return env;
            } catch (SQLException | RuntimeException failure) {
                db.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            logger.error("기관 환경 적용 실패. 설정과 미결 거래를 확인한 뒤 WX_API를 재시작하세요.");
            return "";
        }
    }

    private WalletConfig() {

    }

    /**
     * 설정을 읽는다. 설정이 없어도 던지지 않는다.
     *
     * <p>{@code INetConfigLoader} 는 {@code inet.json} 이 없으면 {@code System.exit(1)} 로 프로세스를
     * 내린다. 이 클래스는 {@code Router} 의 컨트롤러 적재 중에 처음 쓰이므로, 그 자리에서 내려가면
     * 기동이 어디까지 갔는지 남지 않는다. 로더를 깨우기 전에 파일부터 본다. 경로는 로더가 쓰는
     * 것과 같다. WX_ADM {@code WxApiRps}·WX_TASK {@code TransferNetErrTask} 와 같은 방식이다.</p>
     *
     * <p>수신 서버는 {@code host}·{@code port} 가 필요해 이 파일 없이는 기동하지 못한다. 그 거절은
     * {@code INetServer} 가 기동 시점에 하고, 여기서는 업무 경로를 거절할 수 있도록 비워 둔다.
     * 정적 초기화에서 던지면 {@code Router} 의 적재 루프가 삼켜 경로 하나가 조용히 빠진다.</p>
     */
    private static SharedMap<String, Object> load() {

        if (!Files.isRegularFile(SystemUtils.getINetConfigPath())) {
            logger.error("conf/inet.json 이 없어 업무 경로를 모두 거절한다: {}",
                    SystemUtils.getINetConfigPath().toAbsolutePath());
            return SharedMap.<String, Object>of();
        }
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
     * 자신의 IP 는 Wallet 에 등록한 {@code WALLET_API_IPS} 목록으로 원장이 따로 대조한다.</p>
     */
    public static String gatewayAllowedIps() {

        return ENABLED ? CONFIG.read().getString("apiGatewayIps") : "";
    }

    /** 내부 요청 한 건의 IP와 현재·이전 인증키를 같은 설정 버전으로 확인한다. */
    public static SharedMap<String, Object> internal() {

        return ENABLED ? CONFIG.read() : SharedMap.of();
    }

    /** 교체 직후 허용된 송신자가 새 키로 먼저 접속한 경우 DB 버전을 다시 확인한다. */
    public static SharedMap<String, Object> reloadInternal() {

        return ENABLED ? CONFIG.reload() : SharedMap.of();
    }

    /** 기관 전송 환경. 비어 있으면 기관 연동을 쓰지 않는다. */
    public static String upstreamEnv() {

        return UPSTREAM_ENV;
    }

    /** 기관으로 전문을 보낼 수 있는 상태인지. */
    public static boolean upstreamReady() {

        return !UPSTREAM_ENV.isEmpty();
    }

    /** 고객사 포털 웹훅 재접수 경로의 전용 키를 읽는다. */
    public static String portalRetryKey() {

        return ENABLED ? PROPERTY.getString("portal.webhook.key") : "";
    }

    /** 고객사 포털 웹훅 재접수를 요청할 수 있는 서버 주소를 읽는다. */
    public static String portalPeers() {

        return peers(PROPERTY.getString("portal.allowedIps"));
    }

    /** 허용 주소 목록. 비어 있으면 루프백만 허용한다. */
    private static String peers(String value) {

        return value == null || value.isBlank() ? "127.0.0.1" : value.strip();
    }

}
