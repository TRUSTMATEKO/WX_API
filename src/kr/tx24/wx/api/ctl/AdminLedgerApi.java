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
import kr.tx24.wx.api.svc.AdminLedgerSvc;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.utils.INetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 본사 관리자 화면이 요청하는 원장 변경 경로다.
 *
 * <p>원장은 이 프로세스가 가진다. 본사 화면은 권한과 입력만 확인하고 변경은 이 경로로 보낸다.
 * 고객사 키로 인증하지 않고 본사 전용 키와 내부망 주소로만 받는다. 고객사 API 경로
 * ({@code /api/v1/wallet})와 섞이지 않도록 URI 자체를 분리했다.</p>
 *
 * <ul>
 *   <li>{@code /api/internal/ledger/transfer/settle} — 미확정 송금의 운영자 종결</li>
 *   <li>{@code /api/internal/ledger/deposit/assign} — 미매칭 입금의 Wallet 지정</li>
 *   <li>{@code /api/internal/ledger/deposit/manual} — 통지가 오지 않은 입금의 수동 기록</li>
 *   <li>{@code /api/internal/ledger/deposit/assign-hq} — 미매칭 입금의 본사 Wallet 반영</li>
 *   <li>{@code /api/internal/ledger/deposit/reject} — 미매칭 입금의 반려 종결</li>
 *   <li>{@code /api/internal/ledger/invoice/bill} — 대기 월 청구서의 청구 처리</li>
 *   <li>{@code /api/internal/ledger/wallet/hold} — 고객사 Wallet 의 자금 보류 설정·해제</li>
 *   <li>{@code /api/internal/ledger/webhook/retry} — 대기 상태를 제외한 웹훅의 재발송</li>
 * </ul>
 *
 * <p>Wallet 사이의 금액 이전은 {@link HqWalletApi}가 담당한다. 두 경로 모두 {@code internal_api.key}를 쓴다.</p>
 */
@Controller(target = "/api/internal/ledger")
public final class AdminLedgerApi {

    private static final Logger logger = LoggerFactory.getLogger(AdminLedgerApi.class);

    private final ChannelHandlerContext channel;
    private final INet request;
    private final AdminLedgerSvc adminLedgerSvc;
    private final kr.tx24.wx.api.svc.HqWithdrawSvc withdrawals;

    /** /api/internal/ledger: 실제 채널과 원요청, 원장 변경 업무를 주입받는다. */
    @Autowired
    public AdminLedgerApi(ChannelHandlerContext channel, INet request, AdminLedgerSvc adminLedgerSvc,
                          kr.tx24.wx.api.svc.HqWithdrawSvc withdrawals) {

        this.channel = channel;
        this.request = request;
        this.adminLedgerSvc = adminLedgerSvc;
        this.withdrawals = withdrawals;
    }

    /** /api/internal/ledger/hq-withdraw: 지정 계좌 본사 출금을 접수한다. */
    @Route(target = {"/hq-withdraw"}, loggable = false)
    public INet withdraw() {

        return submit("hq-withdraw");
    }

    /** /api/internal/ledger/transfer/settle: 미확정 송금을 운영자 근거로 종결한다. */
    @Route(target = {"/transfer/settle"}, loggable = false)
    public INet transferSettle() {

        return submit("transfer/settle");
    }

    /** /api/internal/ledger/deposit/assign: 미매칭 입금에 고객사 Wallet을 지정한다. */
    @Route(target = {"/deposit/assign"}, loggable = false)
    public INet depositAssign() {

        return submit("deposit/assign");
    }

    /** /api/internal/ledger/deposit/manual: 통지가 오지 않은 입금을 기록하고 Wallet 에 반영한다. */
    @Route(target = {"/deposit/manual"}, loggable = false)
    public INet depositManual() {

        return submit("deposit/manual");
    }

    /** /api/internal/ledger/deposit/assign-hq: 미매칭 입금을 그 모계좌의 본사 Wallet 에 반영한다. */
    @Route(target = {"/deposit/assign-hq"}, loggable = false)
    public INet depositAssignHq() {

        return submit("deposit/assign-hq");
    }

    /** /api/internal/ledger/deposit/reject: 미매칭 입금을 반려로 종결한다. */
    @Route(target = {"/deposit/reject"}, loggable = false)
    public INet depositReject() {

        return submit("deposit/reject");
    }

    /** /api/internal/ledger/invoice/bill: 대기 월 청구서를 청구로 바꾸고 청구일을 기록한다. */
    @Route(target = {"/invoice/bill"}, loggable = false)
    public INet invoiceBill() {

        return submit("invoice/bill");
    }

    /** /api/internal/ledger/wallet/hold: 고객사 Wallet 의 보류 금액을 지정한 값으로 바꾼다. */
    @Route(target = {"/wallet/hold"}, loggable = false)
    public INet walletHold() {

        return submit("wallet/hold");
    }

    /** /api/internal/ledger/webhook/retry: 대기 상태를 제외한 웹훅을 다시 발송 대기로 돌린다. */
    @Route(target = {"/webhook/retry"}, loggable = false)
    public INet webhookRetry() {

        return submit("webhook/retry");
    }

    /**
     * 본사 원장 변경 공통: 전용 키와 접속 경로를 확인하고 결과를 같은 응답 프레임으로 돌려준다.
     *
     * <p>흐름만 나열한다. 요청값 검증과 업무 판단은 vld·svc 계층이 한다.</p>
     */
    private INet submit(String operation) {

        String trace = request.data().getString("x-trace-id");
        if (!trace.matches("[A-Za-z0-9]{8}")) trace = IDUtils.getRandom(8);

        try {
            // 내부 구간 계약: 허용한 내부 주소와 본사 전용 키를 확인한다.
            SharedMap<String, Object> inputMap = INetUtils.receiveInternal(channel, request, 8192);
            // 접속 주소는 요청이 말하는 값이 아니라 실제 접속 주소를 쓴다.
            String                    clientIp = NettyUtils.getRemoteAddress(channel);

            SharedMap<String, Object> dataMap  = switch (operation) {
                case "hq-withdraw" -> withdrawals.submit(inputMap, trace);
                case "transfer/settle" -> adminLedgerSvc.settle(inputMap, clientIp, trace);
                case "deposit/assign"  -> adminLedgerSvc.assignDeposit(inputMap, clientIp, trace);
                case "deposit/manual"  -> adminLedgerSvc.manualDeposit(inputMap, clientIp, trace);
                case "deposit/assign-hq" -> adminLedgerSvc.assignHqDeposit(inputMap, clientIp, trace);
                case "deposit/reject"  -> adminLedgerSvc.rejectDeposit(inputMap, clientIp, trace);
                case "invoice/bill"    -> adminLedgerSvc.billInvoice(inputMap, clientIp, trace);
                case "wallet/hold"     -> adminLedgerSvc.hold(inputMap, clientIp, trace);
                case "webhook/retry"   -> adminLedgerSvc.webhookRetry(inputMap, clientIp, trace);
                default -> throw new WalletException("W001", "요청 경로를 확인해 주세요.");
            };

            return INetUtils.response(request, dataMap);
        } catch (SecurityException refused) {
            logger.info("본사 원장 변경 요청의 경로 또는 키를 거절했다: operation={}, trace={}", operation, trace);
            return INetUtils.response(request, WalletResult.of("W002", "인가되지 않은 접근입니다.", trace));
        } catch (WalletException refused) {
            return INetUtils.response(request, WalletResult.of(refused, trace));
        } catch (Exception failure) {
            return INetUtils.response(request, WalletResult.of(failure, trace));
        }
    }
}
