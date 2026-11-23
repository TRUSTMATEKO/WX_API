package kr.tx24.wx.api.ctl;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.netty.NettyUtils;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.svc.DepositSvc;
import kr.tx24.wx.inet.InternalInetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 외부 시스템이 보내는 입금 통지를 받는다.
 *
 * <p>SMS 중계와 기관 가상계좌 통지가 INet 으로 이 경로에 보낸다. 고객사 API({@code /api/v1/wallet})와
 * 섞이지 않게 경로를 나누고, 내부 경로끼리는 {@code internal_api.key} 와 {@code internal.allowedIps} 를 함께
 * 쓴다.</p>
 *
 * <ul>
 *   <li>{@code /api/internal/deposit/notify} — 입금 통지 수신과 Wallet 자동 매칭</li>
 * </ul>
 *
 * <p>통지가 보내는 값은 네 개다. 가상계좌는 가상계좌번호·입금시간·입금자명·금액, SMS 는
 * 모계좌번호·입금자명·입금시간·금액이다. 모계좌와 Wallet 은 WX 가 그 값으로 찾는다.</p>
 */
@Controller(target = "/api/internal/deposit")
public final class DepositApi {

    private static final Logger logger = LoggerFactory.getLogger(DepositApi.class);

    /** 통지 전문 상한. 원문을 함께 받을 수 있어 다른 내부 경로보다 넉넉하게 둔다. */
    private static final int FRAME = 16384;

    private static volatile SharedMap<String, Object> config;

    private final ChannelHandlerContext channel;
    private final INet request;
    private final DepositSvc depositSvc;

    /** /api/internal/deposit: 실제 채널과 원요청, 입금 통지 업무를 주입받는다. */
    @Autowired
    public DepositApi(ChannelHandlerContext channel, INet request, DepositSvc depositSvc) {

        this.channel = channel;
        this.request = request;
        this.depositSvc = depositSvc;
    }

    /**
     * 기동 시 내부 경로 키를 지정한다. 키가 없으면 경로를 열지 않는다.
     *
     * <p>내부 경로는 WX_ADM·WX_TASK·입금 통지 시스템이 모두 {@code internal_api.key} 하나를 쓴다.
     * 경로만 나눠 두고 키와 허용 주소는 한 집합으로 관리한다.</p>
     */
    public static void configure(SharedMap<String, Object> property) {

        if (property.getString("internal_api.key").length() < 32) {
            logger.warn("내부 경로 키(internal_api.key)가 없어 입금 통지 경로를 열지 않는다.");
            return;
        }
        config = property.copy();
        logger.info("입금 통지 경로를 연다: 허용 주소={}", WalletConfig.internalAllowedIps());
    }

    /** /api/internal/deposit/notify: 입금 통지를 기록하고 Wallet 이 정해지면 바로 반영한다. */
    @Route(target = {"/notify"}, loggable = false)
    public INet depositNotify() {

        String trace = request.data().getString("x-trace-id");
        if (!trace.matches("[A-Za-z0-9]{8}")) trace = IDUtils.getRandom(8);

        if (config == null) {
            return InternalInetUtils.response(request,
                    InternalInetUtils.error("TX503", "입금 통지 경로가 설정되지 않았습니다."));
        }
        try {
            SharedMap<String, Object> input = InternalInetUtils.receive(channel, request,
                    WalletConfig.internalAllowedIps(), "x-wx-key", config.getString("internal_api.key"), FRAME);
            // 접속 주소는 통지가 말하는 값이 아니라 실제 접속 주소를 쓴다. 감사 이력에 이 값이 남는다.
            String clientIp = NettyUtils.getRemoteAddress(channel);
            return InternalInetUtils.response(request, depositSvc.receive(input, clientIp, trace));
        } catch (SecurityException refused) {
            logger.warn("입금 통지의 경로 또는 키를 거절했다: trace={}", trace);
            return InternalInetUtils.response(request, WalletResult.of("W002", "인가되지 않은 접근입니다.", trace));
        } catch (WalletException refused) {
            return InternalInetUtils.response(request, WalletResult.of(refused, trace));
        } catch (Exception failure) {
            logger.warn("입금 통지 처리 실패: type={}, trace={}", failure.getClass().getSimpleName(), trace);
            return InternalInetUtils.response(request, WalletResult.of(failure, trace));
        }
    }
}
