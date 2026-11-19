package kr.tx24.wx.api.ctl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.wx.api.svc.PayoutSvc;

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

    private final PayoutSvc service;
    private final byte[] key;
    private final boolean database;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    /** 로컬 REST 전용 키와 포트로 가상 송금·예금주 조회 경로를 연다. */
    public WalletRestApi(SharedMap<String, Object> config) throws IOException {

        String secret = config.getString("rest.key");
        int port = config.getInt("rest.port");
        database = config.isEquals("profile","database");
        if ((!database && (secret.length()<32 || config.isEquals("client.key",secret) || config.isEquals("adm.key",secret)))
                || port < 1 || port > 65535) throw new IllegalArgumentException("REST 전용 키와 포트를 확인하세요.");
        key = secret.getBytes(StandardCharsets.UTF_8);
        service = new PayoutSvc(config);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        server.createContext("/api/v1/wallet/", this::handle);
        server.setExecutor(workers);
        server.start();
    }

    /** /api/v1/wallet 요청의 키·JSON·본문 크기를 확인하고 내부 업무 결과를 HTTP로 변환한다. */
    private void handle(HttpExchange http) throws IOException {

        String trace = http.getRequestHeaders().getFirst("X-Trace-Id");
        if (trace == null || !trace.matches("[A-Za-z0-9]{8}")) trace = IDUtils.getRandom(8);
        http.getResponseHeaders().set("X-Trace-Id", trace);
        http.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        http.getResponseHeaders().set("Cache-Control", "no-store");
        try (http) {
            String provided = http.getRequestHeaders().getFirst("X-WX-Key");
            String path = http.getRequestURI().getPath();
            String type = http.getRequestHeaders().getFirst("Content-Type");
            if (provided == null || provided.length()<32 || provided.length()>256 || (!database && !MessageDigest.isEqual(key, provided.getBytes(StandardCharsets.UTF_8)))) {
                error(http, 401, "인증 키를 확인하세요.", trace);
            } else if (!Set.of("/api/v1/wallet/transfer", "/api/v1/wallet/holder").contains(path)
                    && !(database && Set.of("/api/v1/wallet/balance","/api/v1/wallet/inquiry").contains(path))) {
                error(http, 404, "요청 경로를 확인하세요.", trace);
            } else if (!http.getRequestMethod().equals("POST")) {
                http.getResponseHeaders().set("Allow", "POST");
                error(http, 405, "POST로 요청하세요.", trace);
            } else if (type == null || !"application/json".equalsIgnoreCase(type.split(";")[0].trim())) {
                error(http, 415, "JSON으로 요청하세요.", trace);
            } else {
                byte[] bytes = http.getRequestBody().readNBytes(8193);
                if (bytes.length > 8192) {
                    error(http, 413, "본문 크기를 확인하세요.", trace);
                    return;
                }
                String action = path.substring(path.lastIndexOf('/')+1);
                submit(http, action.equals("transfer") ? "payout" : action, bytes, trace);
            }
        }
    }

    /** 송금·예금주 조회의 문자열 입력과 필수 필드를 확인하여 고객사 고정 경로로 전달한다. */
    private void submit(HttpExchange http, String action, byte[] bytes, String trace) throws IOException {

        SharedMap<String,Object> input;
        try {
            input = PayoutSvc.readPayload(action,bytes,database);
        } catch (IOException | IllegalArgumentException failure) {
            error(http, 400, "요청 JSON의 필드와 형식을 확인하세요.", trace);
            return;
        }
        SharedMap<String, Object> result;
        try {
            if (database) {
                String peer = http.getRemoteAddress().getAddress().getHostAddress();
                result = service.merchant(action,input,http.getRequestHeaders().getFirst("X-WX-Key"),peer,trace);
            } else result = service.submit(action,input,trace);
        } catch (Exception failure) {
            error(http, 503, "원장 응답을 확인하지 못했습니다. 동일 요청번호로 확인하세요.", trace);
            return;
        }
        int status = PayoutSvc.httpStatus(result);
        if (status==503 && !result.isEquals("code","TX503")) {
            error(http,503,"원장 응답을 확인하세요.",trace);
            return;
        }
        result.put("traceId", trace);
        send(http, status, result);
    }

    /** Wallet REST 오류에 같은 추적번호를 부여한다. */
    private void error(HttpExchange http, int status, String message, String trace) throws IOException {

        send(http, status, SharedMap.<String, Object>of()
                .add("result", false)
                .add("code", "TX" + status)
                .add("message", message)
                .add("traceId", trace));
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
