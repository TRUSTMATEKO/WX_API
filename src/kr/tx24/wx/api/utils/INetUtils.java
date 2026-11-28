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
import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.util.NetUtil;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;

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

    /** 본사·작업기·입금 통지 요청은 같은 버전의 허용 IP와 교체 중인 양쪽 키를 대조한다. */
    public static SharedMap<String, Object> receiveInternal(ChannelHandlerContext channel, INet inet, int maximum)
            throws Exception {

        SharedMap<String, Object> configMap = kr.tx24.wx.api.WalletConfig.internal();
        if (!(channel.channel().remoteAddress() instanceof InetSocketAddress peer) || peer.getAddress() == null
                || !isAllowed(peer.getAddress().getHostAddress(), configMap.getString("apiInternalIps"))) {
            throw new SecurityException();
        }

        String supplied = inet.data().getString("x-wx-key");
        String expected = matchingKey(configMap, supplied);
        if (expected.isEmpty() && supplied.length() >= 32 && supplied.length() <= 128) {
            configMap = kr.tx24.wx.api.WalletConfig.reloadInternal();
            expected = matchingKey(configMap, supplied);
        }

        return receive(channel, inet, configMap.getString("apiInternalIps"), "x-wx-key", expected, maximum);
    }

    /** 승인된 IP에서 보낸 키가 현재 키 또는 아직 폐기하지 않은 이전 키와 일치하는지 검사한다. */
    private static String matchingKey(SharedMap<String, Object> configMap, String supplied) {

        for (String field : java.util.List.of("apiKeyEnc", "apiPreviousKeyEnc")) {
            String key = kr.tx24.wx.config.InternalApiConfig.key(configMap, field);
            if (!key.isEmpty() && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                return key;
            }
        }
        return "";
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
            SharedMap<String, Object> resultMap = JacksonUtils.get().getMapper()
                    .readValue(json, new TypeReference<SharedMap<String, Object>>() { });
            if (resultMap == null) throw new IllegalArgumentException();
            return resultMap;
        } catch (Exception ex) {
            throw new IllegalArgumentException("내부 JSON 요청을 확인하세요.");
        }
    }

    /** 수신 전후 공통: 명시된 IP/CIDR만 허용하고 잘못된 설정은 거절한다. */
    public static boolean isAllowed(String address, String allowedIps) {

        if (allowedIps == null || allowedIps.isBlank()) return false;
        try {
            java.net.InetAddress peer = NetUtil.createInetAddressFromIpAddressString(address);
            if (peer == null) return false;

            InetSocketAddress socket = new InetSocketAddress(peer, 0);
            return Arrays.stream(allowedIps.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .anyMatch(value -> {
                        String[] parts = value.split("/", 2);
                        java.net.InetAddress network = NetUtil.createInetAddressFromIpAddressString(parts[0]);
                        if (network == null) return false;
                        int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.getAddress().length * 8;
                        return new IpSubnetFilterRule(network, prefix, IpFilterRuleType.ACCEPT).matches(socket);
                    });
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 내부 응답: 전송 성공과 업무 결과를 구분하고 동일 8자리 추적번호를 유지한다. */
    public static INet response(INet request, SharedMap<String, Object> bodyMap) {

        String trace = request.data().getString("x-trace-id");
        return response(trace.matches(TRACE) ? trace : IDUtils.getRandom(8), bodyMap);
    }

    /** 고객사 업무와 내부 요청에서 확정한 추적번호를 본문과 응답 헤더에 함께 기록한다. */
    public static INet response(String trace, SharedMap<String, Object> bodyMap) {

        bodyMap.put("traceId", trace);

        return new INet()
                .head("result", true)
                .head("X-Trace-Id", trace)
                .head("contentType", "application/json")
                .head("charset", "UTF-8")
                .data("response", JacksonUtils.get().toJson(bodyMap));
    }

    /** 내부 오류는 키·원문·계좌를 포함하지 않는 코드와 공개 문구만 반환한다. */
    public static SharedMap<String, Object> error(String code, String message) {

        return SharedMap.<String, Object>of()
                .add("result", false)
                .add("code", code)
                .add("message", message);
    }
}
