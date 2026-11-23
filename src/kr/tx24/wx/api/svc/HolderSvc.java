package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.ctx.WalletContext;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 예금주 조회를 처리한다.
 *
 * <p>송금과 흐름이 같다. 원장에 접수하고 기관에 물어 그 응답으로 결과를 확정한다. 다만 자금이
 * 움직이지 않아 잔액·모계좌·원장을 건드리지 않고, 통신장애 재조회도 없다. 기관이 응답하지 않으면
 * 실패로 확정한다. 조회는 다시 물어보면 되고 과금은 성공한 건만 한다.</p>
 *
 * <p>받은 예금주 이름은 고객사 응답에만 담는다. 원장에는 남기지 않는다.</p>
 */
public final class HolderSvc {

    private static final Logger logger = LoggerFactory.getLogger(HolderSvc.class);

    /** 계좌는 있으나 이름이 다른 응답. 기관이 예금주를 돌려주지 않는다. */
    private static final String MISMATCH = "불일치";

    private final WalletLedgerRps ledger;

    @Autowired
    public HolderSvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 예금주 조회: 접수하고 기관에 물어 결과를 확정한다. */
    public SharedMap<String, Object> holder(WalletContext etx) {

        if (!WalletConfig.upstreamReady()) {
            logger.warn("기관 전송 환경이 없어 예금주 조회를 접수하지 않는다: trackId={}, trace={}",
                    etx.getInput().getString("trackId"), etx.getTrace());
            throw new WalletException("W003", "기관 연동 준비 전으로 예금주 조회가 중지되었습니다.");
        }

        SharedMap<String, Object> accepted = accept(etx);
        String trxId = accepted.getString("trxId");
        logger.info("예금주 조회 접수 완료, 기관 전송 시작: trxId={}, bankCd={}, feeAmt={}, trace={}",
                trxId, accepted.getString("bankCd"), accepted.getLong("feeAmt"), etx.getTrace());

        SharedMap<String, Object> replied;
        try {
            replied = new HyphenSvc(TransferSvc.upstream(accepted))
                    .holder(accepted.getString("bankCd"), accepted.getString("accntNo"));
        } catch (Exception failure) {
            // 조회는 자금이 움직이지 않는다. 실패로 확정하고 고객사가 다시 물어보게 한다.
            logger.error("기관 예금주 조회 실패: trxId={}, trace={}", trxId, etx.getTrace(), failure);
            replied = SharedMap.<String, Object>of()
                    .add("acqResultCd", WalletPolicy.NO_RESPONSE)
                    .add("acqResultMsg", "통신장애, 다시 조회해 주세요.");
        }

        String code   = replied.getString("acqResultCd");
        String name   = replied.getString("holder").strip();
        String outcome = WalletPolicy.SUCCEEDED.equals(code)
                ? (name.isEmpty() ? MISMATCH : WalletPolicy.OUTCOME_SUCCESS)
                : WalletPolicy.OUTCOME_FAILED;
        logger.info("기관 예금주 조회 응답: trxId={}, outcome={}, acqResultCd={}, trace={}",
                trxId, outcome, code, etx.getTrace());

        return finish(etx, trxId, outcome, code, replied, accepted, name);
    }

    /** 접수. 원장이 요청을 기록하고 기관 조회 프로필을 돌려준다. */
    private SharedMap<String, Object> accept(WalletContext etx) {

        SharedMap<String, Object> input = etx.getInput();
        input.put("authorizedWalletId", etx.getWalletId());
        input.put("ioId", etx.getIo().getString("ioId"));
        input.put("requestHash", etx.getIo().getString("requestHash"));

        SharedMap<String, Object> reply;
        try {
            reply = ledger.holder(input, etx.getKeyHash(), etx.getClientIp(), etx.getTrace());
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }
        if (!reply.getBoolean("result")) {
            SharedMap<String, Object> refused = WalletResult.ledger("holder", reply, etx.getTrace());
            throw new WalletException(refused.getString("resultCd"), refused.getString("advanceMsg"));
        }
        SharedMap<String, Object> accepted = reply.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (accepted == null || accepted.isBlank("trxId")) {
            throw new WalletException("W999", "예금주 조회 접수 응답을 확인해 주세요.");
        }
        return accepted;
    }

    /** 결과를 확정하고 고객사 응답을 만든다. 예금주 이름은 응답에만 담는다. */
    private SharedMap<String, Object> finish(WalletContext etx, String trxId, String outcome, String code,
                                              SharedMap<String, Object> replied, SharedMap<String, Object> accepted,
                                              String name) {

        try {
            SharedMap<String, Object> settled = ledger.holderConfirm(SharedMap.<String, Object>of()
                    .add("trxId", trxId)
                    .add("outcome", outcome)
                    .add("responseCode", code), etx.getTrace());
            if (!settled.getBoolean("result")) {
                logger.error("예금주 조회 확정이 거절되었다: trxId={}, outcome={}, code={}, trace={}",
                        trxId, outcome, settled.getString("code"), etx.getTrace());
            }
        } catch (Exception failure) {
            // 확정에 실패해도 조회 결과는 돌려준다. 과금은 접수로 남아 운영자가 확인한다.
            logger.error("예금주 조회 확정 호출 실패, 조회 결과는 그대로 반환한다: trxId={}, trace={}",
                    trxId, etx.getTrace(), failure);
        }

        if (!WalletPolicy.OUTCOME_SUCCESS.equals(outcome)) {
            return WalletResult.of(MISMATCH.equals(outcome) ? "W010" : code,
                    replied.getString("acqResultMsg", "예금주를 확인하지 못했습니다."), etx.getTrace())
                    .add("trackId", accepted.getString("trackId"))
                    .add("bankCd", accepted.getString("bankCd"))
                    .add("accntNo", accepted.getString("accntNoMasked"));
        }
        SharedMap<String, Object> reply = WalletResult.of(WalletPolicy.SUCCEEDED, "정상조회", etx.getTrace());
        reply.put("trackId", accepted.getString("trackId"));
        reply.put("bankCd", accepted.getString("bankCd"));
        // 고객사 응답에는 마스킹한 계좌번호만 내보낸다. 평문은 기관 조회에만 쓴다.
        reply.put("accntNo", accepted.getString("accntNoMasked"));
        reply.put("holder", name);
        return reply;
    }
}
