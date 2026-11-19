package kr.tx24.wx.api.ctl;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.svc.PayoutSvc;
import kr.tx24.wx.inet.InternalInetUtils;

@Controller(target = "/api/internal/demo")
public final class PayoutApi {

    private static volatile SharedMap<String, Object> config;
    private static volatile PayoutSvc service;
    private final ChannelHandlerContext channel;
    private final INet request;

    /** /api/internal/demo: 실제 채널과 원요청을 주입한다. */
    @Autowired
    public PayoutApi(ChannelHandlerContext channel, INet request) {

        this.channel = channel;
        this.request = request;
    }

    /** 기동 시 시연 인증 범위와 ADM 연결을 지정한다. */
    public static void configure(SharedMap<String, Object> property) {

        if (property.getString("client.key").length() < 32 || property.getString("adm.key").length() < 32
                || property.isEquals("client.key", property.getString("adm.key"))
                || !java.util.Set.of("M001", "M002").contains(property.getString("client.company"))) {
            throw new IllegalStateException("서로 다른 보호 키와 시연 고객사를 설정하세요.");
        }
        config = property.copy();
        service = new PayoutSvc(config);
    }

    /** /api/internal/demo/payout: 인증된 고객사의 가상 송금을 원장에 접수한다. */
    @Route(target = {"/payout"}, loggable = false)
    public INet payout() {

        return submit("payout");
    }

    /** /api/internal/demo/holder에서 확인한 예금주 조회 결과를 시연한다. */
    @Route(target = {"/holder"}, loggable = false)
    public INet holder() {

        return submit("holder");
    }

    /** 인증된 호출 경로와 요청을 WX 원장에 전달한다. */
    private INet submit(String action) {

        try {
            var input = InternalInetUtils.receive(channel, request, "127.0.0.1",
                    "x-wx-key", config.getString("client.key"), 8192);
            var data = service.submit(action, input, request.data().getString("x-trace-id"));
            return InternalInetUtils.response(request, data);
        } catch (Exception failure) {
            return InternalInetUtils.response(request, InternalInetUtils.error("REJECTED", "접수 응답을 확인하세요."));
        }
    }
}
