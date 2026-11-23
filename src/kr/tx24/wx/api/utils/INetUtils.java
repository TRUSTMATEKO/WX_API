package kr.tx24.wx.api.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.lang.IpMatcherUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import org.slf4j.MDC;

/**
 * 내부 구간의 단일 INet 수신·인증·응답 계약이다. HTTP 대체 경로를 제공하지 않는다.
 *
 * <p>받는 쪽은 WX_API 하나이므로 이 클래스도 WX_API 에만 있다. 보내는 쪽인 WX_ADM·WX_TASK 는
 * {@code kr.tx24.lib.inter.INet} 을 직접 쓰고 수신 그룹 이름과 목적지를 각자 상수로 둔다.
 * 그쪽 전문의 모양은 여기 {@link #receive}·{@link #response} 가 정하므로, 이 계약을 고치면
 * 두 저장소의 송신부도 같이 고친다.</p>
 *
 * <p>{@code method}·{@code payload}·키 헤더·{@code x-trace-id} 네 항목과 응답의
 * {@code data.response}·{@code traceId} 가 그 계약이다.</p>
 */
public final class INetUtils {

    /** 추적번호 모양. 내부 구간은 8자리 영숫자만 쓴다. */
    private static final String TRACE = "[A-Za-z0-9]{8}";

    /** 내부 통신은 상태 없는 함수로 재사용한다. */
    private INetUtils() {

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
