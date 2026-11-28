package kr.tx24.wx.api.holder;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.lib.redis.RedisUtils;
import kr.tx24.wx.api.WalletPolicy;

/** 예금주 조회용 JSON 통신과 기관 전문번호 발급을 처리한다. */
final class HolderHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
                                             .connectTimeout(Duration.ofSeconds(3))
                                             .followRedirects(HttpClient.Redirect.NEVER)
                                             .build();

    private HolderHttp() {

    }

    static long sequence(String key) {

        long sequence = RedisUtils.incr(key);
        if (sequence < 1) throw new IllegalStateException("기관 전문번호를 발급하지 못했습니다.");
        return 1 + (sequence - 1) % 949999;
    }

    static long deadline() {

        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WalletPolicy.HOLDER_TIMEOUT_MILLIS);
    }

    static HttpResponse<String> post(URI target, String body, long deadline) throws IOException, InterruptedException {

        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new HttpTimeoutException("예금주 조회 기관 응답 시간 초과");
        HttpRequest request = HttpRequest.newBuilder(target)
                              .timeout(Duration.ofNanos(remaining))
                              .header("Content-Type", "application/json; charset=utf-8")
                              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                              .build();
        var response = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return response.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            response.cancel(true);
            throw new HttpTimeoutException("예금주 조회 기관 응답 시간 초과");
        } catch (InterruptedException interrupted) {
            response.cancel(true);
            throw interrupted;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("예금주 조회 기관 통신 오류", failure.getCause());
        }
    }

    static JsonNode json(String body) throws IOException {

        JsonNode node = JacksonUtils.get().toJsonNode(body);
        if (node == null || !node.isObject()) throw new IOException("기관 응답 형식 오류");
        return node;
    }

    static SharedMap<String, Object> result(String code, String message, String holder) throws IOException {

        if (!code.matches("[A-Z0-9]{1,24}")) throw new IOException("기관 응답코드 형식 오류");
        return SharedMap.<String, Object>of()
                .add("acqResultCd", code)
                .add("acqResultMsg", message)
                .add("holder", holder);
    }
}
