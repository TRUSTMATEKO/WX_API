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
import kr.tx24.wx.api.utils.INetUtils;
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

    /** /api/internal/deposit/notify: 입금 통지를 기록하고 Wallet 이 정해지면 바로 반영한다. */
    @Route(target = {"/notify"}, loggable = false)
    public INet depositNotify() {

        return receive(false);
    }

    /** /api/internal/deposit/beneficiary: HYPHEN 입금 전 가상계좌 수취인을 확인한다. */
    @Route(target = {"/beneficiary"}, loggable = false)
    public INet beneficiary() {

        return receive(true);
    }

    /** 기관 통지와 수취조회에 같은 내부 인증 및 추적번호 검사를 적용한다. */
    private INet receive(boolean inquiry) {

        String trace = request.data().getString("x-trace-id");
        if (!trace.matches("[A-Za-z0-9]{8}")) trace = IDUtils.getRandom(8);

        try {
            SharedMap<String, Object> inputMap = INetUtils.receiveInternal(channel, request, FRAME);
            // 접속 주소는 통지가 말하는 값이 아니라 실제 접속 주소를 쓴다. 감사 이력에 이 값이 남는다.
            String clientIp = NettyUtils.getRemoteAddress(channel);

            return INetUtils.response(request, inquiry ? depositSvc.beneficiary(inputMap, trace)
                    : depositSvc.receive(inputMap, clientIp, trace));
        } catch (SecurityException refused) {
            logger.info("입금 통지의 경로 또는 키를 거절했다: trace={}", trace);
            return INetUtils.response(request, WalletResult.of("W002", "인가되지 않은 접근입니다.", trace));
        } catch (WalletException refused) {
            return INetUtils.response(request, WalletResult.of(refused, trace));
        } catch (Exception failure) {
            logger.info("입금 통지 처리 실패: type={}, trace={}", failure.getClass().getSimpleName(), trace);
            return INetUtils.response(request, WalletResult.of(failure, trace));
        }
    }
}
