package kr.tx24.wx.api;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.IpMatcherUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 고객사 및 본사 API 요청의 접속 주소를 확인한다.
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
     * <p>WX_ADM의 {@code ClientIpUtils.ADMIN_DEFAULT_RANGES}와 같은 기준에 원장 서버 주소를 더했다.</p>
     */
    private static final IpMatcherUtils INTERNAL = new IpMatcherUtils(List.of(
            "127.0.0.1",
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "220.76.95.27/32", "112.175.48.155/32"));

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

        if (rules().matches(clientIp)) {
            logger.info("차단 목록에 등록된 주소의 API 요청을 차단한다: clientIp={}", clientIp);
            throw new WalletException("W002", "Wallet 인증 또는 접속 IP 확인에 실패했습니다.");
        }

        logger.debug("API 차단 목록 확인 완료: clientIp={}", clientIp);
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
            logger.info("API 차단 목록 갱신 실패, 직전 목록을 유지한다: count={}, type={}",
                    deniedCount, failure.getClass().getSimpleName());
        }

        return denied;
    }

    /** 조회한 규칙에서 IP·CIDR 모양만 받아들여 판정기를 교체한다. */
    private static void apply(kr.tx24.lib.db.RecordSet records) {

        ArrayList<String> loadedList = new ArrayList<String>();
        for (SharedMap<String, Object> rowMap : records.getRows()) {
            String rule = rowMap.getString("ip").strip();
            if (rule.matches(RULE)) loadedList.add(rule);
            else if (!rule.isEmpty()) logger.warn("차단 규칙 모양이 올바르지 않아 건너뛴다: ip={}", rule);
        }
        denied = new IpMatcherUtils(loadedList);
        deniedCount = loadedList.size();
    }
}
