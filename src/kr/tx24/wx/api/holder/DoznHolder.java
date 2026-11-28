package kr.tx24.wx.api.holder;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import kr.tx24.lib.map.SharedMap;

/** DOZN 인터넷망의 inquireDepositor 일반 조회와 SEED-CBC 계약을 처리한다. */
final class DoznHolder implements HolderProvider {

    private final URI target;
    private final String org;
    private final String key;
    private final String sequenceKey;
    private final DoznSeed cipher;

    DoznHolder(boolean test, String org, String key, DoznSeed cipher) {

        String host = test ? "https://test-gw-firm.dozn.co.kr" : "https://firmapi-pub.dozn.co.kr";
        this.target      = URI.create(host + (cipher == null ? "/api" : "/crypto") + "/rt/v1/inquireDepositor");
        this.org         = org;
        this.key         = key;
        this.sequenceKey = test ? "WX:HOLDER:DOZN:TEST:SEQ" : "WX:HOLDER:DOZN:PROD:SEQ";
        this.cipher      = cipher;
    }

    @Override
    public SharedMap<String, Object> holder(String bank, String account) throws IOException, InterruptedException {

        long     deadline = HolderHttp.deadline();
        JsonNode reply    = inquire(bank, account, deadline);
        if (reply.path("status").asInt() != 200 && "D91".equals(reply.path("error_code").asText())) {
            reply = inquire(bank, account, deadline);
        }

        if (reply.path("status").asInt() == 200) {
            return HolderHttp.result("0000", "정상처리", reply.path("depositor").asText(""));
        }
        String code = reply.path("error_code").asText("");
        if ("0000".equals(code)) throw new IOException("DOZN 오류 응답코드 불일치");
        return HolderHttp.result(code, reply.path("error_message").asText(""), "");
    }

    private JsonNode inquire(String bank, String account, long deadline) throws IOException, InterruptedException {

        SharedMap<String, Object> requestMap = SharedMap.<String, Object>of()
                .add("api_key", key)
                .add("org_code", org)
                .add("telegram_no", HolderHttp.sequence(sequenceKey))
                .add("bank_code", bank)
                .add("account", account)
                .add("check_depositor", "N");
        String body = requestMap.toJson();
        HttpResponse<String> response = HolderHttp.post(target, cipher == null ? body : cipher.encrypt(body), deadline);
        boolean success = response.statusCode() / 100 == 2;
        // DOZN은 암호화 계약에서도 HTTP 오류 본문은 평문 JSON으로 반환한다.
        JsonNode reply = HolderHttp.json(success && cipher != null ? cipher.decrypt(response.body()) : response.body());
        if (!success && reply.path("status").asInt() == 200) throw new IOException("DOZN 응답 상태 오류");
        return reply;
    }
}
