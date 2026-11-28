package kr.tx24.wx.api.ctl;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.utils.INetUtils;
import kr.tx24.wx.ledger.rps.WxWebhookRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller(target = "/api/internal/portal")
public final class PortalHookApi {

    private static final Logger logger = LoggerFactory.getLogger(PortalHookApi.class);

    private final ChannelHandlerContext channel;
    private final INet request;

    /** 포털 재접수 요청의 호출 서버와 전용 키를 검사하기 위한 연결이다. */
    @Autowired
    public PortalHookApi(ChannelHandlerContext channel, INet request) {

        this.channel = channel;
        this.request = request;
    }

    /** /api/internal/portal/webhook-retry 에서 고객사 관리자 본인의 발송만 작업기에 넘긴다. */
    @Route(target = {"/webhook-retry"}, loggable = false)
    public INet retry() {

        String key = WalletConfig.portalRetryKey();
        if (key.length() < 32) return INetUtils.response(request, INetUtils.error("TX503", "포털 연동 설정을 확인하세요."));

        try {
            SharedMap<String, Object> inputMap = INetUtils.receive(channel, request, WalletConfig.portalPeers(), "x-wx-key", key, 8192);

            boolean                   changed  = WxWebhookRps.retryMerchant(inputMap.getString("merchant"), inputMap.getString("actor"),
                    inputMap.getString("hookId"), inputMap.getInt("generation", -1));

            return INetUtils.response(request, SharedMap.<String, Object>of()
                    .add("changed", changed));
        } catch (SecurityException refused) {
            return INetUtils.response(request, INetUtils.error("TX401", "포털 요청 경로와 키를 확인하세요."));
        } catch (Exception failure) {
            logger.info("고객사 웹훅 재전송 처리 실패: type={}", failure.getClass().getSimpleName());
            return INetUtils.response(request, INetUtils.error("TX503", "재전송 접수 결과를 확인해 주세요."));
        }
    }
}
