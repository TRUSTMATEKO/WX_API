package kr.tx24.wx.api.ctl;

import java.util.List;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.wx.api.svc.WalletFlowSvc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 로컬 Wallet REST와 INet이 같은 고객사 권한·원장·멱등성 계약을 사용한다. */
public final class WalletRestApi implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WalletRestApi.class);

    private final WalletFlowSvc flowSvc;
    private final byte[] key;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    /** 로컬 REST 전용 키와 포트로 고객사 Wallet 경로를 연다. */
    public WalletRestApi(SharedMap<String, Object> config) throws IOException {

        String secret = config.getString("rest.key");
        int port = config.getInt("rest.port");
        // 전용 키를 반드시 요구한다. 종전에는 키 검사를 건너뛰어 루프백에 닿는 모든 호출이
        // 경로를 쓸 수 있었다.
        if (secret.length() < 32 || config.isEquals("internal_api.key", secret)
                || port < 1 || port > 65535) {
            throw new IllegalArgumentException("REST 전용 키와 포트를 확인하세요.");
        }
        key = secret.getBytes(StandardCharsets.UTF_8);
        flowSvc = WalletFlowSvc.create();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        server.createContext("/api/v1/wallet/", this::handle);
        server.setExecutor(workers);
        server.start();
    }

    /** /api/v1/wallet 요청의 키·JSON·본문 크기를 확인하고 내부 업무 결과를 HTTP로 변환한다. */
    private void handle(HttpExchange http) throws IOException {

        // 추적번호는 로그를 엮는 데만 쓴다. 고객사가 보낸 값을 받지 않고 응답으로도 나가지 않는다.
        String trace = IDUtils.getRandom(8);
        http.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        http.getResponseHeaders().set("Cache-Control", "no-store");
        try (http) {
            // 전용 키는 업무 분기 전에 먼저 확인한다.
            if (!isRestKey(http)) {
                error(http, 401, "인증 키를 확인하세요.", trace);
                return;
            }
            handleWallet(http, trace);
        }
    }

    /** REST 전용 키를 상수 시간 비교로 확인한다. */
    private boolean isRestKey(HttpExchange http) {

        String provided = http.getRequestHeaders().getFirst("X-WX-Key");
        return provided != null && provided.length() >= 32 && provided.length() <= 256
                && MessageDigest.isEqual(key, provided.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 고객사 접속 주소를 결정한다.
     *
     * <p>이 서버는 루프백만 바인딩하므로 TCP 접속 주소는 항상 127.0.0.1 이다. 그 값을 그대로
     * 쓰면 API 키에 등록한 허용 IP 판정이 무력해진다. 그래서 루프백 피어가 보낸 단일
     * {@code X-Forwarded-For} 주소를 고객사 주소로 사용한다. 값이 없거나 둘 이상이면 신뢰하지 않고
     * 접속 주소를 쓴다. 본사 웹의 전달 주소 계약과 같은 기준이다.</p>
     */
    private static String clientIp(HttpExchange http) {

        String peer = http.getRemoteAddress().getAddress().getHostAddress();
        List<String> forwarded = http.getRequestHeaders().getOrDefault("X-Forwarded-For", List.of());
        if (forwarded.size() != 1) return peer;

        String value = forwarded.get(0).strip();
        if (value.isEmpty() || value.indexOf(',') >= 0 || value.length() > 45
                || !value.matches("[0-9A-Fa-f:.]{2,45}")) {
            return peer;
        }
        return value;
    }

    /** 루프백 운영 REST: 게이트웨이와 동일한 Wallet 계약을 적용한다. */
    private void handleWallet(HttpExchange http, String trace) throws IOException {

        String path = http.getRequestURI().getPath();
        if (!Set.of("/api/v1/wallet/balance", "/api/v1/wallet/inquiry", "/api/v1/wallet/transfer",
                "/api/v1/wallet/holder").contains(path)) {
            error(http, 404, "요청 경로를 확인하세요.", trace);
            return;
        }
        if (!"POST".equals(http.getRequestMethod())) {
            http.getResponseHeaders().set("Allow", "POST");
            http.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            byte[] message = "POST로 요청하세요.".getBytes(StandardCharsets.UTF_8);
            http.sendResponseHeaders(405, message.length);
            http.getResponseBody().write(message);
            return;
        }

        Headers headers = http.getRequestHeaders();
        List<String> keys = headers.getOrDefault("Authorization", List.of());
        String key  = keys.size() == 1 ? keys.get(0) : "";
        String pathAction = path.substring(path.lastIndexOf('/') + 1);
        String action     = pathAction.equals("transfer") ? "payout" : pathAction;
        SharedMap<String, Object> result = flowSvc.wallet(action, key, clientIp(http), headers.getFirst("Content-Type"),
                http.getRequestBody(), trace);
        send(http, 200, result);
    }

    /** Wallet REST 오류는 거절 사유만 내보낸다. 추적번호는 로그에만 남긴다. */
    private void error(HttpExchange http, int status, String message, String trace) throws IOException {

        logger.warn("Wallet REST 거절: httpStatus={}, trace={}", status, trace);
        send(http, status, SharedMap.<String, Object>of()
                .add("result", false)
                .add("code", "TX" + status)
                .add("message", message));
    }

    /** Wallet REST 응답 바이트 수와 본문을 함께 확정한다. */
    private void send(HttpExchange http, int status, SharedMap<String, Object> body) throws IOException {

        byte[] bytes = JacksonUtils.get().toJsonBytes(body);
        http.sendResponseHeaders(status, bytes.length);
        http.getResponseBody().write(bytes);
    }

    /** WX_API 종료 시 로컬 REST 포트와 처리 스레드를 반환한다. */
    @Override
    public void close() {

        server.stop(1);
        workers.shutdownNow();
    }
}

