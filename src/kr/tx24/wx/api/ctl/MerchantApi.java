package kr.tx24.wx.api.ctl;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.netty.NettyUtils;
import kr.tx24.wx.api.svc.PayoutSvc;
import kr.tx24.wx.inet.InternalInetUtils;

import java.nio.charset.StandardCharsets;

/** TX24_HTTP가 변환한 Wallet 요청을 수신한다. */
@Controller(target = "/api/v1/wallet")
public final class MerchantApi {

    private static volatile PayoutSvc service;
    private static volatile String allowedPeers;

    private final ChannelHandlerContext channel;
    private final INet request;

    /** /api/v1/wallet 요청의 게이트웨이 채널과 전문을 받는다. */
    @Autowired
    public MerchantApi(ChannelHandlerContext channel, INet request) {

        this.channel = channel;
        this.request = request;
    }

    /** /api/v1/wallet 기동 시 TX24_HTTP의 허용 소켓 주소를 지정한다. */
    public static void configure(SharedMap<String,Object> config, String peers) {

        service = new PayoutSvc(config);
        allowedPeers = peers;
    }

    /** /api/v1/wallet/balance에서 고객사 Wallet 잔액을 조회한다. */
    @Route(target = {"/balance"}, loggable = false)
    public INet balance() {

        return submit("balance");
    }

    /** /api/v1/wallet/inquiry에서 기존 송금 결과를 조회한다. */
    @Route(target = {"/inquiry"}, loggable = false)
    public INet inquiry() {

        return submit("inquiry");
    }

    /** /api/v1/wallet/transfer에서 고객사 송금 접수 조건을 평가한다. */
    @Route(target = {"/transfer"}, loggable = false)
    public INet transfer() {

        return submit("payout");
    }

    /** /api/v1/wallet/holder에서 예금주 조회 접수 조건을 평가한다. */
    @Route(target = {"/holder"}, loggable = false)
    public INet holder() {

        return submit("holder");
    }

    /** TX24_HTTP 전문의 전달 IP와 요청을 검사하고 WX_ADM 고객사 인증에 전달한다. */
    private INet submit(String action) {

        if (!InternalInetUtils.isAllowed(NettyUtils.getRemoteAddress(channel),allowedPeers))
            return reject(403,"허용된 게이트웨이 경로가 필요합니다.");
        var data = request.data();
        String key = data.getString("x-wx-key");
        if (key.length()<32 || key.length()>256) return reject(401,"인증 키를 확인하세요.");
        if (!data.isEquals("method","POST")) return reject(405,"POST로 요청하세요.");
        String contentType = data.getString("contentType").split(";",2)[0].trim();
        if (!"application/json".equalsIgnoreCase(contentType)) return reject(415,"JSON으로 요청하세요.");
        String forwarded = data.getString("x-forwarded-for").strip();
        if (!PayoutSvc.isClientAddress(forwarded)) return reject(403,"고객사 접속 IP를 확인하세요.");

        byte[] payload = data.getString("payload").getBytes(StandardCharsets.UTF_8);
        if (payload.length>8192) return reject(413,"본문 크기를 확인하세요.");
        SharedMap<String,Object> input;
        try {
            input = PayoutSvc.readPayload(action,payload,true);
        } catch (Exception invalid) {
            return reject(400,"요청 JSON의 필드와 형식을 확인하세요.");
        }
        try {
            var result = service.merchant(action,input,key,forwarded,data.getString("x-trace-id"));
            if (!result.getBoolean("result")) return reject(PayoutSvc.httpStatus(result),result.getString("message"));
            return InternalInetUtils.response(request,result);
        } catch (Exception failure) {
            return reject(503,"원장 응답을 확인하세요.");
        }
    }

    /** TX24_HTTP의 오류 응답 계약으로 업무 거절을 실제 HTTP 상태로 전달한다. */
    private INet reject(int status, String message) {

        return new INet().head("result",true).head("responseType","error").data("response",status+","+message);
    }
}
