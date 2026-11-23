package kr.tx24.wx.inet;

import com.fasterxml.jackson.core.type.TypeReference;
import io.netty.channel.ChannelHandlerContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.lang.IpMatcherUtils;
import kr.tx24.lib.lb.LoadBalancer;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import org.slf4j.MDC;

/**
 * 내부 구간의 단일 INet 송수신·인증·응답 계약이다. HTTP 대체 경로를 제공하지 않는다.
 *
 * <p>받는 쪽은 WX_API 하나다. 보내기만 하는 WX_ADM·WX_TASK 사본에는 {@code call}만 있고
 * {@code receive}·{@code response}·{@code isAllowed}는 이 사본에만 있다.
 * 세 프로젝트의 {@code call} 본문은 같아야 한다.</p>
 *
 * <p>목적지는 {@code nlb.json} 의 {@link #WX_API} 그룹에서 읽는다. 주소를 설정 세 곳에 적어 두면
 * 장비를 옮길 때 한 곳이 남고, 그 상태가 기동에서 드러나지 않는다. 그래서 {@code api.host}·
 * {@code api.port} 를 두지 않고 TX24_EXT 와 같은 이름 기반 경로를 쓴다.</p>
 */
public final class InternalInetUtils {

    /** 추적번호 모양. 내부 구간은 8자리 영숫자만 쓴다. */
    private static final String TRACE = "[A-Za-z0-9]{8}";

    /** WX_API 수신 그룹 이름. {@code nlb.json} 의 키이며 세 프로젝트가 같은 이름을 쓴다. */
    public static final String WX_API = "WX_API";

    /** 허용하는 내부 목적지 접두. 그 밖의 경로로는 보내지 않는다. */
    private static final Set<String> TARGETS = Set.of("/api/internal/", "/internal/", "/v1/wx/internal/");

    /** 전송은 성공했으나 수신 측이 인가를 거절한 응답 코드. */
    private static final Set<String> REFUSED = Set.of("UNAUTHENTICATED", "FORBIDDEN");

    /** 내부 통신은 상태 없는 함수로 재사용한다. */
    private InternalInetUtils() {

    }

    /**
     * 수신 그룹에 주소가 올라와 있는지 본다.
     *
     * <p>목적지를 설정에 적지 않고 이름으로 보내므로, 이름이 비어 있는 상태를 전송 실패와 구분해야
     * 한다. {@code connectLb} 는 주소를 찾지 못해도 응답 없음과 같은 모양으로 돌려주고, 그러면
     * 호출자가 보냈는지 알 수 없는 것으로 다룬다. 설정이 없는 것은 보내지 않은 것이므로 먼저 막는다.</p>
     */
    public static boolean isRouted(String server) {

        // nlb.json 이 없으면 LoadBalancer 가 기동하지 않고 목록 자체가 비어 있다. isEnabled 를 먼저 본다.
        if (server == null || server.isBlank() || !LoadBalancer.isEnabled()) return false;
        List<String> servers = LoadBalancer.getServerList().get(server);
        return servers != null && !servers.isEmpty();
    }

    /** 내부 호출: 보호된 목적지로 한 번만 전송하며 시간 초과를 성공 또는 재송신으로 바꾸지 않는다. */
    public static SharedMap<String, Object> call(String server, String target, String keyHeader, String key,
            SharedMap<String, Object> input, String trace, int timeoutMillis, int maximum) throws Exception {

        if (!isRouted(server)
                || key == null || key.length() < 32
                || maximum < 1 || timeoutMillis < 1 || input == null
                || TARGETS.stream().noneMatch(target::startsWith)) {
            throw new IllegalStateException("내부 INet 연결 설정을 확인하세요.");
        }

        String payload = JacksonUtils.get().toJson(input);
        if (payload.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException("내부 요청 크기를 확인하세요.");
        }
        String id = trace != null && trace.matches(TRACE) ? trace : IDUtils.getRandom(8);

        // 원장 명령은 네트워크 오류 시 자동 재송신하지 않는다. 동일 원요청의 상태·fence로 복구한다.
        // retry(0) 이 그 계약이다. connectLb 는 기본값이 1 이라 접속 시간 초과 때 한 번 더 보낸다.
        INet.INMessage reply = new INet("WX_INTERNAL", target)
                .retry(0)
                .data("method", "INet")
                .data("payload", payload)
                .data(keyHeader.toLowerCase(Locale.ROOT), key)
                .data("x-trace-id", id)
                .connectLb(server, timeoutMillis);
        if (reply == null || !reply.successful()) {
            throw new IOException("내부 INet 응답을 확인하지 못했습니다.");
        }

        String json = reply.data().getString("response");
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IOException("내부 응답 크기를 확인하세요.");
        }
        SharedMap<String, Object> result = JacksonUtils.get().getMapper()
                .readValue(json, new TypeReference<SharedMap<String, Object>>() { });
        if (result == null || result.isNotEquals("traceId", id)) {
            throw new IOException("내부 응답 추적번호를 확인하세요.");
        }
        if (REFUSED.contains(result.getString("code"))) {
            throw new SecurityException();
        }
        return result;
    }

    /** 내부 수신: 역직렬화 후 실제 피어·전용 키·INet 방식과 JSON 객체를 검증한다. */
    public static SharedMap<String, Object> receive(ChannelHandlerContext channel, INet inet, String allowedIps,
            String keyHeader, String expectedKey, int maximum) throws Exception {

        if (!(channel.channel().remoteAddress() instanceof InetSocketAddress address)
                || address.getAddress() == null
                || !isAllowed(address.getAddress().getHostAddress(), allowedIps)) {
            throw new SecurityException();
        }

        String supplied = inet.data().getString(keyHeader.toLowerCase(Locale.ROOT));
        if (expectedKey == null || expectedKey.length() < 32 || supplied == null
                || !MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException();
        }
        if (!inet.data().isEquals("method", "INet")) {
            throw new IllegalArgumentException("내부 구간은 INet 요청만 허용합니다.");
        }

        String json = inet.data().getString("payload");
        if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException();
        }
        try {
            SharedMap<String, Object> result = JacksonUtils.get().getMapper()
                    .readValue(json, new TypeReference<SharedMap<String, Object>>() { });
            if (result == null) throw new IllegalArgumentException();
            return result;
        } catch (Exception ex) {
            throw new IllegalArgumentException("내부 JSON 요청을 확인하세요.");
        }
    }

    /** 수신 전후 공통: 명시된 IP/CIDR만 허용하고 잘못된 설정은 거절한다. */
    public static boolean isAllowed(String address, String allowedIps) {

        if (allowedIps == null || allowedIps.isBlank()) return false;
        try {
            return Arrays.stream(allowedIps.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .anyMatch(value -> new IpMatcherUtils(value).matches(address));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 내부 응답: 전송 성공과 업무 결과를 구분하고 동일 8자리 추적번호를 유지한다. */
    public static INet response(INet request, SharedMap<String, Object> body) {

        String trace = MDC.get("traceId");
        if (trace == null || !trace.matches(TRACE)) trace = request.data().getString("x-trace-id");
        if (trace == null || !trace.matches(TRACE)) trace = IDUtils.getRandom(8);

        body.put("traceId", trace);
        return new INet()
                .head("result", true)
                .head("X-Trace-Id", trace)
                .head("contentType", "application/json")
                .head("charset", "UTF-8")
                .data("response", JacksonUtils.get().toJson(body));
    }

    /** 내부 오류는 키·원문·계좌를 포함하지 않는 코드와 공개 문구만 반환한다. */
    public static SharedMap<String, Object> error(String code, String message) {

        return SharedMap.<String, Object>of()
                .add("result", false)
                .add("code", code)
                .add("message", message);
    }
}
