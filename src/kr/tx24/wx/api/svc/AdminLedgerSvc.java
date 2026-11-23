package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.WalletZone;
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

    @Autowired
    public AdminLedgerSvc(AdminLedgerValidator validator, WalletLedgerRps ledger) {

        this.validator = validator;
        this.ledger = ledger;
    }

    /**
     * 미확정 송금을 운영자 근거로 종결한다.
     *
     * <p>이미 확정된 건은 원장이 상태를 바꾸지 않고 {@code changed=false}로 돌려준다. 그 경우에도
     * 근거는 감사 이력에 남아 누가 무엇을 시도했는지 추적할 수 있다.</p>
     */
    public SharedMap<String, Object> settle(SharedMap<String, Object> in, String clientIp, String trace) {

        SharedMap<String, Object> input = internal("transfer/settle", validator.settle(in), clientIp, trace);
        logger.info("송금 운영자 종결 접수: trxId={}, outcome={}, actor={}, trace={}",
                input.getString("trxId"), input.getString("outcome"), input.getString("actor"), trace);

        SharedMap<String, Object> data = confirmed("transfer/settle", ledger.settle(input, trace), trace);
        logger.info("송금 운영자 종결 완료: trxId={}, status={}, changed={}, trace={}",
                data.getString("trxId"), data.getString("status"), data.getBoolean("changed"), trace);
        return reply("송금 결과를 종결했습니다.", data, trace,
                "trxId", "trackId", "mchtId", "walletId", "status", "changed", "amount", "walletTrxId", "balance");
    }

    /** 미매칭 입금에 고객사 Wallet을 지정해 확정 원장에 입금으로 반영한다. */
    public SharedMap<String, Object> assignDeposit(SharedMap<String, Object> in, String clientIp, String trace) {

        SharedMap<String, Object> input = internal("deposit/assign", validator.assign(in), clientIp, trace);
        logger.info("입금 Wallet 지정 접수: depositId={}, walletId={}, actor={}, trace={}",
                input.getString("depositId"), input.getString("walletId"), input.getString("userId"), trace);

        SharedMap<String, Object> data = confirmed("deposit/assign", ledger.assignDeposit(input, trace), trace);
        logger.info("입금 Wallet 지정 완료: depositId={}, walletTrxId={}, balanceAfter={}, trace={}",
                data.getString("id"), data.getString("walletTrxId"), data.getLong("balanceAfter"), trace);
        return reply("입금을 지정 Wallet에 반영했습니다.", data, trace,
                "id", "mchtId", "walletId", "walletTrxId", "amount", "balanceAfter");
    }

    /** 미매칭 입금을 매칭하지 않고 사유를 남겨 종결한다. */
    public SharedMap<String, Object> rejectDeposit(SharedMap<String, Object> in, String clientIp, String trace) {

        SharedMap<String, Object> input = internal("deposit/reject", validator.reject(in), clientIp, trace);
        logger.info("입금 반려 접수: depositId={}, actor={}, trace={}",
                input.getString("depositId"), input.getString("userId"), trace);

        SharedMap<String, Object> data = confirmed("deposit/reject", ledger.rejectDeposit(input, trace), trace);
        return reply("입금을 반려로 종결했습니다.", data, trace, "id", "status");
    }

    /**
     * Wallet 보류를 지정한 금액으로 바꾼다.
     *
     * <p>장부잔액은 바뀌지 않고 출금가능잔액만 다시 계산된다. 보류가 걸린 Wallet 은 송금 접수가
     * 출금가능잔액 부족으로 거절된다. 입금과 집계는 그대로 돈다.</p>
     */
    public SharedMap<String, Object> hold(SharedMap<String, Object> in, String clientIp, String trace) {

        SharedMap<String, Object> input = internal("wallet/hold", validator.hold(in), clientIp, trace);
        logger.info("Wallet 보류 변경 접수: walletId={}, amount={}, actor={}, trace={}",
                input.getString("walletId"), input.getLong("amount"), input.getString("userId"), trace);

        SharedMap<String, Object> data = confirmed("wallet/hold", ledger.hold(input, trace), trace);
        logger.info("Wallet 보류 변경 완료: walletId={}, {} -> {}, available={}, trace={}",
                data.getString("walletId"), data.getLong("beforeAmt"), data.getLong("afterAmt"),
                data.getLong("availableBalance"), trace);
        return reply(data.getLong("afterAmt") > 0 ? "Wallet 보류를 설정했습니다." : "Wallet 보류를 해제했습니다.",
                data, trace,
                "walletId", "mchtId", "beforeAmt", "afterAmt", "balance", "availableBalance", "changed");
    }

    /** 실패로 확정된 웹훅 발송을 다시 대기로 돌린다. */
    public SharedMap<String, Object> webhookRetry(SharedMap<String, Object> in, String clientIp, String trace) {

        SharedMap<String, Object> input = internal("webhook/retry", validator.webhookRetry(in), clientIp, trace);
        logger.info("웹훅 재발송 접수: hookId={}, actor={}, trace={}",
                input.getString("hookId"), input.getString("userId"), trace);

        SharedMap<String, Object> data = confirmed("webhook/retry", ledger.webhookRetry(input, trace), trace);
        return reply(data.getBoolean("changed") ? "웹훅을 다시 발송 대기로 돌렸습니다."
                : "이미 대기이거나 발송된 건입니다.", data, trace, "id", "changed");
    }

    /**
     * 본사 요청 공통: 내부망에서만 받고 실제 접속 주소를 요청값에 붙인다.
     *
     * <p>요청이 말하는 주소가 아니라 실제 접속 주소를 쓴다. 감사 이력의 접근 IP가 이 값이다.</p>
     */
    private static SharedMap<String, Object> internal(String operation, SharedMap<String, Object> input,
                                                       String clientIp, String trace) {

        if (!WalletZone.isInternalRequest(clientIp)) {
            logger.warn("허용하지 않는 주소에서 원장 변경을 요청했다: operation={}, clientIp={}, trace={}",
                    operation, clientIp, trace);
            throw new WalletException("W002", "인가되지 않은 접근입니다.");
        }
        WalletZone.verify(clientIp);
        return input.add("clientIp", clientIp);
    }

    /** 원장 응답 공통: 거절은 업무 예외로 올리고 성공은 데이터만 꺼낸다. */
    private static SharedMap<String, Object> confirmed(String operation, SharedMap<String, Object> reply,
                                                        String trace) {

        if (!reply.getBoolean("result")) {
            SharedMap<String, Object> refused = WalletResult.ledger(operation, reply, trace);
            logger.warn("원장 변경 거절: operation={}, resultCd={}, trace={}",
                    operation, refused.getString("resultCd"), trace);
            throw new WalletException(refused.getString("resultCd"), refused.getString("advanceMsg"));
        }
        SharedMap<String, Object> data = reply.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (data == null) throw new WalletException("W999", "원장 응답을 확인해 주세요.");
        return data;
    }

    /** 본사 응답 공통: 허용한 원장 필드만 복사한다. 그 밖의 값은 화면으로 보내지 않는다. */
    private static SharedMap<String, Object> reply(String message, SharedMap<String, Object> data, String trace,
                                                    String... names) {

        SharedMap<String, Object> result = WalletResult.of("0000", message, trace);
        for (String name : names) {
            if (data.containsKey(name)) result.put(name, data.get(name));
        }
        return result;
    }
}
