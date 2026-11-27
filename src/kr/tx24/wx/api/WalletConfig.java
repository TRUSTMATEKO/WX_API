package kr.tx24.wx.api;

import kr.tx24.lib.db.DBFactory;
import kr.tx24.wx.config.InternalApiConfig;
import java.sql.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.hyphen.HyphenPisp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WX.CONFIG의 기관 환경을 기동 시 확정하고 내부 연동 설정을 제공한다.
 *
 * <p>OPL_API 의 {@code CoreConstants} 와 같은 구조다. 전용 Launcher 없이 범용
 * {@code kr.tx24.inet.server.INetServer} 로 뜨므로, {@code Router}의 컨트롤러 적재 시점에
 * 기관 환경을 확정한다.</p>
 *
 * <p>정적 초기화에서 예외를 던지지 않는다. Router 의 적재 루프가 예외를 삼키고 넘어가므로
 * 던지면 경로 하나가 조용히 빠진다. 기관 환경 적용에 실패하면 기관 연동을 닫는다.</p>
 */
public final class WalletConfig {

    private static final Logger logger = LoggerFactory.getLogger(WalletConfig.class);

    private static final InternalApiConfig CONFIG = new InternalApiConfig(true);
    private static final String UPSTREAM_ENV;

    static {
        // 기관 원본은 전문 본문을 기록하므로 이 로거를 끄고 호출부의 처리 결과 로그만 남긴다.
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(HyphenPisp.class.getName()).setLevel(Level.OFF);
        SystemUtils.disableDeepView();

        UPSTREAM_ENV = activateEnvironment();
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
                int configId;
                String env;
                String applied;
                try (ResultSet row = query.executeQuery("SELECT id,apiEnv,apiAppliedEnv FROM WX.CONFIG FOR UPDATE")) {
                    if (!row.next()) throw new SQLException("설정 없음");
                    configId = row.getInt("id");
                    env      = row.getString("apiEnv");
                    applied  = row.getString("apiAppliedEnv");
                    if (row.next()) throw new SQLException("설정이 한 건이어야 합니다.");
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
                    try (PreparedStatement update = db.prepareStatement("UPDATE WX.CONFIG SET apiAppliedEnv=? WHERE id=?")) {
                        update.setString(1, env);
                        update.setInt(2, configId);
                        if (update.executeUpdate() != 1) throw new SQLException("기관 환경 적용에 실패했습니다.");
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

        return SystemUtils.getSystemProperty("portal.webhook.key", "");
    }

    /** 고객사 포털 웹훅 재접수를 요청할 수 있는 서버 주소를 읽는다. */
    public static String portalPeers() {

        String value = SystemUtils.getSystemProperty("portal.allowedIps", "");
        return value.isBlank() ? "127.0.0.1" : value.strip();
    }

}
