package kr.tx24.wx.api.svc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.util.NetUtil;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.map.SharedMap;
import java.io.IOException;
import java.util.Set;
import kr.tx24.wx.inet.InternalInetUtils;

public final class PayoutSvc {

    private static final ObjectMapper READER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SharedMap<String, Object> config;

    /** 시연 지급의 ADM 연결 설정을 확보한다. */
    public PayoutSvc(SharedMap<String, Object> config) {

        this.config = config;
    }

    /** Wallet REST와 게이트웨이 INet의 공통 JSON 필드와 형식을 확인한다. */
    public static SharedMap<String,Object> readPayload(String action, byte[] bytes, boolean database) throws IOException {

        var fields = action.equals("payout") ? Set.of("requestId","walletId","amount","recipient","outcome")
                : Set.of("requestId","walletId","outcome");
        if (database) fields = switch (action) {
            case "balance" -> Set.of("walletId");
            case "inquiry" -> Set.of("trxId");
            case "holder" -> Set.of("walletId","requestId","bankCode","account");
            default -> Set.of("walletId","requestId","amount","bankCode","account","recipient");
        };
        var payload = READER.readTree(bytes);
        if (payload == null || !payload.isObject() || payload.size() != fields.size()) throw new IllegalArgumentException();
        var input = SharedMap.<String,Object>of();
        for (String field : fields) {
            if (!payload.path(field).isTextual() || payload.get(field).asText().isBlank()) throw new IllegalArgumentException();
            input.put(field,payload.get(field).asText());
        }
        return input;
    }

    /** 게이트웨이가 덮어쓴 헤더는 주소 한 개로 제한한다. */
    public static boolean isClientAddress(String address) {

        return address.matches("[0-9A-Fa-f:.]{2,45}")
                && (NetUtil.isValidIpV4Address(address) || NetUtil.isValidIpV6Address(address));
    }

    /** Wallet 요청의 키 해시와 확인된 고객사 접속 주소를 ADM에 전달한다. */
    public SharedMap<String,Object> merchant(String action, SharedMap<String,Object> input, String key, String clientIp,
            String trace) throws Exception {

        var query = SharedMap.<String,Object>of()
                .add("operation",action)
                .add("keyHash",CommonUtils.hashSHA256(key))
                .add("clientIp",clientIp);
        if (action.equals("inquiry")) query.put("trxId",input.getString("trxId"));
        if (action.equals("balance")) query.put("walletId",input.getString("walletId"));
        return InternalInetUtils.call("127.0.0.1",config.getInt("adm.port"),"/api/internal/wallet/merchant",
                "x-wx-key",config.getString("adm.key"),query,trace,5000,8192);
    }

    /** Wallet REST와 TX24_HTTP 경로가 동일한 거절 상태를 사용한다. */
    public static int httpStatus(SharedMap<String,Object> result) {

        if (result.getBoolean("result")) return 200;
        return switch (result.getString("code")) {
            case "TX400" -> 400;
            case "TX401" -> 401;
            case "TX403" -> 403;
            case "TX404" -> 404;
            case "TX409" -> 409;
            default -> 503;
        };
    }

    /** 지급 접수: 고객사 범위는 서버 설정에 고정하고 ADM의 동일 원장으로 전달한다. */
    public SharedMap<String, Object> submit(String action, SharedMap<String, Object> input, String trace) throws Exception {

        var command = SharedMap.<String, Object>of()
                .add("mchtId", config.getString("client.company"))
                .add("requestId", input.getString("requestId"))
                .add("walletId", input.getString("walletId"))
                .add("outcome", input.getString("outcome"));
        if (action.equals("payout")) {
            command.put("amount", input.getString("amount"));
            command.put("recipient", input.getString("recipient"));
        }
        return InternalInetUtils.call("127.0.0.1", config.getInt("adm.port"), "/api/internal/wallet/" + action,
                "x-wx-key", config.getString("adm.key"), command, trace, 5000, 8192);
    }
}
