package kr.tx24.wx.api;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.IpMatcherUtils;
import kr.tx24.lib.redis.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 공개 API 요청의 접근 구역을 판정한다.
 *
 * <p>OPL_API의 {@code ApiZone}과 같은 자리다. 인증보다 앞에서 판정하므로 차단된 요청은
 * 원장 호출도, API 요청 이력도 남기지 않는다.</p>
 *
 * <p>차단 사유는 응답으로 내보내지 않는다. 고객사에는 인증·접속 IP 실패({@code W002})로만
 * 전달해 어떤 규칙에 걸렸는지 알려주지 않는다.</p>
 */
public final class WalletZone {

    private static final Logger logger = LoggerFactory.getLogger(WalletZone.class);

    /**
     * 내부망으로 보는 주소.
     *
     * <p>WX_ADM의 {@code ClientIpUtils.ADMIN_DEFAULT_RANGES}와 같은 기준에 원장 서버 주소를 더했다.
     * 사설 주소에는 국가가 없으므로 국가 판정을 건너뛴다.</p>
     */
    private static final IpMatcherUtils INTERNAL = new IpMatcherUtils(List.of(
            "127.0.0.1",
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "220.76.95.27/32", "112.175.48.155/32"));

    /** 허용 국가. 사설·미확인 주소는 {@code ZZ}로 회신되며 차단하지 않는다. */
    private static final List<String> ALLOWED_COUNTRY = List.of("KR", "ZZ");

    /** 차단 목록 갱신 주기. WX_ADM의 CONFIG 갱신과 같은 10분이다. */
    private static final long REFRESH_MILLIS = 600_000L;

    /** 갱신 실패 후 다시 시도하는 간격. 조회가 막혔을 때 요청마다 DB를 두드리지 않는다. */
    private static final long RETRY_MILLIS = 30_000L;

    /** 차단 규칙으로 받아들이는 모양. 호스트 이름이 들어와 DNS 조회가 일어나지 않게 막는다. */
    private static final String RULE = "[0-9]{1,3}(\\.[0-9]{1,3}){3}(/[0-9]{1,2})?";

    private static volatile IpMatcherUtils denied = new IpMatcherUtils(List.of());
    private static volatile int deniedCount = 0;
    private static volatile long loadedAt = 0L;
    private static volatile long retryAt = 0L;

    private WalletZone() {

    }

    /**
     * 기동 시 차단 목록을 한 번 읽어 DB 연결과 표를 확인한다.
     *
     * <p>설정이 잘못되었으면 여기서 실패해야 한다. 운영 중에는 조회가 막혀도 업무를 멈추지 않지만,
     * 기동 시점의 실패를 숨기면 차단이 걸려 있다고 착각한 채로 서비스가 열린다.</p>
     */
    public static void warmUp() {

        RecordSet records = new Retrieve().select("SELECT ip FROM WX.API_IP_BLACK WHERE status='사용'"
                + " AND (expiresAt IS NULL OR expiresAt>NOW())");
        if (records == null) throw new IllegalStateException("WX.API_IP_BLACK 조회를 확인하세요.");
        apply(records);
        logger.info("API 차단 목록 확인: count={}", deniedCount);
    }

    /** 사설 대역과 지정 egress 주소인지 판정한다. */
    public static boolean isInternalRequest(String clientIp) {

        return clientIp != null && INTERNAL.matches(clientIp);
    }

    /**
     * 요청 주소가 차단 대상인지 확인한다. 차단이면 {@link WalletException}을 던진다.
     *
     * @param clientIp 게이트웨이가 전달한 고객사 접속 주소
     */
    public static void verify(String clientIp) {

        if (clientIp == null || clientIp.isBlank()) {
            throw new WalletException("W002", "Wallet 인증 또는 접속 IP 확인에 실패했습니다.");
        }

        boolean internal = isInternalRequest(clientIp);
        if (!internal && isBlockCountry(clientIp)) {
            logger.warn("허용하지 않는 국가에서 들어온 API 요청을 차단한다: clientIp={}", clientIp);
            throw new WalletException("W002", "Wallet 인증 또는 접속 IP 확인에 실패했습니다.");
        }
        if (isBlockIp(clientIp)) {
            logger.warn("차단 목록에 등록된 주소의 API 요청을 차단한다: clientIp={}", clientIp);
            throw new WalletException("W002", "Wallet 인증 또는 접속 IP 확인에 실패했습니다.");
        }
        logger.debug("API 접근 구역 확인: clientIp={}, internal={}", clientIp, internal);
    }

    /**
     * 허용 국가가 아닌지 판정한다.
     *
     * <p>Redis GeoIP Function으로 조회한다. 조회에 실패하거나 GeoIP 키가 없으면 {@code ZZ}가 되어
     * 차단하지 않는다. 국가 판정은 보조 수단이고, 이것이 막혀서 정상 거래가 거절되면 안 된다.</p>
     */
    public static boolean isBlockCountry(String clientIp) {

        String country;
        try {
            country = RedisUtils.getGeoIpCountry(clientIp);
        } catch (RuntimeException failure) {
            // 국가 조회가 불가능해도 업무를 멈추지 않는다. 차단 목록과 키별 허용 IP가 남아 있다.
            logger.warn("국가 조회에 실패해 국가 판정을 건너뛴다: clientIp={}, type={}",
                    clientIp, failure.getClass().getSimpleName());
            return false;
        }
        if (country == null || country.isBlank()) return false;
        return !ALLOWED_COUNTRY.contains(country.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * 차단 목록에 맞는 규칙이 있는지 판정한다.
     *
     * <p>OPL은 IP 한 건을 Redis 30분 캐시로 조회한다. WX는 CIDR 규칙을 허용하므로 한 건 조회로는
     * 판정할 수 없다. 그래서 목록 전체를 10분 주기로 읽어 둔다.</p>
     */
    public static boolean isBlockIp(String clientIp) {

        return rules().matches(clientIp);
    }

    /**
     * 유효한 차단 규칙 목록을 돌려준다.
     *
     * <p>갱신에 실패하면 직전 목록을 그대로 쓴다. 읽지 못했다고 전부 막으면 조회 실패가 곧 전체
     * 장애가 되고, 비워 버리면 차단이 풀린다. 그래서 직전 상태를 유지한다.</p>
     */
    private static synchronized IpMatcherUtils rules() {

        long now = System.currentTimeMillis();
        if (loadedAt != 0L && now - loadedAt < REFRESH_MILLIS) return denied;
        if (now < retryAt) return denied;

        try {
            RecordSet records = new Retrieve().select("SELECT ip FROM WX.API_IP_BLACK WHERE status='사용'"
                    + " AND (expiresAt IS NULL OR expiresAt>NOW())");
            if (records == null) throw new IllegalStateException("차단 목록 조회 결과가 없습니다.");
            apply(records);
            loadedAt = now;
            retryAt = 0L;
            logger.debug("API 차단 목록 갱신: count={}", deniedCount);
        } catch (RuntimeException failure) {
            retryAt = now + RETRY_MILLIS;
            logger.warn("API 차단 목록 갱신 실패, 직전 목록을 유지한다: count={}, type={}",
                    deniedCount, failure.getClass().getSimpleName());
        }
        return denied;
    }

    /** 조회한 규칙에서 IP·CIDR 모양만 받아들여 판정기를 교체한다. */
    private static void apply(kr.tx24.lib.db.RecordSet records) {

        ArrayList<String> loaded = new ArrayList<String>();
        for (SharedMap<String, Object> row : records.getRows()) {
            String rule = row.getString("ip").strip();
            if (rule.matches(RULE)) loaded.add(rule);
            else if (!rule.isEmpty()) logger.warn("차단 규칙 모양이 올바르지 않아 건너뛴다: ip={}", rule);
        }
        denied = new IpMatcherUtils(loaded);
        deniedCount = loaded.size();
    }
}
