package kr.tx24.wx.api.ctl;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.netty.NettyUtils;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.svc.HqWalletSvc;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.utils.INetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 본사 조작으로 Wallet 사이의 금액을 옮긴다.
 *
 * <p>OPL_API가 고객사 API({@code /v1/wallet})와 본사 내부 업무({@code /core/wallet})를 서로 다른
 * URI로 분리한 것과 같다. 이 경로는 고객사 키로 인증하지 않고 본사 전용 키와 내부망 주소로만 받는다.
 * 고객사 API 경로({@code /api/v1/wallet})와 섞이지 않도록 URI 자체를 분리했다.</p>
 *
 * <ul>
 *   <li>{@code /api/internal/wallet/collect} — 고객사 Wallet 차감, 본사 Wallet 입금</li>
 *   <li>{@code /api/internal/wallet/payout} — 본사 Wallet 차감, 고객사 Wallet 입금</li>
 * </ul>
 *
 * <p>접두가 OPL의 {@code /core/}가 아닌 이유는 WX의 내부 구간 계약이다.
 * {@code INetUtils.call}은 {@code /api/internal/}로 시작하는 목적지만 허용한다.</p>
 *
 * <p>실제 은행 자금 이동이 없으므로 모계좌 잔액은 바뀌지 않는다. 기관 전송도 없다.</p>
 */
@Controller(target = "/api/internal/wallet")
public final class HqWalletApi {

    private static final Logger logger = LoggerFactory.getLogger(HqWalletApi.class);

    private final ChannelHandlerContext channel;
    private final INet request;
    private final HqWalletSvc hqWalletSvc;

    /** /api/internal/wallet: 실제 채널과 원요청, 이전 업무를 주입받는다. */
    @Autowired
    public HqWalletApi(ChannelHandlerContext channel, INet request, HqWalletSvc hqWalletSvc) {

        this.channel = channel;
        this.request = request;
        this.hqWalletSvc = hqWalletSvc;
    }

    /** /api/internal/wallet/collect: 고객사 Wallet에서 본사 Wallet으로 옮긴다. */
    @Route(target = {"/collect"}, loggable = false)
    public INet hqCollect() {

        return submit("collect");
    }

    /** /api/internal/wallet/payout: 본사 Wallet에서 고객사 Wallet으로 옮긴다. */
    @Route(target = {"/payout"}, loggable = false)
    public INet hqPayout() {

        return submit("payout");
    }

    /**
     * 이전 공통: 전용 키와 접속 경로를 확인하고 업무 결과를 같은 응답 프레임으로 돌려준다.
     *
     * <p>흐름만 나열한다. 요청값 검증과 업무 판단은 vld·svc 계층이 한다.</p>
     */
    private INet submit(String direction) {

        String trace = request.data().getString("x-trace-id");
        if (!trace.matches("[A-Za-z0-9]{8}")) trace = IDUtils.getRandom(8);

        if (!WalletConfig.internalReady()) {
            return INetUtils.response(request,
                    INetUtils.error("TX503", "Wallet 이전 경로가 설정되지 않았습니다."));
        }
        try {
            // 내부 구간 계약: 허용한 내부 주소와 본사 전용 키를 확인한다.
            SharedMap<String, Object> input = INetUtils.receive(channel, request,
                    WalletConfig.internalAllowedIps(), "x-wx-key", WalletConfig.internalKey(), 8192);
            // 접속 주소는 요청이 말하는 값이 아니라 실제 접속 주소를 쓴다.
            String clientIp = NettyUtils.getRemoteAddress(channel);
            SharedMap<String, Object> data = "collect".equals(direction)
                    ? hqWalletSvc.collect(input, clientIp, trace)
                    : hqWalletSvc.payout(input, clientIp, trace);
            return INetUtils.response(request, data);
        } catch (SecurityException refused) {
            logger.warn("Wallet 이전 요청의 경로 또는 키를 거절했다: direction={}, trace={}", direction, trace);
            return INetUtils.response(request, WalletResult.of("W002", "인가되지 않은 접근입니다.", trace));
        } catch (WalletException refused) {
            return INetUtils.response(request, WalletResult.of(refused, trace));
        } catch (Exception failure) {
            return INetUtils.response(request, WalletResult.of(failure, trace));
        }
    }
}
