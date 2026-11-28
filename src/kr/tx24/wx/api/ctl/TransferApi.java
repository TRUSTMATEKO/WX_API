package kr.tx24.wx.api.ctl;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.svc.RecoverySvc;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.utils.INetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WX_TASK가 요청하는 송금 통신장애 재조회를 받는다.
 *
 * <p>기관 연동은 WX_API에만 있고 원장은 WX_ADM에만 있다. 재조회는 두 경로를 모두 지나야 하므로
 * 주기 실행은 WX_TASK가 맡고 실제 조회와 확정은 이 경로에서 수행한다.</p>
 */
@Controller(target = "/api/internal/transfer")
public final class TransferApi {

    private static final Logger logger = LoggerFactory.getLogger(TransferApi.class);

    private final ChannelHandlerContext channel;
    private final INet request;
    private final RecoverySvc recoverySvc;

    /** /api/internal/transfer: 실제 채널과 원요청, 재조회 업무를 주입받는다. */
    @Autowired
    public TransferApi(ChannelHandlerContext channel, INet request, RecoverySvc recoverySvc) {

        this.channel = channel;
        this.request = request;
        this.recoverySvc = recoverySvc;
    }

    /** /api/internal/transfer/recovery: 장애 큐의 송금을 기관 원거래 결과로 확정한다. */
    @Route(target = {"/recovery"}, loggable = false)
    public INet recovery() {

        return recover(false);
    }

    /** /api/internal/transfer/balance-recovery: 보류된 모계좌 잔액을 기관에 재조회한다. */
    @Route(target = {"/balance-recovery"}, loggable = false)
    public INet recoverBalance() {

        return recover(true);
    }

    /** 송금 및 잔액 재조회 요청의 내부 인증과 응답 형식을 공유한다. */
    private INet recover(boolean balance) {

        try {
            SharedMap<String, Object> inputMap = INetUtils.receiveInternal(channel, request, 8192);
            String                    trace    = request.data().getString("x-trace-id");
            SharedMap<String, Object> dataMap  = balance ? recoverySvc.recoverBalance(trace)
                    : recoverySvc.recover(inputMap.getInt("limit"), trace);

            return INetUtils.response(request, SharedMap.<String, Object>of()
                    .add("result", true)
                    .add("data", dataMap));
        } catch (SecurityException refused) {
            return INetUtils.response(request,
                    INetUtils.error("TX401", "재조회 요청 경로와 키를 확인하세요."));
        } catch (Exception failure) {
            logger.info("송금 재조회 처리 실패: type={}", failure.getClass().getSimpleName());
            return INetUtils.response(request,
                    INetUtils.error("TX503", "송금 재조회를 처리하지 못했습니다."));
        }
    }
}
