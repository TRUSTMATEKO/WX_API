package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.WalletZone;
import kr.tx24.wx.api.hyphen.HyphenStub;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.vld.AdminLedgerValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 본사 관리자가 요청한 원장 변경을 처리한다.
 *
 * <p>본사 화면은 권한과 입력만 확인하고 원장 변경은 이 프로세스에 요청한다. 화면과 고객사 요청이
 * 각각 다른 경로로 원장을 바꾸면 검증과 로그가 갈라지므로 업무 진입점을 WX_API 한 곳에 둔다.</p>
 *
 * <p>확정 계약은 고객사 경로와 같다. 송금 종결은 기관 응답 확정과 같은
 * {@code WxTransferRps.confirm}을 쓰고, 입금 지정은 고객 Wallet 원장만 기록한다.
 * 운영자 화면이라고 다른 계약을 쓰지 않는다.</p>
 */
public final class AdminLedgerSvc {

    private static final Logger logger = LoggerFactory.getLogger(AdminLedgerSvc.class);

    private final AdminLedgerValidator validator;
    private final WalletLedgerRps ledger;

    /** 본사 원장 변경 요청의 검증과 원장 처리를 연결한다. */
    @Autowired
    public AdminLedgerSvc(AdminLedgerValidator validator, WalletLedgerRps ledger) {

        this.validator = validator;
        this.ledger    = ledger;
    }

    /** 수동·야간 모계좌 잔액조회에서 기관이 확인한 금액만 원장에 반영한다. */
    public SharedMap<String, Object> refreshBalance(SharedMap<String, Object> inMap, String clientIp, String trace)
            throws java.sql.SQLException {

        SharedMap<String, Object> inputMap = validator.balance(inMap);
        verifyInternal("bank/balance", inputMap, clientIp, trace);
        String hqId = inputMap.getString("hqId");
        SharedMap<String, Object> jobMap = ledger.balanceAccount(hqId);
        if (jobMap == null) throw new WalletException("W009", "사용 중인 모계좌를 찾을 수 없습니다.");
        String deferred = "진행 중인 거래 또는 모계좌 변경으로 잔액 갱신을 보류했습니다.";
        if (jobMap.getLong("reservedAmt") != 0) return WalletResult.of("0000", deferred, trace);

        SharedMap<String, Object> responseMap;
        if ("test".equals(WalletConfig.upstreamEnv())) {
            responseMap = HyphenStub.balance(hqId);
        } else {
            if (!jobMap.containsKey("upstream")) throw new WalletException("W003", "모계좌의 기관 설정을 확인해 주세요.");
            responseMap = UpstreamSvc.open(TransferSvc.upstream(jobMap)).balance();
        }
        if (responseMap.isEquals("acqResultCd", WalletPolicy.NO_RESPONSE)) {
            logger.info("기관 통신장애로 잔액 갱신 보류: hqId={}, trace={}", hqId, trace);
            return WalletResult.of("0000", "기관 통신장애로 잔액을 갱신하지 못했습니다.", trace);
        }
        if (!responseMap.isEquals("acqResultCd", WalletPolicy.SUCCEEDED) || !responseMap.containsKey("balance")) {
            throw new WalletException("W003", "기관 잔액조회 응답을 확인해 주세요.");
        }
        SharedMap<String, Object> resultMap = confirmed("bank/balance", ledger.refreshBalance(jobMap,
                responseMap.getLong("balance"), inputMap.getString("userId"), trace), trace);
        if (resultMap.getBoolean("deferred")) return WalletResult.of("0000", deferred, trace);
        return reply(resultMap.getBoolean("changed") ? "모계좌 잔액을 갱신했습니다." : "기관 잔액과 일치합니다.",
                resultMap, trace, "hqId", "reported", "changed");
    }

    /**
     * 미확정 송금을 운영자 근거로 종결한다.
     *
     * <p>이미 확정된 건은 원장이 상태를 바꾸지 않고 {@code changed=false}로 돌려준다. 그 경우에도
     * 근거는 감사 이력에 남아 누가 무엇을 시도했는지 추적할 수 있다.</p>
     */
    public SharedMap<String, Object> settle(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.settle(inMap);
        verifyInternal("transfer/settle", inputMap, clientIp, trace);
        logger.info("송금 운영자 종결 접수: trxId={}, outcome={}, actor={}, trace={}",
                inputMap.getString("trxId"), inputMap.getString("outcome"), inputMap.getString("actor"), trace);

        // 운영자 종결도 기관 응답과 같은 확정 절차를 거쳐 중복 차감이나 원복을 막는다.
        SharedMap<String, Object> replyMap = ledger.settle(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("transfer/settle", replyMap, trace);
        logger.info("송금 운영자 종결 완료: trxId={}, status={}, changed={}, trace={}",
                dataMap.getString("trxId"), dataMap.getString("status"), dataMap.getBoolean("changed"), trace);

        return reply("송금 결과를 종결했습니다.", dataMap, trace,
                "trxId", "trackId", "mchtId", "walletId", "status", "changed", "amount", "walletTrxId", "balance");
    }

    /** /api/internal/ledger/transfer/reverse: 운영자 정정을 반영하고 알린다. */
    public SharedMap<String, Object> reverse(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.reverse(inMap);
        verifyInternal("transfer/reverse", inputMap, clientIp, trace);
        SharedMap<String, Object> dataMap = confirmed("transfer/reverse", ledger.reverse(inputMap, trace), trace);
        kr.tx24.wx.api.utils.NotificationUtils.transferFailure(dataMap.getString("trxId"));
        return reply("송금을 실패로 정정했습니다.", dataMap, trace, "trxId", "status", "changed", "amount");
    }

    /** 미매칭 입금에 고객사 Wallet을 지정해 확정 원장에 입금으로 반영한다. */
    public SharedMap<String, Object> assignDeposit(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.assign(inMap);
        verifyInternal("deposit/assign", inputMap, clientIp, trace);
        logger.info("입금 Wallet 지정 접수: depositId={}, walletId={}, actor={}, trace={}",
                inputMap.getString("depositId"), inputMap.getString("walletId"), inputMap.getString("userId"), trace);

        SharedMap<String, Object> replyMap = ledger.assignDeposit(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("deposit/assign", replyMap, trace);
        logger.info("입금 Wallet 지정 완료: depositId={}, walletTrxId={}, balanceAfter={}, trace={}",
                dataMap.getString("id"), dataMap.getString("walletTrxId"), dataMap.getLong("balanceAfter"), trace);

        return reply("입금을 지정 Wallet에 반영했습니다.", dataMap, trace,
                "id", "mchtId", "walletId", "walletTrxId", "amount", "balanceAfter");
    }

    /**
     * 통지가 오지 않은 입금을 운영자가 직접 기록하고 고른 Wallet 에 반영한다.
     *
     * <p>반영이 끝나면 모계좌 잔액을 기관 값으로 맞춘다. 통지를 놓친 사이 다른 입금의 맞추기가
     * 이 금액을 이미 장부에 넣어 두었을 수 있어, 넣은 직후 실제 값으로 다시 맞춰야 장부가
     * 실제와 벌어지지 않는다. 실패해도 입금 기록은 되돌리지 않는다.</p>
     */
    public SharedMap<String, Object> manualDeposit(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.manual(inMap);
        verifyInternal("deposit/manual", inputMap, clientIp, trace);
        logger.info("수동 입금 접수: walletId={}, sourceType={}, amount={}, occurredAt={}, actor={}, trace={}",
                inputMap.getString("walletId"), inputMap.getString("sourceType"), inputMap.getLong("amount"),
                inputMap.getString("occurredAt"), inputMap.getString("userId"), trace);

        SharedMap<String, Object> replyMap = ledger.manualDeposit(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("deposit/manual", replyMap, trace);
        logger.info("수동 입금 완료: depositId={}, walletTrxId={}, balanceAfter={}, trace={}",
                dataMap.getString("id"), dataMap.getString("walletTrxId"), dataMap.getLong("balanceAfter"), trace);

        // 입금 기록을 확정한 뒤 기관 잔액을 대조한다. 조회 실패로 확정된 입금을 되돌리지 않는다.
        DepositSvc.syncBankBalance(ledger, dataMap, trace);
        return reply("입금을 기록하고 Wallet 에 반영했습니다.", dataMap, trace,
                "id", "status", "mchtId", "walletId", "walletTrxId", "amount", "balanceAfter");
    }

    /**
     * 미매칭 입금을 그 모계좌의 본사 Wallet 에 반영한다.
     *
     * <p>이자·환급처럼 본사가 받은 돈이다. 고객사 잔액은 늘지 않고 고객사 웹훅도 보내지 않는다.
     * 모계좌 잔액은 수신 때 이미 올라 있으므로 이 처리로 바뀌지 않는다.</p>
     */
    public SharedMap<String, Object> assignHqDeposit(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.assignHq(inMap);
        verifyInternal("deposit/assign-hq", inputMap, clientIp, trace);
        logger.info("본사 입금 접수: depositId={}, actor={}, trace={}",
                inputMap.getString("depositId"), inputMap.getString("userId"), trace);

        SharedMap<String, Object> replyMap = ledger.assignHqDeposit(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("deposit/assign-hq", replyMap, trace);
        logger.info("본사 입금 완료: depositId={}, walletId={}, walletTrxId={}, balanceAfter={}, trace={}",
                dataMap.getString("id"), dataMap.getString("walletId"), dataMap.getString("walletTrxId"),
                dataMap.getLong("balanceAfter"), trace);
        return reply("입금을 본사 Wallet에 반영했습니다.", dataMap, trace,
                "id", "owner", "hqId", "walletId", "walletTrxId", "amount", "balanceAfter", "status");
    }

    /** 미매칭 입금을 매칭하지 않고 사유를 남겨 종결한다. */
    public SharedMap<String, Object> rejectDeposit(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.reject(inMap);
        verifyInternal("deposit/reject", inputMap, clientIp, trace);
        logger.info("입금 반려 접수: depositId={}, actor={}, trace={}",
                inputMap.getString("depositId"), inputMap.getString("userId"), trace);

        SharedMap<String, Object> replyMap = ledger.rejectDeposit(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("deposit/reject", replyMap, trace);

        return reply("입금을 반려로 종결했습니다.", dataMap, trace, "id", "status");
    }

    /**
     * Wallet 보류를 지정한 금액으로 바꾼다.
     *
     * <p>장부잔액은 바뀌지 않고 출금가능잔액만 다시 계산된다. 보류가 걸린 Wallet 은 송금 접수가
     * 출금가능잔액 부족으로 거절된다. 입금과 집계는 그대로 돈다.</p>
     */
    public SharedMap<String, Object> hold(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.hold(inMap);
        verifyInternal("wallet/hold", inputMap, clientIp, trace);
        logger.info("Wallet 보류 변경 접수: walletId={}, amount={}, actor={}, trace={}",
                inputMap.getString("walletId"), inputMap.getLong("amount"), inputMap.getString("userId"), trace);

        SharedMap<String, Object> replyMap = ledger.hold(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("wallet/hold", replyMap, trace);
        logger.info("Wallet 보류 변경 완료: walletId={}, {} -> {}, available={}, trace={}",
                dataMap.getString("walletId"), dataMap.getLong("beforeAmt"), dataMap.getLong("afterAmt"),
                dataMap.getLong("availableBalance"), trace);
        return reply(dataMap.getLong("afterAmt") > 0 ? "Wallet 보류를 설정했습니다." : "Wallet 보류를 해제했습니다.",
                dataMap, trace,
                "walletId", "mchtId", "beforeAmt", "afterAmt", "balance", "availableBalance", "changed");
    }

    /**
     * 대기 중인 월 청구서를 청구로 종결한다.
     *
     * <p>금액과 분개는 손대지 않는다. 돈은 일별 마감에서 이미 움직였고 월 청구서는 그 합을 고객사에
     * 알리는 문서다. 이미 청구된 건은 {@code changed=false} 로 돌려준다.</p>
     */
    public SharedMap<String, Object> billInvoice(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.bill(inMap);
        verifyInternal("invoice/bill", inputMap, clientIp, trace);
        logger.info("월 청구 접수: invoiceId={}, billDay={}, actor={}, trace={}",
                inputMap.getString("invoiceId"), inputMap.getInt("billDay"), inputMap.getString("userId"), trace);

        SharedMap<String, Object> replyMap = ledger.billInvoice(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("invoice/bill", replyMap, trace);
        logger.info("월 청구 완료: invoiceId={}, billDay={}, changed={}, trace={}",
                dataMap.getString("id"), dataMap.getInt("billDay"), dataMap.getBoolean("changed"), trace);

        return reply(dataMap.getBoolean("changed") ? "월 청구를 청구로 처리했습니다." : "이미 청구된 청구서입니다.",
                dataMap, trace, "id", "mchtId", "baseMonth", "status", "billDay", "totalAmt", "changed");
    }

    /** 대기 상태를 제외한 웹훅 발송을 다시 대기로 돌린다. */
    public SharedMap<String, Object> webhookRetry(SharedMap<String, Object> inMap, String clientIp, String trace) {

        SharedMap<String, Object> inputMap = validator.webhookRetry(inMap);
        verifyInternal("webhook/retry", inputMap, clientIp, trace);
        logger.info("웹훅 재발송 접수: hookId={}, actor={}, trace={}",
                inputMap.getString("hookId"), inputMap.getString("userId"), trace);

        SharedMap<String, Object> replyMap = ledger.webhookRetry(inputMap, trace);
        SharedMap<String, Object> dataMap  = confirmed("webhook/retry", replyMap, trace);

        return reply(dataMap.getBoolean("changed") ? "웹훅을 다시 발송 대기로 돌렸습니다."
                : "이미 대기 중이거나 발송 이력을 찾을 수 없습니다.", dataMap, trace, "id", "changed");
    }

    /**
     * 본사 요청 공통: 내부망에서만 받고 실제 접속 주소를 요청값에 붙인다.
     *
     * <p>요청이 말하는 주소가 아니라 실제 접속 주소를 쓴다. 감사 이력의 접근 IP가 이 값이다.</p>
     */
    private static void verifyInternal(
            String operation, SharedMap<String, Object> inputMap, String clientIp, String trace) {

        if (!WalletZone.isInternalRequest(clientIp)) {
            logger.info("허용하지 않는 주소에서 원장 변경을 요청했다: operation={}, clientIp={}, trace={}",
                    operation, clientIp, trace);
            throw new WalletException("W002", "인가되지 않은 접근입니다.");
        }
        WalletZone.verify(clientIp);
        inputMap.put("clientIp", clientIp);
    }

    /** 원장 응답 공통: 거절은 업무 예외로 올리고 성공은 데이터만 꺼낸다. */
    private static SharedMap<String, Object> confirmed(String operation, SharedMap<String, Object> replyMap,
                                                        String trace) {

        if (!replyMap.getBoolean("result")) {
            SharedMap<String, Object> refusedMap = WalletResult.ledger(operation, replyMap, trace);
            logger.info("원장 변경 거절: operation={}, resultCd={}, trace={}",
                    operation, refusedMap.getString("resultCd"), trace);
            throw new WalletException(refusedMap.getString("resultCd"), refusedMap.getString("advanceMsg"));
        }

        SharedMap<String, Object> dataMap = replyMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (dataMap == null) {
            throw new WalletException("W999", "원장 응답을 확인해 주세요.");
        }
        return dataMap;
    }

    /** 본사 응답 공통: 허용한 원장 필드만 복사한다. 그 밖의 값은 화면으로 보내지 않는다. */
    private static SharedMap<String, Object> reply(String message, SharedMap<String, Object> dataMap, String trace,
                                                    String... names) {

        SharedMap<String, Object> resultMap = WalletResult.of("0000", message, trace);
        for (String name : names) {
            if (dataMap.containsKey(name)) {
                resultMap.put(name, dataMap.get(name));
            }
        }
        return resultMap;
    }
}
