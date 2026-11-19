package kr.tx24.wx.inet;

import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.lang.IpMatcherUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.lib.mapper.JacksonUtils;

/** WX 내부 구간의 단일 INet 송수신·인증·응답 계약이다. HTTP 대체 경로를 제공하지 않는다. */
public final class InternalInetUtils {

    /** 내부 통신은 상태 없는 함수로 재사용한다. */
    private InternalInetUtils() {

    }

    /** 내부 호출: 보호된 목적지로 한 번만 전송하며 시간 초과를 성공 또는 재송신으로 바꾸지 않는다. */
    public static SharedMap<String,Object> call(String host, int port, String target, String keyHeader, String key,
            SharedMap<String,Object> input, String trace, int timeoutMillis, int maximum) throws Exception {

        if (host == null || host.isBlank() || host.contains("://") || port < 1 || port > 65535
                || key == null || key.length() < 32 || maximum < 1 || timeoutMillis < 1 || input == null
                || !(target.startsWith("/api/internal/") || target.startsWith("/internal/") || target.startsWith("/v1/wx/internal/"))) {
            throw new IllegalStateException("내부 INet 연결 설정을 확인하세요.");
        }
        String payload = JacksonUtils.get().toJson(input);
        if (payload.getBytes(StandardCharsets.UTF_8).length > maximum) throw new IllegalArgumentException("내부 요청 크기를 확인하세요.");
        String id = trace != null && trace.matches("[A-Za-z0-9]{8}") ? trace : IDUtils.getRandom(8);

        // 원장 명령은 네트워크 오류 시 자동 재송신하지 않는다. 동일 원요청의 상태·fence로 복구한다.
        var reply = new INet("WX_INTERNAL", target).data("method", "INet").data("payload", payload)
                .data(keyHeader.toLowerCase(Locale.ROOT), key).data("x-trace-id", id).connect(host, port, timeoutMillis);
        if (reply == null || !reply.successful()) throw new java.io.IOException("내부 INet 응답을 확인하지 못했습니다.");
        String json = reply.data().getString("response");
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maximum) throw new java.io.IOException("내부 응답 크기를 확인하세요.");
        SharedMap<String,Object> result = JacksonUtils.get().getMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<SharedMap<String,Object>>() {});
        if (result == null || result.isNotEquals("traceId", id)) throw new java.io.IOException("내부 응답 추적번호를 확인하세요.");
        if (java.util.Set.of("UNAUTHENTICATED", "FORBIDDEN").contains(result.getString("code"))) throw new SecurityException();
        return result;
    }

    /** 내부 수신: 역직렬화 후 실제 피어·전용 키·INet 방식과 JSON 객체를 검증한다. */
    public static SharedMap<String,Object> receive(ChannelHandlerContext channel, INet inet, String allowedIps,
            String keyHeader, String expectedKey, int maximum) throws Exception {

        if (!(channel.channel().remoteAddress() instanceof InetSocketAddress address) || address.getAddress() == null
                || !isAllowed(address.getAddress().getHostAddress(), allowedIps)) throw new SecurityException();
        String supplied = inet.data().getString(keyHeader.toLowerCase(Locale.ROOT));
        if (expectedKey == null || expectedKey.length() < 32 || supplied == null || !MessageDigest.isEqual(
                expectedKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) throw new SecurityException();
        if (!inet.data().isEquals("method", "INet")) throw new IllegalArgumentException("내부 구간은 INet 요청만 허용합니다.");
        String json = inet.data().getString("payload");
        if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > maximum) throw new IllegalArgumentException();
        try {
            SharedMap<String,Object> result = JacksonUtils.get().getMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<SharedMap<String,Object>>() {});
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
            return java.util.Arrays.stream(allowedIps.split(",")).map(String::trim).filter(value -> !value.isBlank())
                    .anyMatch(value -> new IpMatcherUtils(value).matches(address));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 내부 응답: 전송 성공과 업무 결과를 구분하고 동일 8자리 추적번호를 유지한다. */
    public static INet response(INet request, SharedMap<String,Object> body) {

        String trace = org.slf4j.MDC.get("traceId");
        if (trace == null || !trace.matches("[A-Za-z0-9]{8}")) trace = request.data().getString("x-trace-id");
        if (trace == null || !trace.matches("[A-Za-z0-9]{8}")) trace = IDUtils.getRandom(8);
        body.put("traceId", trace);
        return new INet().head("result", true).head("X-Trace-Id", trace)
                .head("contentType", "application/json").head("charset", "UTF-8")
                .data("response", JacksonUtils.get().toJson(body));
    }

    /** 내부 오류는 키·원문·계좌를 포함하지 않는 코드와 공개 문구만 반환한다. */
    public static SharedMap<String,Object> error(String code, String message) {

        return SharedMap.<String,Object>of().add("result", false).add("code", code).add("message", message);
    }
}
