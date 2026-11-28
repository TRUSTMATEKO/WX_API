package kr.tx24.wx.api.holder;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Locale;
import kr.tx24.lib.map.SharedMap;

/** COOCON 6110 전문으로 예금주를 조회한다. */
final class CooconHolder implements HolderProvider {

    private final URI target;
    private final String org;
    private final String key;
    private final String bankCd;
    private final String sequenceKey;

    CooconHolder(boolean test, String org, String key, String bankCd) {

        this.target = URI.create(test ? "https://dev2.coocon.co.kr:8443/sol/gateway/vapg_wapi.jsp"
                : "https://apigw.coocon.co.kr/sol/gateway/vapg_wapi.jsp");
        this.org         = org;
        this.key         = key;
        this.bankCd      = bankCd;
        this.sequenceKey = test ? "WX:HOLDER:COOCON:TEST:SEQ" : "WX:HOLDER:COOCON:PROD:SEQ";
    }

    @Override
    public SharedMap<String, Object> holder(String bank, String account) throws IOException, InterruptedException {

        long     deadline = HolderHttp.deadline();
        JsonNode reply    = inquire(bank, account, deadline);
        if ("XXXX".equals(reply.path("RESP_CD").asText())) reply = inquire(bank, account, deadline);

        String code = reply.path("RESP_CD").asText("");
        return "0000".equals(code) ? HolderHttp.result(code, "정상처리", reply.path("RCV_ACCT_NM").asText(""))
                : HolderHttp.result(code, reply.path("RESP_MSG").asText(""), "");
    }

    private JsonNode inquire(String bank, String account, long deadline) throws IOException, InterruptedException {

        SharedMap<String, Object> requestMap = SharedMap.<String, Object>of()
                .add("SECR_KEY", key)
                .add("KEY", "6110")
                .add("TRT_INST_CD", org)
                .add("BANK_CD", bankCd)
                .add("TRSC_SEQ_NO", String.format(Locale.ROOT, "%012d", HolderHttp.sequence(sequenceKey)))
                .add("RCV_BNK_CD", bank)
                .add("RCV_ACCT_NO", account)
                .add("TRSC_AMT", "1");
        HttpResponse<String> response = HolderHttp.post(target, requestMap.toJson(), deadline);
        JsonNode reply = HolderHttp.json(response.body());
        if (response.statusCode() / 100 != 2 && "0000".equals(reply.path("RESP_CD").asText())) {
            throw new IOException("COOCON 응답 상태 오류");
        }
        return reply;
    }
}
