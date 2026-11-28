package kr.tx24.wx.api.ctl;

import kr.tx24.lib.map.SharedMap;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.inet.mapper.Controller;
import kr.tx24.inet.mapper.Route;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.netty.NettyUtils;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.svc.WalletFlowSvc;
import kr.tx24.wx.api.utils.INetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** TX24_HTTP가 변환한 Wallet 요청을 수신한다. 흐름만 나열하고 업무는 svc 계층이 처리한다. */
@Controller(target = "/api/v1/wallet")
public final class MerchantApi {

    private static final Logger logger = LoggerFactory.getLogger(MerchantApi.class);

    private static final ExecutorService IO_WRITER = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "wx-api-io-writer");
            thread.setDaemon(true);
            return thread;
        });

    static {
        // INet Router가 수신 포트를 열기 전에 컨트롤러를 초기화한다.
        try {
            WalletConfig.initialize();
        } catch (ExceptionInInitializerError failure) {
            Throwable cause = failure.getCause();
            String message = cause instanceof IllegalStateException ? cause.getMessage() : "기동 설정 초기화 실패";
            logger.error("WX_API 기동 중단. {}", message);
            System.exit(1);
        }
    }

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

        if (!INetUtils.isAllowed(NettyUtils.getRemoteAddress(channel), WalletConfig.gatewayAllowedIps())) {
            logger.info("Wallet API 접속: action={}, walletId=미확인, remoteIp=미확인, status=403", action);
            return reject(403, "허용된 게이트웨이 경로가 필요합니다.");
        }

        INet.INMap dataMap  = request.data();
        String     remoteIp = dataMap.getString("remoteIp").strip();
        // 단일 IP에 쓰이는 문자만 받아 검증과 로그에 전달한다.
        if (!remoteIp.matches("[0-9A-Fa-f:.]{2,45}")) remoteIp = "";

        if (!dataMap.isEquals("method", "POST")) {
            logger.info("Wallet API 접속: action={}, walletId=미확인, remoteIp={}, status=405",
                    action, remoteIp.isEmpty() ? "미확인" : remoteIp);
            return reject(405, "POST로 요청하세요.");
        }

        // 내부 로그 상관용 번호다. 고객사가 보낸 값은 받지 않는다.
        String mdcTrace = MDC.get("traceId");
        String trace    = mdcTrace != null && mdcTrace.matches("[A-Za-z0-9]{8}") ? mdcTrace : IDUtils.getRandom(8);

        long start = System.nanoTime();
        try {
            byte[]                    payload   = dataMap.getString("payload").getBytes(StandardCharsets.UTF_8);
            SharedMap<String, Object> resultMap = flowSvc.wallet(action, dataMap.getString("authorization"),
                    remoteIp, dataMap.getString("contentType"),
                    new ByteArrayInputStream(payload), trace, this::recordAfterClose);
            return INetUtils.response(trace, resultMap);
        } finally {
            logger.info("Wallet API 소요 시간: action={}, stage=total, elapsedMs={}, trace={}",
                    action, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), trace);
        }
    }

    /** 고객사 응답 소켓이 종료된 뒤 요청 이력을 별도 스레드에 넘긴다. */
    private void recordAfterClose(Runnable task) {

        channel.channel().closeFuture().addListener(future -> IO_WRITER.execute(task));
    }

    /** /api/v1/wallet: 프록시 연결과 메서드 오류는 게이트웨이 HTTP 오류 형식을 사용한다. */
    private INet reject(int status, String message) {

        return new INet().head("result", true).head("responseType", "error").data("response", status + "," + message);
    }
}
