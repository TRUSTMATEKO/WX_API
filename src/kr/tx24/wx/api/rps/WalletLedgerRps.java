package kr.tx24.wx.api.rps;

import java.util.concurrent.TimeUnit;

import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisTextUtils;
import kr.tx24.lib.redis.RedisUtils;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.config.InternalApiConfig;
import kr.tx24.wx.ledger.ex.LedgerEx;
import kr.tx24.wx.ledger.rps.WxDepositRps;
import kr.tx24.wx.ledger.rps.WxHealthRps;
import kr.tx24.wx.ledger.rps.WxHoldRps;
import kr.tx24.wx.ledger.rps.WxHolderRps;
import kr.tx24.wx.ledger.rps.WxInvoiceRps;
import kr.tx24.wx.ledger.rps.WxMerchantRps;
import kr.tx24.wx.ledger.rps.WxTransferRps;
import kr.tx24.wx.ledger.rps.WxWalletMoveRps;
import kr.tx24.wx.ledger.rps.WxWebhookRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 고객사와 본사 업무에서 사용하는 WX 원장 조회·변경 및 결과 코드 변환을 담당한다. */
public final class WalletLedgerRps {

    private static final Logger logger = LoggerFactory.getLogger(WalletLedgerRps.class);

    private static final String HOLDER_CONFIG_CACHE_PREFIX = "WX:API:REFERENCE:HOLDER:";
    private static final long   HOLDER_CONFIG_CACHE_SECONDS = 600L;

    private final HqWithdrawRps withdrawals = new HqWithdrawRps();

    /** 지정 계좌 출금의 접수·확정·재조회 저장소. */
    public HqWithdrawRps withdrawals() {

        return withdrawals;
    }

    /** 인증: 키 해시와 접속 IP로 키에 연결된 Wallet을 확인한다. */
    public SharedMap<String, Object> authorize(String keyHash, String clientIp, String trace) {

        SharedMap<String, Object> inMap = request("authorize", keyHash, clientIp);
        return merchant(inMap, trace);
    }

    /**
     * 조회 업무: 잔액·거래조회·예금주조회를 원장에 전달한다.
     *
     * <p>업무별로 전달할 필드가 다르다. 허용한 필드만 담고 그 밖의 값은 넘기지 않는다.</p>
     */
    public SharedMap<String, Object> query(String action, SharedMap<String, Object> inputMap, String keyHash,
                                            String clientIp, String trace) {

        // 인증으로 결정한 Wallet에 업무별 조회 조건만 추가한다.
        SharedMap<String, Object> inMap = request(action, keyHash, clientIp)
                .add("walletId", inputMap.getString("walletId"));
        if (action.equals("inquiry")) {
            inMap
                    .add("trxId", inputMap.getString("trxId"))
                    .add("trackId", inputMap.getString("trackId"))
                    .add("trxDay", inputMap.getString("trxDay"));
        } else if (action.equals("holder")) {
            inMap
                    .add("trackId", inputMap.getString("trackId"))
                    .add("bankCd", inputMap.getString("bankCd"))
                    .add("accntNo", inputMap.getString("accntNo"));
        }
        return merchant(inMap, trace);
    }

    /**
     * 송금 접수: 한도·잔액·기관 설정을 확인하고 선차감까지 한 트랜잭션에서 수행한다.
     *
     * <p>수취 계좌번호의 마스킹과 암호화는 원장이 수행한다. 저장 주체가 이 프로세스이므로
     * 평문이 다른 프로세스로 넘어가지 않는다.</p>
     */
    public SharedMap<String, Object> accept(SharedMap<String, Object> inputMap, String keyHash, String clientIp,
                                             String trace) {

        SharedMap<String, Object> inMap = request("payout", keyHash, clientIp)
                .add("walletId", inputMap.getString("walletId"))
                .add("trackId", inputMap.getString("trackId"))
                .add("bankCd", inputMap.getString("bankCd"))
                .add("accntNo", inputMap.getString("accntNo"))
                .add("amount", inputMap.getLong("amount"))
                .add("sender", inputMap.getString("sender"))
                .add("holder", inputMap.getString("holder"))
                .add("ioId", inputMap.getString("ioId"))
                .add("requestHash", inputMap.getString("requestHash"));
        return merchant(inMap, trace);
    }

    /** 송금 접수 후 기관 전송 전에 중지가 시작되었는지 확인한다. */
    public void requireTransferOpen(String bankCd) {

        WxTransferRps.requireTransferOpen(bankCd);
    }

    /** 고객사 응답이 종료된 뒤 접수와 결과를 이력 한 건으로 기록한다. */
    public void recordIo(SharedMap<String, Object> ioMap, String trace) throws Exception {

        long start = System.nanoTime();
        try {
            WxMerchantRps.recordIo(ioMap);
        } finally {
            logger.info("Wallet API 소요 시간: operation=io, stage=completed, elapsedMs={}, trace={}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), trace);
        }
    }

    /**
     * 기관 전문 전송 직전에 원거래번호를 원장에 먼저 남긴다.
     *
     * <p>이 번호가 없으면 통신장애를 해소할 수 없다. 기록이 실패하면 전문을 보내지 않도록
     * 예외를 던진다.</p>
     */
    public void recordAcq(String trxId, String acqTrxId, String trace) {

        long start = System.nanoTime();
        try {
            WxTransferRps.recordAcq(trxId, acqTrxId);
            logger.info("기관 원거래번호 기록: trxId={}, acqTrxId={}, trace={}", trxId, acqTrxId, trace);
        } catch (Exception failure) {
            throw new IllegalStateException("기관 원거래번호를 기록하지 못해 송금을 중단합니다.", failure);
        } finally {
            logger.info("Wallet API 소요 시간: operation=transfer/recordAcq, stage=ledger, elapsedMs={}, trace={}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), trace);
        }
    }

    /** 본사 송금 상세에서 요청한 성공 거래의 정정을 처리한다. */
    public SharedMap<String, Object> reverse(SharedMap<String, Object> commandMap, String trace) {

        return ledger("transfer/reverse", trace, () -> WxTransferRps.reverse(commandMap));
    }

    /** 내부 통지: 매입사 원거래번호로 송금 실패 정정을 반영한다. */
    public SharedMap<String, Object> transferFailure(SharedMap<String, Object> noticeMap, String trace) {

        return ledger("transfer/failure-notify", trace, () -> WxTransferRps.failureNotice(noticeMap));
    }

    /** 송금 확정: 기관 응답으로 원장 상태와 잔액을 확정한다. */
    public SharedMap<String, Object> confirm(SharedMap<String, Object> commandMap, String trace) {

        return ledger("transfer/confirm", trace, () -> WxTransferRps.confirm(commandMap));
    }

    /** 미전송 만료와 기관 재조회 대상을 원장에서 선점한다. */
    public List<SharedMap<String, Object>> pending(int limit, String trace) throws Exception {

        return WxTransferRps.pending(limit);
    }

    /** 송금 재조회 작업이 기관 전송 전 만료된 고객사 송금을 원복한다. */
    public boolean expireUnsent(String trxId) throws java.sql.SQLException {

        return WxTransferRps.expireUnsent(trxId);
    }

    /** 예금주 조회 기관 설정을 환경과 설정 버전별로 10분간 암호문 그대로 보관한다. */
    public SharedMap<String, Object> holderConfig(String env) {

        String version;
        try {
            version = RedisTextUtils.get(InternalApiConfig.VERSION_KEY);
            if (version != null) {
                SharedMap<String, Object> cachedMap = RedisUtils.fetchRow(HOLDER_CONFIG_CACHE_PREFIX + env + ":" + version,
                        holderConfigQuery().where("apiEnv", env).where("apiConfigVersion", version), HOLDER_CONFIG_CACHE_SECONDS);
                if (cachedMap != null && cachedMap.isEquals("apiEnv", env)
                        && version.equals(cachedMap.getString("apiConfigVersion"))) return cachedMap;
            }
        } catch (RuntimeException unavailable) {
            return holderConfigQuery().select().getRow(0);
        }

        SharedMap<String, Object> configMap = holderConfigQuery().select().getRow(0);
        if (configMap != null && configMap.isEquals("apiEnv", env)) {
            String loadedVersion = configMap.getString("apiConfigVersion");
            try {
                if (version == null) RedisTextUtils.setNx(InternalApiConfig.VERSION_KEY, loadedVersion);
                RedisUtils.setNx(HOLDER_CONFIG_CACHE_PREFIX + env + ":" + loadedVersion, configMap, HOLDER_CONFIG_CACHE_SECONDS);
            } catch (RuntimeException unavailable) {
                // Redis에 저장하지 못해도 이번 요청은 DB에서 읽은 설정으로 처리한다.
            }
        }
        return configMap;
    }

    /** 예금주 조회 기관 설정과 캐시 버전을 같은 행에서 읽는다. */
    private static Retrieve holderConfigQuery() {

        return new Retrieve("WX.CONFIG")
                .columns("apiConfigVersion,apiEnv,holderAcq,holderOrgCode,holderBankCd,holderKey1Enc,"
                        + "holderKey2Enc,holderSeed,holderIvEnc")
                .where("id", 1);
    }

    /**
     * 예금주 조회 접수: 요청을 기록하고 조회 대상 계좌를 돌려준다.
     *
     * <p>조회 대상 계좌번호의 마스킹과 암호화는 원장이 수행한다.</p>
     */
    public SharedMap<String, Object> holder(SharedMap<String, Object> inputMap, String keyHash, String clientIp,
                                             String trace) {

        SharedMap<String, Object> inMap = request("holder", keyHash, clientIp)
                .add("walletId", inputMap.getString("walletId"))
                .add("trackId", inputMap.getString("trackId"))
                .add("bankCd", inputMap.getString("bankCd"))
                .add("accntNo", inputMap.getString("accntNo"))
                .add("ioId", inputMap.getString("ioId"))
                .add("requestHash", inputMap.getString("requestHash"));
        return merchant(inMap, trace);
    }

    /** 예금주 조회 확정: 기관 응답으로 결과와 과금 대상 여부를 확정한다. */
    public SharedMap<String, Object> holderConfirm(SharedMap<String, Object> commandMap, String trace) {

        return ledger("holder/confirm", trace, () -> WxHolderRps.confirm(commandMap));
    }

    /**
     * Wallet 간 금액 이전을 원장에 분개한다.
     *
     * <p>고객사 Wallet과 본사 Wallet 양쪽 분개와 감사 이력을 한 트랜잭션에 기록한다.
     * 실제 은행 자금 이동이 없어 기관 전송이 없으므로 확정 단계가 따로 없다.</p>
     *
     * @param direction {@code collect}는 고객사에서 본사로, {@code payout}은 본사에서 고객사로
     */
    public SharedMap<String, Object> move(String direction, SharedMap<String, Object> inputMap, String trace) {

        if ("collect".equals(direction)) {
            return ledger("move/collect", trace, () -> WxWalletMoveRps.collect(inputMap));
        }
        return ledger("move/" + direction, trace, () -> WxWalletMoveRps.payout(inputMap));
    }

    /**
     * 본사 운영자 종결: 기관 응답 경로와 같은 확정 계약으로 미확정 송금을 끝낸다.
     *
     * <p>운영자 화면이라고 다른 계약을 쓰지 않는다. 성공이면 모계좌 장부잔액을 줄이고
     * 실패면 트리거가 차감한 금액을 원복한다.</p>
     */
    public SharedMap<String, Object> settle(SharedMap<String, Object> commandMap, String trace) {

        return ledger("transfer/settle", trace, () -> WxTransferRps.confirm(commandMap));
    }

    /** 입금 수신: 통지를 기록하고 Wallet이 하나로 정해지면 입금으로 반영한다. */
    public SharedMap<String, Object> receiveDeposit(SharedMap<String, Object> inputMap, String trace) {

        return ledger("deposit/receive", trace, () -> WxDepositRps.receive(inputMap));
    }

    /** 수취조회: 사용 중인 모계좌·가상계좌·고객사 Wallet에 연결된 고객사명을 읽는다. */
    public String beneficiary(String account) {

        Retrieve query = new Retrieve("WX.HQ_VACCNT V")
                .joinInner("WX.HQ_ACCNT H", "H.id=V.hqId")
                .joinInner("WX.WALLET W", "W.id=V.walletId AND W.hqId=V.hqId")
                .joinInner("WX.MCHT M", "M.mchtId=W.mchtId")
                .column("M.name AS beneficiary")
                .where("V.accntNo=?")
                .where("V.status", "사용")
                .where("H.status", "사용")
                .where("W.status", "사용")
                .where("W.ownerType", "MCHT")
                .where("M.status", "사용");
        kr.tx24.lib.db.RecordSet records = query.select(query.build(), List.of(account));
        if (records == null) throw new WalletException("W999", "수취조회 처리 결과를 확인해 주세요.");
        SharedMap<String, Object> rowMap = records.getRow(0);
        return rowMap == null ? "" : rowMap.getString("beneficiary");
    }

    /** 모계좌 잔액 동기화: 기관이 알려준 실제 잔액으로 장부잔액을 맞춘다. */
    public long bankBalanceRevision(String hqId) throws java.sql.SQLException {

        return WxDepositRps.balanceRevision(hqId);
    }

    /** 모계좌 잔액 조회용 설정과 원장 버전을 읽는다. */
    public SharedMap<String, Object> balanceAccount(String hqId) throws java.sql.SQLException {

        return WxDepositRps.balanceAccount(hqId);
    }

    /** 수동·야간 조회의 기관 잔액을 모계좌 원장과 대조한다. */
    public SharedMap<String, Object> refreshBalance(SharedMap<String, Object> jobMap, long balance, String actor,
                                                    String trace) {

        return ledger("bank/balance", trace, () -> WxDepositRps.refreshBalance(jobMap, balance, actor));
    }

    /** 작업기가 재조회할 모계좌 한 건을 선점한다. */
    public SharedMap<String, Object> claimBalanceReview() throws java.sql.SQLException {

        return WxDepositRps.claimBalanceReview();
    }

    /** 입금 잔액 대조: 기관 조회 당시 원장 순번이 유지된 경우에만 반영한다. */
    public SharedMap<String, Object> syncBankBalance(SharedMap<String, Object> jobMap, long reported, long revision,
                                                      String trace) {

        return ledger("deposit/balance", trace, () -> WxDepositRps.syncBalance(jobMap, reported, revision));
    }

    /** 본사 입금 지정: 미매칭 입금을 지정 Wallet의 확정 원장에 입금으로 반영한다. */
    public SharedMap<String, Object> assignDeposit(SharedMap<String, Object> inputMap, String trace) {

        return ledger("deposit/assign", trace, () -> WxDepositRps.assign(inputMap));
    }

    /** 본사 수동 입금: 통지가 오지 않은 입금을 기록하고 고른 Wallet 에 반영한다. */
    public SharedMap<String, Object> manualDeposit(SharedMap<String, Object> inputMap, String trace) {

        return ledger("deposit/manual", trace, () -> WxDepositRps.manual(inputMap));
    }

    /** 본사 입금: 미매칭 입금을 그 모계좌의 본사 Wallet 에 반영한다(이자·환급 등). */
    public SharedMap<String, Object> assignHqDeposit(SharedMap<String, Object> inputMap, String trace) {

        return ledger("deposit/assign-hq", trace, () -> WxDepositRps.assignHq(inputMap));
    }

    /** 본사 입금 반려: 매칭하지 않고 사유를 남겨 입금을 종결한다. */
    public SharedMap<String, Object> rejectDeposit(SharedMap<String, Object> inputMap, String trace) {

        return ledger("deposit/reject", trace, () -> WxDepositRps.reject(inputMap));
    }

    /**
     * Wallet 보류 설정과 해제: 지정한 금액으로 보류액을 바꾼다.
     *
     * <p>장부잔액은 바뀌지 않는다. 출금가능잔액만 다시 계산된다.</p>
     */
    public SharedMap<String, Object> hold(SharedMap<String, Object> inputMap, String trace) {

        return ledger("wallet/hold", trace, () -> WxHoldRps.apply(inputMap));
    }

    /** 본사 월 청구: 대기 청구서를 청구로 바꾸고 청구일을 기록한다. */
    public SharedMap<String, Object> billInvoice(SharedMap<String, Object> inputMap, String trace) {

        return ledger("invoice/bill", trace, () -> WxInvoiceRps.bill(inputMap));
    }

    /** 웹훅 재발송: 대기 상태를 제외한 발송을 다시 대기로 돌린다. */
    public SharedMap<String, Object> webhookRetry(SharedMap<String, Object> inputMap, String trace) {

        return ledger("webhook/retry", trace, () -> {
            String hookId  = inputMap.getString("hookId");
            int    updated = WxWebhookRps.retry(hookId, inputMap.getString("userId"));
            return SharedMap.<String, Object>of()
                    .add("id", hookId)
                    .add("changed", updated == 1);
        });
    }


    /** 기동 점검에서 거래 실행 없이 WX 연결과 업무 스키마를 확인한다. */
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
    private static SharedMap<String, Object> merchant(SharedMap<String, Object> inMap, String trace) {

        String operation = inMap.getString("operation");
        if (trace != null) inMap.put("traceId", trace);
        long start = System.nanoTime();
        try {
            return ok(WxMerchantRps.query(inMap));
        } catch (LedgerEx refused) {
            logger.info("원장 조회 거절: operation={}, code={}, reason={}, trace={}",
                    operation, refused.getCode().getCode(), refused.getMessage(), trace);
            return merchantError(refused);
        } catch (Exception failure) {
            logger.info("원장 조회 실패: operation={}, type={}, trace={}",
                    operation, failure.getClass().getSimpleName(), trace);
            logger.debug("원장 조회 예외 상세: operation={}, trace={}", operation, trace, failure);
            return error("TX500", "원장 응답을 확인해 주세요.");
        } finally {
            logger.info("Wallet API 소요 시간: operation={}, stage=ledger, elapsedMs={}, trace={}",
                    operation, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), trace);
        }
    }

    /**
     * 원장 확정 업무: 업무 거절과 처리 실패를 종전 내부 구간과 같은 코드로 구분한다.
     *
     * <p>요청 전문과 키는 로그에 남기지 않는다. 오류 유형만 남긴다.</p>
     */
    private static SharedMap<String, Object> ledger(String operation, String trace, Work work) {

        long start = System.nanoTime();
        try {
            return ok(work.run());
        } catch (LedgerEx refused) {
            logger.info("원장 처리 거절: operation={}, code={}, reason={}, trace={}",
                    operation, refused.getCode().getCode(), refused.getMessage(), trace);
            return error(refused);
        } catch (Exception failure) {
            logger.info("원장 처리 실패: operation={}, type={}, trace={}",
                    operation, failure.getClass().getSimpleName(), trace);
            logger.debug("원장 처리 예외 상세: operation={}, trace={}", operation, trace, failure);
            return error("TX503", "내부 요청을 처리하지 못했습니다.");
        } finally {
            logger.info("Wallet API 소요 시간: operation={}, stage=ledger, elapsedMs={}, trace={}",
                    operation, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), trace);
        }
    }

    /** 성공 응답. 종전 내부 구간과 같은 result·data 모양을 유지한다. */
    private static SharedMap<String, Object> ok(SharedMap<String, Object> dataMap) {

        return SharedMap.<String, Object>of()
                .add("result", true)
                .add("data", dataMap == null ? SharedMap.<String, Object>of() : dataMap);
    }

    /** 고객사 거래 거절에는 원장의 세부 사유에 해당하는 Wallet 응답 코드를 함께 담는다. */
    private static SharedMap<String, Object> merchantError(LedgerEx refused) {

        SharedMap<String, Object> errorMap = error(refused);
        if (errorMap.isEquals("code", "TX409")) {
            String walletCode = switch (refused.getCode()) {
                case DUPLICATE_REQUEST    -> "W006";
                case LIMIT_EXCEEDED       -> "W007";
                case KEY_WALLET_ERROR     -> "W008";
                case WALLET_UNAVAILABLE   -> "W011";
                case INSUFFICIENT_BALANCE -> "W012";
                case REQUEST_UNAVAILABLE  -> "W013";
                case INACTIVE_ACQUIRER    -> WalletPolicy.INACTIVE_ACQUIRER;
                default                   -> "W004";
            };
            errorMap.put("walletCode", walletCode);
        }
        return errorMap;
    }

    /** 업무 거절. 원장이 정한 코드와 공개 문구만 담는다. */
    private static SharedMap<String, Object> error(LedgerEx refused) {

        return error(refused.getCode().getCode(), refused.getMessage());
    }

    /** 거절 응답. 키·원문·계좌를 포함하지 않는 코드와 공개 문구만 반환한다. */
    private static SharedMap<String, Object> error(String code, String message) {

        return SharedMap.<String, Object>of()
                .add("result", false)
                .add("code", code)
                .add("message", message);
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
