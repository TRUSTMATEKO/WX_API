package kr.tx24.wx.api.rps;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.ledger.LedgerEx;
import kr.tx24.wx.ledger.WxDepositRps;
import kr.tx24.wx.ledger.WxHealthRps;
import kr.tx24.wx.ledger.WxHoldRps;
import kr.tx24.wx.ledger.WxHolderRps;
import kr.tx24.wx.ledger.WxMerchantRps;
import kr.tx24.wx.ledger.WxTransferRps;
import kr.tx24.wx.ledger.WxWalletMoveRps;
import kr.tx24.wx.ledger.WxWebhookRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * WX 원장 쓰기와 조회를 전담한다.
 *
 * <p>WX_API가 원장 소유 프로세스다. 고객사 요청과 본사 요청 모두 이 프로세스의 DB 트랜잭션에서
 * 확정된다. 업무 계층은 이 클래스의 메서드만 쓰고 {@code kr.tx24.wx.ledger}를 직접 부르지 않는다.</p>
 *
 * <p>종전에는 이 클래스가 WX_ADM의 내부 INet 경로를 호출하는 대리자였다. 원장이 본사 관리자 쪽에
 * 있어 고객사 요청 한 건이 두 프로세스를 지나야 했고 호출 방향도 WX_API → WX_ADM 으로 거꾸로였다.
 * 원장을 이 프로세스로 옮겨 호출 방향을 WX_ADM → WX_API 한 방향으로 정리했다.</p>
 *
 * <p>응답 모양은 종전 내부 구간 계약을 유지한다. 성공은 {@code result=true}와 {@code data},
 * 거절은 {@code result=false}와 {@code code}·{@code message}다.
 * {@code WalletResult.ledger}가 이 모양을 읽는다.</p>
 */
public final class WalletLedgerRps {

    private static final Logger logger = LoggerFactory.getLogger(WalletLedgerRps.class);

    /** 인증: 키 해시와 접속 IP로 키에 연결된 Wallet을 확인한다. */
    public SharedMap<String, Object> authorize(String keyHash, String clientIp, String trace) {

        return merchant("authorize", request("authorize", keyHash, clientIp), trace);
    }

    /**
     * 조회 업무: 잔액·거래조회·예금주조회를 원장에 전달한다.
     *
     * <p>업무별로 전달할 필드가 다르다. 허용한 필드만 담고 그 밖의 값은 넘기지 않는다.</p>
     */
    public SharedMap<String, Object> query(String action, SharedMap<String, Object> input, String keyHash,
                                            String clientIp, String trace) {

        SharedMap<String, Object> in = request(action, keyHash, clientIp).add("authorizedWalletId", input.getString("authorizedWalletId"));
        if (action.equals("inquiry")) {
            in.add("trxId", input.getString("trxId"))
                    .add("trackId", input.getString("trackId"))
                    .add("trxDay", input.getString("trxDay"));
        }
        if (action.equals("holder")) {
            in.add("trackId", input.getString("trackId"))
                    .add("bankCd", input.getString("bankCd"))
                    .add("accntNo", input.getString("accntNo"));
        }
        return merchant(action, in, trace);
    }

    /**
     * 송금 접수: 한도·잔액·기관 설정을 확인하고 선차감까지 한 트랜잭션에서 수행한다.
     *
     * <p>수취 계좌번호의 마스킹과 암호화는 원장이 수행한다. 저장 주체가 이 프로세스이므로
     * 평문이 다른 프로세스로 넘어가지 않는다.</p>
     */
    public SharedMap<String, Object> accept(SharedMap<String, Object> input, String keyHash, String clientIp,
                                             String trace) {

        SharedMap<String, Object> in = request("payout", keyHash, clientIp)
                .add("authorizedWalletId", input.getString("authorizedWalletId"))
                .add("trackId", input.getString("trackId"))
                .add("bankCd", input.getString("bankCd"))
                .add("accntNo", input.getString("accntNo"))
                .add("amount", input.getLong("amount"))
                .add("sender", input.getString("sender"))
                .add("holder", input.getString("holder"))
                .add("ioId", input.getString("ioId"))
                .add("requestHash", input.getString("requestHash"));
        return merchant("payout", in, trace);
    }

    /** API 요청 이력: 접수와 완료 시점을 기록한다. 본문 원문과 키는 저장하지 않는다. */
    public void recordIo(SharedMap<String, Object> io, String trace) throws Exception {

        WxMerchantRps.recordIo(io);
    }

    /**
     * 기관 전문 전송 직전에 원거래번호를 원장에 먼저 남긴다.
     *
     * <p>이 번호가 없으면 통신장애를 해소할 수 없다. 기록이 실패하면 전문을 보내지 않도록
     * 예외를 던진다.</p>
     */
    public void recordAcq(String trxId, String acqTrxId, String trace) {

        try {
            WxTransferRps.recordAcq(trxId, acqTrxId);
            logger.info("기관 원거래번호 기록: trxId={}, acqTrxId={}, trace={}", trxId, acqTrxId, trace);
        } catch (Exception failure) {
            throw new IllegalStateException("기관 원거래번호를 기록하지 못해 송금을 중단합니다.", failure);
        }
    }

    /** 송금 확정: 기관 응답으로 원장 상태와 잔액을 확정한다. */
    public SharedMap<String, Object> confirm(SharedMap<String, Object> command, String trace) {

        return ledger("transfer/confirm", trace, () -> WxTransferRps.confirm(command));
    }

    /** 통신장애 재조회 대상을 장애 큐에서 꺼내 온다. */
    public List<SharedMap<String, Object>> pending(int limit, String trace) throws Exception {

        return WxTransferRps.pending(limit);
    }

    /** 확정하지 못한 송금을 재조회 큐로 돌려보낸다. */
    public void park(String trxId, int retry, String reason, String trace) throws Exception {

        WxTransferRps.requeue(trxId, retry, reason);
    }

    /**
     * 예금주 조회 접수: 요청을 기록하고 기관 조회 프로필을 돌려준다.
     *
     * <p>조회 대상 계좌번호의 마스킹과 암호화는 원장이 수행한다.</p>
     */
    public SharedMap<String, Object> holder(SharedMap<String, Object> input, String keyHash, String clientIp,
                                             String trace) {

        SharedMap<String, Object> in = request("holder", keyHash, clientIp)
                .add("authorizedWalletId", input.getString("authorizedWalletId"))
                .add("trackId", input.getString("trackId"))
                .add("bankCd", input.getString("bankCd"))
                .add("accntNo", input.getString("accntNo"))
                .add("ioId", input.getString("ioId"))
                .add("requestHash", input.getString("requestHash"));
        return merchant("holder", in, trace);
    }

    /** 예금주 조회 확정: 기관 응답으로 결과와 과금 대상 여부를 확정한다. */
    public SharedMap<String, Object> holderConfirm(SharedMap<String, Object> command, String trace) {

        return ledger("holder/confirm", trace, () -> WxHolderRps.confirm(command));
    }

    /**
     * Wallet 간 금액 이전을 원장에 분개한다.
     *
     * <p>고객사 Wallet과 본사 Wallet 양쪽 분개와 감사 이력을 한 트랜잭션에 기록한다.
     * 실제 은행 자금 이동이 없어 기관 전송이 없으므로 확정 단계가 따로 없다.</p>
     *
     * @param direction {@code collect}는 고객사에서 본사로, {@code payout}은 본사에서 고객사로
     */
    public SharedMap<String, Object> move(String direction, SharedMap<String, Object> input, String trace) {

        return ledger("move/" + direction, trace, () -> "collect".equals(direction)
                ? WxWalletMoveRps.collect(input) : WxWalletMoveRps.payout(input));
    }

    /**
     * 본사 운영자 종결: 기관 응답 경로와 같은 확정 계약으로 미확정 송금을 끝낸다.
     *
     * <p>운영자 화면이라고 다른 계약을 쓰지 않는다. 성공이면 모계좌 장부잔액을 줄이고
     * 실패면 트리거가 차감한 금액을 원복한다.</p>
     */
    public SharedMap<String, Object> settle(SharedMap<String, Object> command, String trace) {

        return ledger("transfer/settle", trace, () -> WxTransferRps.confirm(command));
    }

    /** 입금 수신: 통지를 기록하고 Wallet이 하나로 정해지면 입금으로 반영한다. */
    public SharedMap<String, Object> receiveDeposit(SharedMap<String, Object> input, String trace) {

        return ledger("deposit/receive", trace, () -> WxDepositRps.receive(input));
    }
    /** 모계좌 잔액 동기화: 기관이 알려준 실제 잔액으로 장부잔액을 맞춘다. */
    public SharedMap<String, Object> syncBankBalance(String hqId, String depositId, long reported, String trace) {

        return ledger("deposit/balance", trace, () -> WxDepositRps.syncBalance(hqId, depositId, reported));
    }

    /** 본사 입금 지정: 미매칭 입금을 지정 Wallet의 확정 원장에 입금으로 반영한다. */
    public SharedMap<String, Object> assignDeposit(SharedMap<String, Object> input, String trace) {

        return ledger("deposit/assign", trace, () -> WxDepositRps.assign(input));
    }

    /** 본사 입금 반려: 매칭하지 않고 사유를 남겨 입금을 종결한다. */
    public SharedMap<String, Object> rejectDeposit(SharedMap<String, Object> input, String trace) {

        return ledger("deposit/reject", trace, () -> WxDepositRps.reject(input));
    }

    /**
     * Wallet 보류 설정과 해제: 지정한 금액으로 보류액을 바꾼다.
     *
     * <p>장부잔액은 바띀지 않는다. 출금가능잔액만 다시 계산된다.</p>
     */
    public SharedMap<String, Object> hold(SharedMap<String, Object> input, String trace) {

        return ledger("wallet/hold", trace, () -> WxHoldRps.apply(input));
    }

    /** 웹훅 재발송: 실패로 확정된 발송을 다시 대기로 돌린다. */
    public SharedMap<String, Object> webhookRetry(SharedMap<String, Object> input, String trace) {

        return ledger("webhook/retry", trace, () -> SharedMap.<String, Object>of()
                .add("id", input.getString("hookId"))
                .add("changed",
                        WxWebhookRps.retry(input.getString("hookId"), input.getString("userId")) == 1));
    }


    /** --check: 거래 실행 없이 WX 연결과 업무 스키마를 확인한다. */
    public void health() throws Exception {

        WxHealthRps.api();
    }

    /** 원장 조회의 공통 입력. 키 해시와 게이트웨이 접속 IP, 업무 구분만 담는다. */
    private static SharedMap<String, Object> request(String action, String keyHash, String clientIp) {

        return SharedMap.<String, Object>of()
                .add("operation", action)
                .add("keyHash", keyHash)
                .add("clientIp", clientIp == null || clientIp.length() > 45 ? "" : clientIp);
    }

    /**
     * 고객사 업무: 인증과 조회·접수를 같은 DB 조회 시점에 처리한다.
     *
     * <p>원장 처리 자체가 실패한 경우는 {@code TX500}으로 올린다. 종전 내부 구간의
     * {@code /api/internal/wallet/merchant} 응답과 같은 코드다.</p>
     */
    private static SharedMap<String, Object> merchant(String operation, SharedMap<String, Object> in,
                                                       String trace) {

        try {
            return ok(WxMerchantRps.query(in));
        } catch (LedgerEx refused) {
            // 거절 사유를 남긴다. 고객사 응답은 일반 문구로 바뀌므로 여기 안 남기면 원인을 찾을 길이 없다.
            logger.warn("원장 조회 거절: operation={}, code={}, reason={}, trace={}",
                    operation, refused.getCode().getCode(), refused.getMessage(), trace);
            return error(refused);
        } catch (Exception failure) {
            logger.warn("원장 조회 실패: operation={}, type={}, trace={}",
                    operation, failure.getClass().getSimpleName(), trace, failure);
            return error("TX500", "원장 응답을 확인해 주세요.");
        }
    }

    /**
     * 원장 확정 업무: 업무 거절과 처리 실패를 종전 내부 구간과 같은 코드로 구분한다.
     *
     * <p>요청 전문과 키는 로그에 남기지 않는다. 오류 유형만 남긴다.</p>
     */
    private static SharedMap<String, Object> ledger(String operation, String trace, Work work) {

        try {
            return ok(work.run());
        } catch (LedgerEx refused) {
            logger.warn("원장 처리 거절: operation={}, code={}, reason={}, trace={}",
                    operation, refused.getCode().getCode(), refused.getMessage(), trace);
            return error(refused);
        } catch (Exception failure) {
            logger.warn("원장 처리 실패: operation={}, type={}, trace={}",
                    operation, failure.getClass().getSimpleName(), trace, failure);
            return error("TX503", "내부 요청을 처리하지 못했습니다.");
        }
    }

    /** 성공 응답. 종전 내부 구간과 같은 result·data 모양을 유지한다. */
    private static SharedMap<String, Object> ok(SharedMap<String, Object> data) {

        return SharedMap.<String, Object>of()
                .add("result", true)
                .add("data", data == null ? SharedMap.<String, Object>of() : data);
    }

    /** 업무 거절. 원장이 정한 코드와 공개 문구만 담는다. */
    private static SharedMap<String, Object> error(LedgerEx refused) {

        return error(refused.getCode().getCode(), refused.getMessage());
    }

    /** 거절 응답. 키·원문·계좌를 포함하지 않는 코드와 공개 문구만 반환한다. */
    private static SharedMap<String, Object> error(String code, String message) {

        return SharedMap.<String, Object>of().add("result", false).add("code", code).add("message", message);
    }

    /** 원장 확정 처리 한 건. 업무 거절은 {@link LedgerEx}로, 그 밖의 실패는 일반 예외로 올린다. */
    private interface Work {

        SharedMap<String, Object> run() throws Exception;
    }

    /** 원장 처리에서 코드를 모르는 실패는 업무 코드를 단정하지 않고 시스템 오류로 올린다. */
    public static WalletException unreachable(Exception failure) {

        return new WalletException("W999", "원장 처리를 확인해 주세요.", failure);
    }
}
