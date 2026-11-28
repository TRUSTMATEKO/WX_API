package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.utils.DepositAlert;
import kr.tx24.wx.api.vld.DepositValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 외부 시스템이 보낸 입금 통지를 처리한다.
 *
 * <p>SMS 중계와 기관 가상계좌 통지가 INet 으로 보낸다. 받는 쪽은 WX_API 하나이고 원장도 이
 * 프로세스가 가진다. 그래서 통지 기록과 Wallet 판정, 원장 반영이 한 트랜잭션에서 끝난다.</p>
 *
 * <p>Wallet 판정 기준은 둘이다. SMS 는 등록한 입금자명으로 찾고 후보가 정확히 하나일 때만
 * 반영한다. 가상계좌는 수취 번호가 곧 Wallet 이다. 찾지 못했거나 입금자명 후보가 둘 이상이면
 * 미매칭으로 두고 본사 운영자가 화면에서 Wallet 을 지정한다.</p>
 *
 * <p>접속 주소와 전용 키 확인은 수신 경로가 한다. 통지를 보내는 주체는 본사 화면도 작업기도
 * 아니므로 사설 대역 판정({@code WalletZone.isInternalRequest})을 적용하지 않는다. 허용 주소는
 * {@code notify.allowedIps} 설정 하나로 정한다.</p>
 */
public final class DepositSvc {

    private static final Logger logger = LoggerFactory.getLogger(DepositSvc.class);

    private final DepositValidator validator;
    private final WalletLedgerRps ledger;

    /** 입금 통지 검증과 Wallet 입금 원장을 연결한다. */
    @Autowired
    public DepositSvc(DepositValidator validator, WalletLedgerRps ledger) {

        this.validator = validator;
        this.ledger    = ledger;
    }

    /** /api/internal/deposit/beneficiary: 의뢰 정보를 확인하고 수취 가능 계좌의 고객사명을 회신한다. */
    public SharedMap<String, Object> beneficiary(SharedMap<String, Object> inMap, String trace) {

        if (!inMap.getString("vaAccntNo").matches("[0-9]{8,16}") || inMap.isBlank("depositName")
                || !inMap.getString("senderBankCd").matches("[0-9]{3}")
                || !inMap.getString("expectedAmount").matches("[0-9]{1,13}")) {
            throw new WalletException("W001", "수취조회 의뢰 정보를 확인해 주세요.");
        }
        String name = ledger.beneficiary(inMap.getString("vaAccntNo"));
        logger.info("가상계좌 수취조회: valid={}, senderBankCd={}, trace={}", !name.isBlank(), inMap.getString("senderBankCd"), trace);
        if (name.isBlank()) {
            String code = System.getProperty("hyphen.beneficiary.invalidCode", "9999");
            if (!code.matches("[A-Z0-9]{4}") || code.equals("0000")) throw new IllegalStateException("수취조회 거절 코드 오류");
            return WalletResult.of("W009", "수취 가능한 가상계좌를 찾을 수 없습니다.", trace)
                    .add("hyphenResultCd", code);
        }
        return WalletResult.of("0000", "수취 가능", trace)
                .add("hyphenResultCd", "0000")
                .add("beneficiary", name);
    }

    /** 입금 통지 한 건을 기록하고 Wallet 이 하나로 정해지면 바로 반영한다. */
    public SharedMap<String, Object> receive(SharedMap<String, Object> inMap, String clientIp, String trace) {

        if (inMap.isEquals("messageType", "0400100") || (inMap.isBlank("messageType")
                && inMap.containsKey("parentAccount") && inMap.containsKey("recvAccount") && inMap.containsKey("seqNo"))) {
            SharedMap<String, Object> noticeMap = validator.transferFailure(inMap);
            SharedMap<String, Object> replyMap = ledger.transferFailure(noticeMap, trace);
            if (!replyMap.getBoolean("result")) {
                SharedMap<String, Object> refusedMap = WalletResult.ledger("transfer/failure-notify", replyMap, trace);
                throw new WalletException(refusedMap.getString("resultCd"), refusedMap.getString("advanceMsg"));
            }
            SharedMap<String, Object> dataMap = replyMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
            if (dataMap.getBoolean("changed") && dataMap.isEquals("beforeStatus", "성공")) {
                kr.tx24.wx.api.utils.NotificationUtils.transferFailure(dataMap.getString("trxId"));
            }
            return WalletResult.of("0000", "타행이체불능명세를 반영했습니다.", trace)
                    .add("trxId", dataMap.getString("trxId"))
                    .add("status", dataMap.getString("status"))
                    .add("changed", dataMap.getBoolean("changed"));
        }
        SharedMap<String, Object> inputMap = validator.receive(inMap).add("clientIp", clientIp);
        String                    source   = inputMap.isBlank("vaAccntNo") ? "sms" : "va";

        logger.info("입금 통지 접수: 경로={}, occurredAt={}, amount={}, clientIp={}, trace={}",
                source, inputMap.getString("occurredAt"),
                inputMap.getLong("amount"), clientIp, trace);

        SharedMap<String, Object> replyMap = ledger.receiveDeposit(inputMap, trace);
        if (!replyMap.getBoolean("result")) {
            SharedMap<String, Object> refusedMap = WalletResult.ledger("deposit/receive", replyMap, trace);
            logger.info("입금 통지 거절: resultCd={}, occurredAt={}, trace={}",
                    refusedMap.getString("resultCd"), inputMap.getString("occurredAt"), trace);
            throw new WalletException(refusedMap.getString("resultCd"), refusedMap.getString("advanceMsg"));
        }

        SharedMap<String, Object> dataMap = replyMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (dataMap == null) {
            throw new WalletException("W999", "원장 응답을 확인해 주세요.");
        }

        logger.info("입금 통지 처리: depositId={}, status={}, matched={}, duplicate={}, trace={}",
                dataMap.getString("id"), dataMap.getString("status"), dataMap.getBoolean("matched"),
                dataMap.getBoolean("duplicate"), trace);

        boolean matched   = dataMap.getBoolean("matched");
        boolean duplicate = dataMap.getBoolean("duplicate");

        // 같은 통지가 다시 오면 잔액 대조와 미매칭 알림을 반복하지 않는다.
        if (!duplicate) {
            syncBankBalance(ledger, dataMap, trace);
            if (!matched) {
                DepositAlert.mismatch(dataMap.getString("id"), source,
                        inputMap.getString("occurredAt"), inputMap.getLong("amount"), trace);
            }
        }

        String                    message   = matched ? "입금을 Wallet 에 반영했습니다." : "입금을 미매칭으로 기록했습니다. 본사에서 Wallet 을 지정합니다.";
        SharedMap<String, Object> resultMap = WalletResult.of("0000", message, trace);
        for (String name : new String[] {"id", "status", "mchtId", "walletId", "walletTrxId", "amount",
                "balanceAfter", "matched", "duplicate"}) {
            if (dataMap.containsKey(name)) {
                resultMap.put(name, dataMap.get(name));
            }
        }
        return resultMap;
    }

    /** 입금 통지와 수동 입금 확정 후 모계좌 장부를 기관 잔액과 대조한다. */
    static boolean syncBankBalance(WalletLedgerRps ledger, SharedMap<String, Object> dataMap, String trace) {

        String hqId      = dataMap.getString("hqId");
        String depositId = dataMap.getString("id");
        if (dataMap.isBlank("claimId") || hqId.isBlank() || depositId.isBlank()) {
            return false;
        }

        if ("test".equals(WalletConfig.upstreamEnv()) && !depositId.startsWith("BRT-TEST-")) {
            logger.info("시험 환경이어서 모계좌 잔액을 맞추지 않는다. 더미 잔액은 원장과 무관하다: "
                    + "hqId={}, trace={}", hqId, trace);
            return false;
        }

        SharedMap<String, Object> profileMap = dataMap.getMap("upstream", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (profileMap == null) {
            logger.warn("모계좌 기관 연동 설정이 없어 잔액을 맞추지 못한다: hqId={}, trace={}", hqId, trace);
            return false;
        }

        try {
            // 조회 전의 원장 순번을 보관해 조회 도중 다른 입출금이 반영된 잔액을 덮어쓰지 않는다.
            long revision = ledger.bankBalanceRevision(hqId);
            if (revision < 0) {
                logger.info("모계좌에 진행 중인 거래가 있어 잔액 조정을 보류한다: hqId={}, trace={}", hqId, trace);
                return false;
            }

            SharedMap<String, Object> repliedMap = UpstreamSvc.open(TransferSvc.upstream(dataMap)).balance();
            if (!WalletPolicy.SUCCEEDED.equals(repliedMap.getString("acqResultCd"))
                    || !repliedMap.containsKey("balance")) {
                logger.info("기관 잔액 조회가 성공하지 않아 맞추지 않는다: hqId={}, acqResultCd={}, trace={}",
                        hqId, repliedMap.getString("acqResultCd"), trace);
                return false;
            }

            SharedMap<String, Object> syncedMap = ledger.syncBankBalance(dataMap,
                    repliedMap.getLong("balance"), revision, trace);
            SharedMap<String, Object> resultMap = syncedMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
            if (!syncedMap.getBoolean("result") || resultMap == null) {
                logger.info("모계좌 잔액 동기화가 거절되었다: hqId={}, code={}, trace={}",
                        hqId, syncedMap.getString("code"), trace);
                return false;
            }
            if (resultMap.getBoolean("deferred")) {
                return false;
            }

            logger.info("모계좌 잔액 동기화: hqId={}, 장부={}, 기관={}, 차이={}, 조정={}, trace={}",
                    hqId, resultMap.getLong("before"), resultMap.getLong("reported"), resultMap.getLong("delta"),
                    resultMap.getBoolean("changed"), trace);
            return true;
        } catch (Exception failure) {
            // 입금은 이미 기록했다. 잔액 맞추기 실패로 그것을 되돌리지 않는다.
            logger.info("모계좌 잔액을 맞추지 못했다: hqId={}, type={}, trace={}",
                    hqId, failure.getClass().getSimpleName(), trace);
            return false;
        }
    }

}
