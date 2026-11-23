package kr.tx24.wx.api.ctl;

import kr.tx24.lib.map.SharedMap;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.netty.NettyUtils;
import kr.tx24.wx.api.svc.WalletFlowSvc;
import kr.tx24.wx.inet.InternalInetUtils;
import org.slf4j.MDC;

/** TX24_HTTP가 변환한 Wallet 요청을 수신한다. 흐름만 나열하고 업무는 svc 계층이 처리한다. */
@Controller(target = "/api/v1/wallet")
public final class MerchantApi {

    private static volatile String allowedPeers;

    private final ChannelHandlerContext channel;
    private final INet request;
    private final WalletFlowSvc flowSvc;

    /** /api/v1/wallet: 게이트웨이 채널과 전문, 업무 흐름을 주입받는다. */
    @Autowired
    public MerchantApi(ChannelHandlerContext channel, INet request, WalletFlowSvc flowSvc) {

        this.channel = channel;
        this.request = request;
        this.flowSvc = flowSvc;
    }

    /** /api/v1/wallet 기동: TX24_HTTP의 허용 소켓 주소를 지정한다. */
    public static void configure(String peers) {

        allowedPeers = peers;
    }

    /** /api/v1/wallet/balance: 인증 키에 연결된 Wallet 잔액을 조회한다. */
    @Route(target = {"/balance"}, loggable = false)
    public INet balance() {

        return submit("balance");
    }

    /** /api/v1/wallet/inquiry: 고객 요청번호와 요청일로 기존 송금 결과를 조회한다. */
    @Route(target = {"/inquiry"}, loggable = false)
    public INet inquiry() {

        return submit("inquiry");
    }

    /** /api/v1/wallet/transfer: 잔액을 차감하고 즉시 기관으로 송금한다. */
    @Route(target = {"/transfer"}, loggable = false)
    public INet transfer() {

        return submit("payout");
    }

    /** /api/v1/wallet/holder: 인증과 예금주 조회 접수 조건을 확인한다. */
    @Route(target = {"/holder"}, loggable = false)
    public INet holder() {

        return submit("holder");
    }

    /** /api/v1/wallet: 지정된 경로를 사용하고 업무 결과를 HTTP 200 JSON으로 보낸다. */
    private INet submit(String action) {

        if (!InternalInetUtils.isAllowed(NettyUtils.getRemoteAddress(channel), allowedPeers)) {
            return reject(403, "허용된 게이트웨이 경로가 필요합니다.");
        }
        INet.INMap data = request.data();
        if (!data.isEquals("method", "POST")) return reject(405, "POST로 요청하세요.");

        // 내부 로그 상관용 번호다. 고객사가 보낸 값은 받지 않는다.
        String trace = MDC.get("traceId");
        if (trace == null || !trace.matches("[A-Za-z0-9]{8}")) trace = IDUtils.getRandom(8);
        byte[] payload = data.getString("payload").getBytes(StandardCharsets.UTF_8);
        SharedMap<String, Object> result = flowSvc.wallet(action, data.getString("authorization"),
                data.getString("x-forwarded-for").strip(), data.getString("contentType"),
                new ByteArrayInputStream(payload), trace);
        return InternalInetUtils.response(request, result);
    }

    /** /api/v1/wallet: 프록시 연결과 메서드 오류는 게이트웨이 HTTP 오류 형식을 사용한다. */
    private INet reject(int status, String message) {

        return new INet().head("result", true).head("responseType", "error").data("response", status + "," + message);
    }
}
