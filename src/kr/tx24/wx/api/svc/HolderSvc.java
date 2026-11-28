package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.holder.HolderProvider;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.ctx.WalletContext;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 고객사 예금주 조회를 접수하고 기관 조회 결과를 과금 이력에 반영한다. */
public final class HolderSvc {

    private static final Logger logger = LoggerFactory.getLogger(HolderSvc.class);

    /** 계좌는 있으나 이름이 다른 응답. 기관이 예금주를 돌려주지 않는다. */
    private static final String MISMATCH = "불일치";

    private final WalletLedgerRps ledger;

    /** 예금주 조회 접수와 과금 확정에 사용할 원장을 연결한다. */
    @Autowired
    public HolderSvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 예금주 조회: 접수하고 기관에 물어 결과를 확정한다. */
    public SharedMap<String, Object> holder(WalletContext etx) {

        // 인증한 Wallet으로 접수하고 시스템 설정의 기관에 수취 계좌를 보낸다.
        SharedMap<String, Object> inputMap = etx.getInput();
        inputMap.put("walletId", etx.getWalletId());
        inputMap.put("ioId", etx.getIo().getString("ioId"));
        inputMap.put("requestHash", etx.getIo().getString("requestHash"));

        String env = WalletConfig.upstreamEnv();
        HolderProvider provider = "test".equals(env) ? HolderSvc::testHolder
                : HolderProvider.open(ledger.holderConfig(), env);

        SharedMap<String, Object> receiptMap;
        try {
            receiptMap = ledger.holder(inputMap, etx.getKeyHash(), etx.getClientIp(), etx.getTrace());
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }

        if (!receiptMap.getBoolean("result")) {
            SharedMap<String, Object> refusedMap = WalletResult.ledger("holder", receiptMap, etx.getTrace());
            throw new WalletException(refusedMap.getString("resultCd"), refusedMap.getString("advanceMsg"));
        }

        SharedMap<String, Object> acceptedMap = receiptMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (acceptedMap == null || acceptedMap.isBlank("trxId")) {
            throw new WalletException("W999", "예금주 조회 접수 응답을 확인해 주세요.");
        }

        String trxId = acceptedMap.getString("trxId");
        logger.info("예금주 조회 접수 완료: env={}, trxId={}, bankCd={}, feeAmt={}, trace={}",
                env, trxId, acceptedMap.getString("bankCd"), acceptedMap.getLong("feeAmt"), etx.getTrace());

        SharedMap<String, Object> repliedMap;
        try {
            repliedMap = provider.holder(acceptedMap.getString("bankCd"), acceptedMap.getString("accntNo"));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // 조회는 자금이 움직이지 않는다. 실패로 확정하고 고객사가 다시 물어보게 한다.
            logger.debug("기관 예금주 조회 실패: trxId={}, trace={}", trxId, etx.getTrace(), failure);
            repliedMap = SharedMap.<String, Object>of()
                    .add("acqResultCd", WalletPolicy.NO_RESPONSE)
                    .add("acqResultMsg", "통신장애, 다시 조회해 주세요.");
        }

        // 기관 성공 코드라도 이름이 비어 있으면 원장에 불일치로 기록한다.
        String code = repliedMap.getString("acqResultCd");
        String name = repliedMap.getString("holder").strip();
        String outcome;
        if (!WalletPolicy.SUCCEEDED.equals(code)) {
            outcome = WalletPolicy.OUTCOME_FAILED;
        } else if (name.isEmpty()) {
            outcome = MISMATCH;
        } else {
            outcome = WalletPolicy.OUTCOME_SUCCESS;
        }

        logger.info("예금주 조회 결과: env={}, trxId={}, outcome={}, acqResultCd={}, trace={}",
                env, trxId, outcome, code, etx.getTrace());

        return finish(etx, trxId, outcome, code, repliedMap, acceptedMap, name);
    }

    /** /api/v1/wallet/holder 시험 환경은 모든 수취 계좌에 같은 시험 이름을 반환한다. */
    private static SharedMap<String, Object> testHolder(String bank, String account) {

        return SharedMap.<String, Object>of()
                .add("acqResultCd", WalletPolicy.SUCCEEDED)
                .add("holder", "테스트예금주");
    }

    /** 결과를 확정하고 고객사 응답을 만든다. 예금주 이름은 응답에만 담는다. */
    private SharedMap<String, Object> finish(WalletContext etx, String trxId, String outcome, String code,
                                              SharedMap<String, Object> repliedMap, SharedMap<String, Object> acceptedMap,
                                              String name) {

        try {
            // 예금주 이름은 기관 응답에서만 사용하고 원장에는 거래번호와 판정 결과를 기록한다.
            SharedMap<String, Object> commandMap = SharedMap.<String, Object>of()
                    .add("trxId", trxId)
                    .add("outcome", outcome)
                    .add("responseCode", code);
            SharedMap<String, Object> settledMap = ledger.holderConfirm(commandMap, etx.getTrace());
            if (!settledMap.getBoolean("result")) {
                logger.info("예금주 조회 확정이 거절되었다: trxId={}, outcome={}, code={}, trace={}",
                        trxId, outcome, settledMap.getString("code"), etx.getTrace());
            }
        } catch (Exception failure) {
            // 확정에 실패해도 조회 결과는 돌려준다. 과금은 접수로 남아 운영자가 확인한다.
            logger.info("예금주 조회 확정 호출 실패, 조회 결과는 그대로 반환한다: trxId={}, type={}, trace={}",
                    trxId, failure.getClass().getSimpleName(), etx.getTrace());
            logger.debug("예금주 조회 확정 호출 예외 상세: trxId={}, trace={}", trxId, etx.getTrace(), failure);
        }

        SharedMap<String, Object> replyMap;
        if (WalletPolicy.OUTCOME_SUCCESS.equals(outcome)) {
            replyMap = WalletResult.of(WalletPolicy.SUCCEEDED, "정상조회", etx.getTrace());
            replyMap.put("holder", name);
        } else if (MISMATCH.equals(outcome)) {
            replyMap = WalletResult.of("W016", "기관 응답에 예금주명이 없습니다.", etx.getTrace());
        } else {
            String message  = repliedMap.getString("acqResultMsg", "예금주를 확인하지 못했습니다.");
            replyMap = WalletResult.of(code, message, etx.getTrace());
        }
        replyMap.put("trxId", trxId);
        replyMap.put("trackId", acceptedMap.getString("trackId"));
        replyMap.put("bankCd",  acceptedMap.getString("bankCd"));
        replyMap.put("accntNo", acceptedMap.getString("accntNoMasked"));

        return replyMap;
    }
}
