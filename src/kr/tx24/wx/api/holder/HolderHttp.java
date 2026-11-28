package kr.tx24.wx.api.holder;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.lib.redis.RedisUtils;

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

    static HttpResponse<String> post(URI target, String body) throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(target)
                              .timeout(Duration.ofSeconds(30))
                              .header("Content-Type", "application/json; charset=utf-8")
                              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                              .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
