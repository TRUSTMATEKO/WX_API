package kr.tx24.wx.api.svc;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.inet.InternalInetUtils;

public final class PayoutSvc {

    private final SharedMap<String, Object> config;

    /** 시연 지급의 ADM 연결 설정을 확보한다. */
    public PayoutSvc(SharedMap<String, Object> config) {

        this.config = config;
    }

    /** 지급 접수: 고객사 범위는 서버 설정에 고정하고 ADM의 동일 원장으로 전달한다. */
    public SharedMap<String, Object> submit(SharedMap<String, Object> input, String trace) throws Exception {

        var command = SharedMap.<String, Object>of()
                .add("mchtId", config.getString("client.company"))
                .add("requestId", input.getString("requestId"))
                .add("walletId", input.getString("walletId"))
                .add("amount", input.getString("amount"))
                .add("recipient", input.getString("recipient"))
                .add("outcome", input.getString("outcome"));
        return InternalInetUtils.call("127.0.0.1", config.getInt("adm.port"), "/api/internal/wallet/payout",
                "x-wx-key", config.getString("adm.key"), command, trace, 5000, 8192);
    }
}
