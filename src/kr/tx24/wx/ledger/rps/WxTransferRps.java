package kr.tx24.wx.ledger.rps;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ZAddArgs;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.lib.redis.RedisText;
import kr.tx24.lib.redis.RedisTextUtils;
import kr.tx24.lib.redis.RedisUtils;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.ledger.cst.LedgerKeys;
import kr.tx24.wx.ledger.enm.LedgerCode;
import kr.tx24.wx.ledger.ex.LedgerEx;

import static kr.tx24.wx.ledger.rps.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.rps.WxDatabaseRps.update;

/**
 * 송금 접수와 결과 확정을 관리한다. 잔액 관리는 OPL_API의 wallet 계약을 따른다.
 *
 * <p>접수 시점에 고객 Wallet 원장에 출금 분개를 {@code 대기}로 기록하고 트리거가 장부잔액을 즉시 차감한다.
 * 기관에 바로 전문을 보내고 응답으로 상태를 확정한다. 성공이면 {@code 성공}, 실패가 확인되면 {@code 실패}로
 * 바꾸고 트리거가 차감한 금액을 원복한다. 통신장애는 {@code 장애}로 두어 차감을 유지하고 재조회로만 해소한다.</p>
 *
 * <p>트리거 계약은 {@code db/10-wallet-balance-trigger.sql}에 있다. 애플리케이션은 분개와 상태만 기록하고
 * {@code WALLET_BALANCE}를 직접 계산하지 않는다.</p>
 */
public final class WxTransferRps {

    private static final Logger logger = LoggerFactory.getLogger(WxTransferRps.class);

    /** 기관 결과를 확정하지 못한 송금 상태. 재조회 대상의 기준이다. */
    static final Set<String> OPEN = Set.of("접수", "처리중", "미확인");

    /** 기관 재조회를 유지하는 시간. OPL의 통신장애 재조회 계약과 같은 5시간을 사용한다. */
    public static final int RECOVERY_HOURS = 5;

    /** 송금 요청 멱등 키 보관 시간. OPL과 같은 10분을 사용한다. */
    private static final long REQUEST_TTL = 10 * 60;
    private static final long FEE_CACHE_SECONDS = TimeUnit.DAYS.toSeconds(1);

    private static final long RECOVERY_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long RECOVERY_SCAN_SECONDS = 5 * 60;
    private static final int RECOVERY_SCAN_BATCH = 200;
    private static final String RECOVERABLE = """
            ((t.status='접수' AND COALESCE(t.bankTrxId,'')='')
              OR (t.status IN ('미확인','처리중') AND t.bankTrxId REGEXP '^[0-9]{12}$'
                  AND t.regDate>=NOW()-INTERVAL ? HOUR))
            """;

    private WxTransferRps() {

    }

    /**
     * 송금 접수. 한도와 잔액을 확인하고 출금 분개를 대기로 기록해 장부잔액을 즉시 차감한다.
     *
     * <p>호출자는 업무 트랜잭션을 열고 접수 중지시간 정책을 먼저 확인한 상태여야 한다.
     * 반환한 결과로 기관 전문을 보내고 {@link #confirm}으로 상태를 확정한다.</p>
     */
    static SharedMap<String, Object> accept(Connection db, SharedMap<String, Object> inMap, String company,
                                            String walletId, LocalDateTime now) throws SQLException {

        String track   = WxPostingRps.field(inMap, "trackId", 128);
        String bankCd  = WxPostingRps.field(inMap, "bankCd", 12);
        String accntNo = WxPostingRps.field(inMap, "accntNo", 32);
        long   amount  = inMap.getLong("amount");
        if (!bankCd.matches("[0-9]{3}") || !accntNo.matches("[0-9]{1,20}") || amount < 1) {
            throw new LedgerEx(LedgerCode.INVALID, "송금 요청값을 확인해 주세요.");
        }
        int day = WxPostingRps.businessDay(now);

        // 같은 고객사의 같은 요청번호는 한 건만 접수한다. 유니크 제약은 2차 방어선으로 둔다.
        String idempotent = LedgerKeys.transferRequest(company, track);
        if (!RedisUtils.setNx(idempotent, now.toString(), REQUEST_TTL)) {
            logger.info("송금 중복 요청 차단: mchtId={}, trackId={}", company, track);
            throw new LedgerEx(LedgerCode.DUPLICATE_REQUEST, "같은 요청번호가 처리 중입니다. 원거래 조회로 결과를 확인해 주세요.");
        }
        try {
            return register(db, inMap, company, walletId, track, bankCd, accntNo, amount, day, now);
        } catch (RuntimeException | SQLException failure) {
            // 접수에 이르지 못한 요청은 즉시 재시도할 수 있도록 멱등 키를 돌려준다.
            RedisUtils.del(idempotent);
            throw failure;
        }
    }

    /** 접수 본문. 잔액과 한도를 확인하고 미확정 원장을 만든다. */
    private static SharedMap<String, Object> register(Connection db, SharedMap<String, Object> inMap, String company,
                                                      String walletId, String track, String bankCd, String accntNo,
                                                      long amount, int day, LocalDateTime now) throws SQLException {

        List<SharedMap<String, Object>> existsList = rows(db, "SELECT trxId,status FROM WX.TRX_TRANSFER WHERE mchtId=? AND trackId=?", company, track);
        if (!existsList.isEmpty()) {
            logger.info("송금 주문번호 중복: mchtId={}, trackId={}, trxId={}, status={}", company, track,
                    existsList.get(0).getString("trxId"), existsList.get(0).getString("status"));
            throw new LedgerEx(LedgerCode.DUPLICATE_REQUEST, "이미 요청된 주문번호입니다. 원거래 조회로 결과를 확인해 주세요.");
        }

        // 일별 마감과 같은 순서로 모계좌를 먼저 잠그고 Wallet의 귀속을 다시 확인한다.
        List<SharedMap<String, Object>> ownerList = rows(db, "SELECT hqId FROM WX.WALLET WHERE id=? AND mchtId=?",
                walletId, company);
        if (ownerList.isEmpty()) throw new LedgerEx(LedgerCode.WALLET_UNAVAILABLE, "Wallet 사용 상태를 확인해 주세요.");
        String                          hqId     = ownerList.get(0).getString("hqId");
        String                          trace    = inMap.getString("traceId");
        List<SharedMap<String, Object>> bankList = lockRows(db, "HQ_ACCNT", trace, new Retrieve("WX.HQ_ACCNT")
                .columns("id,balance,reservedAmt,balance-reservedAmt AS payable,alarmBalance,acq,acqId,settingRev")
                .column("COALESCE((SELECT MAX(t.idx) FROM WX.TRX_HQ t WHERE t.hqId=HQ_ACCNT.id),0) balanceRevision")
                .column("bankCd AS acqBankCd,accntNo AS acqAccount,COALESCE(key2,'') AS acqKey2")
                .where("id=? AND status='사용'")
                .forUpdate().build(), hqId);
        // 문구에 모계좌를 드러내지 않는다. 고객사가 알 일이 아니고 사유는 원장 로그에 남는다.
        if (bankList.size() != 1) {
            logger.info("모계좌 사용 상태가 아니어서 송금을 접수하지 않는다: hqId={}", hqId);
            throw new LedgerEx(LedgerCode.REQUEST_UNAVAILABLE, "송금을 접수할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        requireTransferOpen(bankList.get(0).getString("acqBankCd"));
        WxMerchantRps.requireActiveAcquirer(bankList.get(0));
        List<SharedMap<String, Object>> walletList = lockRows(db, "WALLET", trace, new Retrieve("WX.WALLET w")
                .columns("w.id,w.mchtId,w.hqId,w.limitOnce,w.limitDay,w.limitMonth,w.minWithdrawAmt,w.sender")
                .column("b.balance,b.held,b.available")
                .joinInner("WX.WALLET_BALANCE b", "b.walletId=w.id")
                .where("w.id=? AND w.mchtId=? AND w.hqId=? AND w.ownerType='MCHT'")
                .where("w.status='사용' AND w.apiStatus='사용'")
                .forUpdate().build(), walletId, company, hqId);
        if (walletList.size() != 1) throw new LedgerEx(LedgerCode.WALLET_UNAVAILABLE, "Wallet 사용 상태를 확인해 주세요.");
        SharedMap<String, Object> accountMap = walletList.get(0);

        SharedMap<String, Object> rateMap = fee(db, company, day, amount);
        long                      charge  = rateMap.getLong("feeAmt");
        logger.debug("송금 수수료 산정: mchtId={}, amount={}, feeAmt={}, feeRateIdx={}",
                company, amount, charge, rateMap.getString("feeRateIdx"));

        if (!isWalletSettings(accountMap)) {
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "Wallet 출금 설정을 확인해 주세요.");
        }
        checkLimit(db, walletId, accountMap, amount, now);

        long available = accountMap.getLong("available");
        if (available < amount) {
            logger.info("송금 잔액 부족: walletId={}, amount={}, available={}", walletId, amount, available);
            throw new LedgerEx(LedgerCode.INSUFFICIENT_BALANCE, "출금가능잔액이 부족합니다.");
        }

        if (bankList.get(0).getLong("payable") < amount) {
            logger.warn("모계좌 송금 가능액 부족: hqId={}, amount={}, payable={}",
                    hqId, amount, bankList.get(0).getLong("payable"));
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "송금 요청이 일시 중지되었습니다. 잠시 후 다시 시도해 주세요.");
        }

        // 기관 연동 설정은 차감 전에 확인한다. 보낼 수 없는 요청을 접수하면 잔액만 묶인다.
        SharedMap<String, Object> upstreamMap = upstream(hqId, bankList.get(0));
        if (upstreamMap == null) {
            logger.warn("모계좌 기관 연동 설정이 없어 송금을 접수하지 않는다: hqId={}, acq={}",
                    hqId, bankList.get(0).getString("acq"));
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "기관 연동 설정이 없어 송금 접수가 중지되었습니다.");
        }

        String trxId  = IDUtils.getUnique("S");
        String sender = inMap.isBlank("sender") ? accountMap.getString("sender")
                : WxPostingRps.field(inMap, "sender", 20);
        String holder = inMap.getString("holder");

        // 수취 계좌번호는 평문으로 받는다. 저장은 마스킹과 암호문으로만 하고 암호 키는 WX_ADM 에만 둔다.
        String masked = WxMerchantRps.maskedAccount(accntNo);
        String encrypted = WxPostingRps.encryptForLedger(accntNo);
        String bankName = WxMerchantRps.bankName(db, bankCd);

        update(db, """
                INSERT INTO WX.TRX_TRANSFER(trxId,mchtId,walletId,hqId,ioId,sender,trackId,requestHash,amount,
                                            bankCd,bankName,accntNo,accntNoEnc,holder,status,feeAmt,feeRateIdx,
                                            feeTarget,regId,regDay,regDate)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'접수',?,?,0,'WX_API',?,?)
                """, trxId, company, walletId, hqId, inMap.getString("ioId"), sender, track,
                inMap.getString("requestHash"), amount, bankCd, bankName, masked, encrypted,
                holder, charge, rateMap.get("feeRateIdx"), day, now);

        // 출금 분개를 대기로 기록한다. 트리거가 장부잔액을 즉시 차감한다.
        String ledgerId = withdraw(db, accountMap, trxId, track, amount, charge, bankCd, bankName, holder, masked,
                sender, now);
        update(db, "UPDATE WX.TRX_TRANSFER SET walletTrxId=? WHERE trxId=?", ledgerId, trxId);

        if (update(db, "UPDATE WX.HQ_ACCNT SET reservedAmt=reservedAmt+? WHERE id=? AND status='사용'",
                amount, hqId) != 1) {
            throw new SQLException("모계좌 송금 예약 반영에 실패했습니다.");
        }

        logger.info("송금 접수와 선차감: trxId={}, mchtId={}, walletId={}, hqId={}, amount={}, feeAmt={}, walletTrxId={}",
                trxId, company, walletId, hqId, amount, charge, ledgerId);

        return SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("trackId", track)
                .add("walletTrxId", ledgerId)
                .add("status", "접수")
                .add("amount", amount)
                .add("feeAmt", charge)
                .add("bankCd", bankCd)
                .add("bankName", bankName)
                .add("accntNo", accntNo)
                .add("accntNoMasked", masked)
                .add("holder", holder)
                .add("sender", sender)
                .add("hqId", hqId)
                .add("balanceRevision", bankList.get(0).getLong("balanceRevision"))
                .add("settingRev", bankList.get(0).getLong("settingRev"))
                .add("upstream", upstreamMap);
    }

    /** 고객사 송금 접수와 전송 전에 출금 모계좌 은행의 긴급중지를 확인한다. */
    public static void requireTransferOpen(String bankCd) {

        long stopped;
        try {
            stopped = RedisTextUtils.exists(LedgerKeys.TRANSFER_EMERGENCY_STOP + bankCd);
        } catch (RuntimeException failure) {
            logger.error("송금 긴급중지 상태 조회 실패: bankCd={}", bankCd, failure);
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "송금 가능 상태를 확인할 수 없습니다.");
        }
        if (stopped > 0) {
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "은행 장애 대응으로 송금이 일시 중지되었습니다.");
        }
    }

    /** 송금 접수의 잠금 대기와 행 조회에 걸린 시간을 함께 기록한다. */
    private static List<SharedMap<String, Object>> lockRows(Connection db, String target, String trace,
                                                          String sql, Object... args) throws SQLException {

        long start = System.nanoTime();
        try {
            return rows(db, sql, args);
        } finally {
            logger.info("Wallet API 소요 시간: operation=payout, stage=lockQuery, target={}, elapsedMs={}, trace={}",
                    target, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), trace);
        }
    }

    /** 잠근 Wallet의 한도 설정이 누락되거나 잘못된 형식이면 무제한으로 처리하지 않는다. */
    private static boolean isWalletSettings(SharedMap<String, Object> settingsMap) {

        return settingsMap.get("sender") instanceof String
                && List.of("limitOnce", "limitDay", "limitMonth", "minWithdrawAmt").stream().allMatch(key -> {
                    Object amount = settingsMap.get(key);
                    return amount instanceof Long || amount instanceof Integer;
                });
    }

    /**
     * 모계좌에 등록된 기관 연동 설정을 WX_API가 사용할 전송 프로필로 만든다.
     *
     * <p>중개사·식별자·모계좌·보호키 중 하나라도 없으면 전문을 보낼 수 없으므로 {@code null}을 돌려준다.
     * 접수에서는 차감 전에 이를 확인해 거절하고, 재조회에서는 운영자 확인 대상으로 남긴다.</p>
     */
    static SharedMap<String, Object> upstream(String hqId, SharedMap<String, Object> bankMap) {

        if (!"HYPHEN".equals(bankMap.getString("acq")) || !WalletPolicy.isValidAcqSid(bankMap.getString("acqId"))
                || bankMap.isBlank("acqBankCd")
                || bankMap.isBlank("acqAccount") || bankMap.isBlank("acqKey2")) {
            logger.debug("모계좌 기관 연동 설정 미완성: hqId={}, acq={}, acqId={}",
                    hqId, bankMap.getString("acq"), bankMap.getString("acqId"));
            return null;
        }
        // 모계좌 ID 는 기관 거래 일련번호를 모계좌별로 나누는 키에 쓴다. 보호값이 아니다.
        return SharedMap.<String, Object>of()
                .add("hqId", hqId)
                .add("acq", bankMap.getString("acq"))
                .add("acqSid", bankMap.getString("acqId"))
                .add("acqBankCd", bankMap.getString("acqBankCd"))
                .add("acqAccount", bankMap.getString("acqAccount"))
                .add("acqKey2", bankMap.getString("acqKey2"))
                // 기관이 지급 전문에 돌려주는 모계좌 잔액의 경고 기준이다. 보호값이 아니다.
                .add("alarmBalance", bankMap.getLong("alarmBalance", 0L));
    }

    /** 접수: 고객 Wallet의 출금 분개를 대기로 기록한다. 장부잔액은 트리거가 차감한다. */
    private static String withdraw(Connection db, SharedMap<String, Object> accountMap, String trxId, String track,
                                    long amount, long charge, String bankCd, String bankName, String holder,
                                    String accntNo, String sender, LocalDateTime now) throws SQLException {

        String walletId = accountMap.getString("id");
        long   before   = accountMap.getLong("balance");
        long   after    = Math.subtractExact(before, amount);
        String id       = IDUtils.getUnique("T");
        int    day      = WxPostingRps.businessDay(now);
        update(db, """
                INSERT INTO WX.TRX_WALLET(id,ownerType,mchtId,walletId,hqId,trackId,refType,refId,refSeq,`sequence`,
                                          trxType,dtlType,status,amount,depositAmt,withdrawnAmt,fee,balance,balanceAfter,
                                          bankCd,bankName,holder,accntNo,sender,trxDay,trxDate,regId,regDay)
                SELECT ?,'MCHT',?,?,?,?,'TRX_TRANSFER',?,1,COALESCE(MAX(`sequence`),0)+1,
                       '출금','일반출금','처리중',?,0,?,?,?,?,?,?,?,?,?,?,?,'WX_API',?
                  FROM WX.TRX_WALLET WHERE walletId=?
                """, id, accountMap.getString("mchtId"), walletId, accountMap.getString("hqId"), track, trxId,
                Math.negateExact(amount), amount, charge, before, after, bankCd, bankName, holder, accntNo, sender,
                day, now, day, walletId);
        logger.debug("고객 Wallet 출금 선차감 분개: walletId={}, before={}, after={}, ledgerId={}",
                walletId, before, after, id);
        return id;
    }

    /** 과금 기준일에 적용할 수수료 단가를 조회한다. 마감 검증과 같은 식으로 계산한다. */
    private static SharedMap<String, Object> fee(Connection db, String company, int day, long amount) throws SQLException {

        String key = "WX:API:TRANSFER:FEE:" + company + ":" + day;
        SharedMap<String, Object> policyMap = WxDatabaseRps.fetchRow(key, FEE_CACHE_SECONDS,
                () -> WxDatabaseRps.select(db, """
                        SELECT COALESCE(f.idx,0) AS idx,f.transferUnitAmt,CAST(f.transferPercentRate AS CHAR) AS transferPercentRate
                          FROM (SELECT 1) seed LEFT JOIN (
                               SELECT idx,transferUnitAmt,transferPercentRate FROM WX.MCHT_FEE
                                WHERE mchtId=? AND applyDay<=? AND status='사용'
                                ORDER BY applyDay DESC LIMIT 1
                          ) f ON 1=1
                        """, company, day));
        if (policyMap.getLong("idx") == 0) {
            return SharedMap.<String, Object>of()
                    .add("feeAmt", 0L);
        }
        long percentFee = policyMap.getBigDecimal("transferPercentRate").multiply(BigDecimal.valueOf(amount))
                .movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return SharedMap.<String, Object>of()
                .add("feeAmt", Math.addExact(policyMap.getLong("transferUnitAmt"), percentFee))
                .add("feeRateIdx", policyMap.getLong("idx"));
    }

    /** 건당·일·월 한도와 최소 출금액을 확인한다. 0은 무제한으로 본다. */
    private static void checkLimit(Connection db, String walletId, SharedMap<String, Object> settingsMap,
                                   long amount, LocalDateTime now) throws SQLException {

        long minimum = settingsMap.getLong("minWithdrawAmt");
        if (minimum > 0 && amount < minimum) throw new LedgerEx(LedgerCode.INVALID, "최소 출금금액 미만입니다.");

        long once = settingsMap.getLong("limitOnce");
        if (once > 0 && amount > once) {
            logger.info("송금 건당 한도 초과: walletId={}, amount={}, limitOnce={}", walletId, amount, once);
            throw new LedgerEx(LedgerCode.LIMIT_EXCEEDED, "건당 출금한도를 초과했습니다.");
        }
        long dayLimit   = settingsMap.getLong("limitDay");
        long monthLimit = settingsMap.getLong("limitMonth");
        if (dayLimit <= 0 && monthLimit <= 0) return;

        // 실패로 확정된 건은 한도에서 제외하고 미확정과 성공만 센다.
        int day = WxPostingRps.businessDay(now);
        SharedMap<String, Object> usedMap;
        if (monthLimit > 0) {
            LocalDateTime monthStart = now.withDayOfMonth(1);
            int           firstDay   = WxPostingRps.businessDay(monthStart);
            int           nextMonth  = WxPostingRps.businessDay(monthStart.plusMonths(1));
            usedMap = rows(db, """
                    SELECT COALESCE(SUM(CASE WHEN regDay=? THEN amount ELSE 0 END),0) AS dayAmt,
                           COALESCE(SUM(amount),0) AS monthAmt
                      FROM WX.TRX_TRANSFER
                     WHERE walletId=? AND regDay>=? AND regDay<? AND status<>'실패'
                    """, day, walletId, firstDay, nextMonth).get(0);
        } else {
            usedMap = rows(db, """
                    SELECT COALESCE(SUM(amount),0) AS dayAmt
                      FROM WX.TRX_TRANSFER
                     WHERE walletId=? AND regDay=? AND status<>'실패'
                    """, walletId, day).get(0);
        }
        if (dayLimit > 0 && usedMap.getLong("dayAmt") + amount > dayLimit) {
            logger.info("송금 일 한도 초과: walletId={}, used={}, amount={}, limitDay={}",
                    walletId, usedMap.getLong("dayAmt"), amount, dayLimit);
            throw new LedgerEx(LedgerCode.LIMIT_EXCEEDED, "일 출금한도를 초과했습니다.");
        }
        if (monthLimit > 0 && usedMap.getLong("monthAmt") + amount > monthLimit) {
            logger.info("송금 월 한도 초과: walletId={}, used={}, amount={}, limitMonth={}",
                    walletId, usedMap.getLong("monthAmt"), amount, monthLimit);
            throw new LedgerEx(LedgerCode.LIMIT_EXCEEDED, "월 출금한도를 초과했습니다.");
        }
    }

    /**
     * 기관 전문 전송 직전에 받은 원거래번호를 즉시 커밋한다.
     *
     * <p>이 값이 없으면 통신장애가 났을 때 기관에 원거래를 물어볼 수 없다. 그래서 전송 전에 따로 커밋한다.</p>
     */
    public static void recordAcq(String trxId, String acqTrxId) throws SQLException {

        if (!acqTrxId.matches("[0-9]{12}")) throw new LedgerEx(LedgerCode.INVALID, "기관 원거래번호를 확인해 주세요.");
        try (Connection db = WxDatabaseRps.open()) {
            int changed = update(db, "UPDATE WX.TRX_TRANSFER SET bankTrxId=?,status='처리중'"
                    + " WHERE trxId=? AND status='접수' AND COALESCE(bankTrxId,'')=''", acqTrxId, trxId);
            if (changed != 1) {
                logger.info("기관 원거래번호 선기록 실패: trxId={}, changed={}", trxId, changed);
                throw new SQLException("기관 원거래번호 선기록에 실패했습니다.");
            }
            if (!db.getAutoCommit()) db.commit();
            logger.info("기관 원거래번호 선기록: trxId={}, acqTrxId={}", trxId, acqTrxId);
        }
    }

    /**
     * 송금 결과를 확정한다.
     *
     * <p>성공은 원장을 성공으로 바꾸고 모계좌 장부잔액을 줄인다. 실패는 원장을 실패로 바꾸어 트리거가
     * 차감한 금액을 원복하게 하고 모계좌 예약을 해제한다. 미확정은 원장을 장애로 두어 차감을 유지하고
     * 장애 큐에 적재한다.</p>
     */
    public static SharedMap<String, Object> confirm(SharedMap<String, Object> inMap) throws SQLException {

        return confirm(inMap, false);
    }

    /** 타행이체불능명세를 원거래와 대조하고 최초 상태 변경만 커밋 후 통지한다. */
    public static SharedMap<String, Object> failureNotice(SharedMap<String, Object> noticeMap) throws SQLException {

        SharedMap<String, Object> resultMap;
        try (Connection db = WxDatabaseRps.open()) {
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                resultMap = applyFailureNotice(db, noticeMap);
                db.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
        WxWebhookRps.publish(resultMap);
        try {
            RedisTextUtils.zrem(LedgerKeys.TRANSFER_RECOVERY_QUEUE, resultMap.getString("trxId"));
        } catch (RuntimeException unavailable) {
            logger.warn("타행이체불능 복구 큐 정리 지연: trxId={}", resultMap.getString("trxId"));
        }
        return resultMap;
    }

    /** 모계좌와 송금을 잠근 뒤 전일·당일 성공 송금만 정정하고 감사 이력을 남긴다. */
    public static SharedMap<String, Object> reverse(SharedMap<String, Object> commandMap) throws SQLException {

        SharedMap<String, Object> resultMap;
        try (Connection db = WxDatabaseRps.open()) {
            db.setAutoCommit(false);
            try {
                resultMap = reverse(db, commandMap);
                db.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
        WxWebhookRps.publish(resultMap);
        return resultMap;
    }

    private static SharedMap<String, Object> reverse(Connection db, SharedMap<String, Object> commandMap)
            throws SQLException {

        String trxId = commandMap.getString("trxId");
        lockTransferBank(db, trxId);
        List<SharedMap<String, Object>> transferList = rows(db, """
                SELECT t.trxId,t.hqId,t.mchtId,t.walletId,t.walletTrxId,t.trackId,t.status,t.amount,t.bankTrxId
                  FROM WX.TRX_TRANSFER t
                 WHERE t.trxId=? AND t.status='성공'
                   AND t.regDay BETWEEN DATE_FORMAT(CURRENT_DATE-INTERVAL 1 DAY,'%Y%m%d')+0
                                    AND DATE_FORMAT(CURRENT_DATE,'%Y%m%d')+0 FOR UPDATE
                """, trxId);
        if (transferList.size() != 1) {
            throw new LedgerEx(LedgerCode.CONFLICT, "전일 또는 당일 접수된 성공 송금만 정정할 수 있습니다.");
        }
        SharedMap<String, Object> transferMap = transferList.get(0);
        SharedMap<String, Object> resultMap = reverseSuccess(db, transferMap, transferMap.getString("bankTrxId"),
                commandMap.getString("responseCode"), commandMap.getString("reason"), commandMap.getString("actor"));
        audit(db, commandMap, resultMap, "실패", "TRANSFER_REVERSE");
        return resultMap;
    }

    private static SharedMap<String, Object> applyFailureNotice(Connection db, SharedMap<String, Object> noticeMap)
            throws SQLException {

        String reference = noticeMap.getString("acqTrxId");
        List<SharedMap<String, Object>> bankList = rows(db, """
                SELECT id FROM WX.HQ_ACCNT WHERE bankCd=? AND accntNo=? AND acq='HYPHEN' FOR UPDATE
                """, noticeMap.getString("bankCd"), noticeMap.getString("parentAccount"));
        if (bankList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "원거래 모계좌를 확인해 주세요.");
        String hqId = bankList.get(0).getString("id");
        List<SharedMap<String, Object>> transferList = rows(db, """
                SELECT trxId,hqId,mchtId,walletId,walletTrxId,trackId,status,amount,accntNoEnc
                  FROM WX.TRX_TRANSFER WHERE bankTrxId=? AND hqId=? AND bankCd=? AND amount=? FOR UPDATE
                """, reference, hqId, noticeMap.getString("recvBankCd"), noticeMap.getLong("amount"));
        if (transferList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "원거래 송금을 확인해 주세요.");
        SharedMap<String, Object> transferMap = transferList.get(0);
        byte[] plaintext = kr.tx24.lib.kms.KMSUtils.decrypt(
                java.util.Base64.getDecoder().decode(transferMap.getString("accntNoEnc")));
        if (!java.util.Arrays.equals(plaintext, noticeMap.getString("recvAccount").getBytes(
                java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new LedgerEx(LedgerCode.CONFLICT, "원거래 수취계좌가 일치하지 않습니다.");
        }
        String trxId  = transferMap.getString("trxId");
        String status = transferMap.getString("status");
        String code   = noticeMap.getString("acqResultCd");
        String msg    = noticeMap.getString("acqResultMsg");
        String lineId = transferMap.getString("walletTrxId");
        if ("처리중".equals(status) || "미확인".equals(status)) {
            SharedMap<String, Object> commandMap = SharedMap.<String, Object>of()
                    .add("acqTrxId", reference)
                    .add("responseCode", code)
                    .add("responseMsg", msg);
            return settle(db, trxId, "실패", commandMap, false);
        }
        if (!"실패".equals(status) && !"성공".equals(status)) throw new LedgerEx(LedgerCode.CONFLICT);
        if ("실패".equals(status)) {
            update(db, "UPDATE WX.TRX_WALLET SET acqResultCd=?,acqResultMsg=? WHERE id=? AND status='실패'",
                    code, msg, lineId);
            update(db, """
                    UPDATE WX.TRX_HQ SET acqResultCd=?,acqResultMsg=?
                     WHERE hqId=? AND refType='TRX_TRANSFER' AND refId=? AND trxType='출금' AND status='실패'
                    """, code, msg, hqId, trxId);
            update(db, "UPDATE WX.TRX_TRANSFER SET responseCode=? WHERE trxId=?", code, trxId);
            return unchanged(trxId, transferMap);
        }

        return reverseSuccess(db, transferMap, reference, code, msg, "WX_API");
    }

    private static SharedMap<String, Object> reverseSuccess(Connection db, SharedMap<String, Object> transferMap,
            String reference, String code, String msg, String actor) throws SQLException {

        String trxId  = transferMap.getString("trxId");
        String hqId   = transferMap.getString("hqId");
        String lineId = transferMap.getString("walletTrxId");
        SharedMap<String, Object> resultMap = unchanged(trxId, transferMap);
        // 원금은 건별 불능금액과 무관하게 원거래 전액을 한 번만 돌려준다.
        String walletId = transferMap.getString("walletId");
        List<SharedMap<String, Object>> balanceList = rows(db,
                "SELECT balance FROM WX.WALLET_BALANCE WHERE walletId=? FOR UPDATE", walletId);
        if (balanceList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND);
        long expected = Math.addExact(balanceList.get(0).getLong("balance"), transferMap.getLong("amount"));
        // 모계좌 출금 당시의 금액과 잔액은 보존하고, 원송금의 최종 결과를 함께 정정한다.
        if (update(db, """
                UPDATE WX.TRX_HQ SET status='실패',acqResultCd=?,acqResultMsg=?
                 WHERE hqId=? AND refType='TRX_TRANSFER' AND refId=? AND trxType='출금' AND status='성공'
                """, code, msg, hqId, trxId) != 1) {
            throw new LedgerEx(LedgerCode.CONFLICT, "원송금의 본사 모계좌 출금 원장을 확인해 주세요.");
        }
        if (update(db, """
                UPDATE WX.TRX_WALLET SET status='실패',resultCd=?,resultMsg='타행이체불능',acqResultCd=?,acqResultMsg=?
                 WHERE id=? AND walletId=? AND refType='TRX_TRANSFER' AND refId=? AND status='성공'
                """, code, code, msg, lineId, walletId, trxId) != 1) throw new LedgerEx(LedgerCode.CONFLICT);
        if (rows(db, "SELECT balance FROM WX.WALLET_BALANCE WHERE walletId=? FOR UPDATE", walletId)
                .get(0).getLong("balance") != expected) throw new SQLException("타행이체불능 Wallet 원금 원복을 확인해 주세요.");
        update(db, """
                UPDATE WX.TRX_TRANSFER SET status='실패',responseCode=?,feeTarget=0,udtDate=NOW(),udtId=?
                 WHERE trxId=?
                """, code, actor, trxId);
        // 모계좌에서는 반환 입금이 별도 거래로 관측될 수 있어 기관 잔액을 다시 대조한다.
        String prefix = "test".equals(kr.tx24.wx.api.WalletConfig.upstreamEnv()) ? "BRT-TEST-" : "BRT-PROD-";
        WxDepositRps.scheduleBalanceReview(db, hqId, prefix + IDUtils.getUnique("B"));
        resultMap.put("changed", true);
        resultMap.put("status", "실패");
        resultMap.put("amount", transferMap.getLong("amount"));
        resultMap.put("acqTrxId", reference);
        WxWebhookRps.enqueue(db, resultMap, WxWebhookRps.TRANSFER_SETTLED, null);
        return resultMap;
    }

    /** 재조회 작업: 전송을 시작하지 않은 접수만 실패로 닫고 차감과 예약을 원복한다. */
    public static boolean expireUnsent(String trxId) throws SQLException {

        SharedMap<String, Object> commandMap = SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("outcome", WalletPolicy.OUTCOME_FAILED)
                .add("responseCode", WalletPolicy.NOT_SENT)
                .add("responseMsg", "기관 전송 전 접수 만료");
        return confirm(commandMap, true).getBoolean("changed");
    }

    /** 송금 확정과 전송 전 만료를 같은 원장 트랜잭션으로 처리한다. */
    private static SharedMap<String, Object> confirm(SharedMap<String, Object> inMap, boolean unsentOnly)
            throws SQLException {

        String trxId   = WxPostingRps.field(inMap, "trxId", 32);
        String outcome = WxPostingRps.field(inMap, "outcome", 12);
        if (!Set.of("성공", "실패", "미확인").contains(outcome)) {
            throw new LedgerEx(LedgerCode.INVALID, "확정 결과를 확인해 주세요.");
        }
        SharedMap<String, Object> resultMap;
        try (Connection db = WxDatabaseRps.open()) {
            db.setAutoCommit(false);
            try {
                resultMap = settle(db, trxId, outcome, inMap, unsentOnly);
                // 운영자 종결은 근거를 같은 트랜잭션에 남긴다. 기관 응답 확정에는 근거가 없다.
                if (!inMap.isBlank("reason")) audit(db, inMap, resultMap, outcome, "TRANSFER_SETTLE");
                db.commit();
            } catch (Exception failure) {
                try {
                    db.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                logger.error("송금 확정 처리 실패: trxId={}, outcome={}", trxId, outcome, failure);
                if (!unsentOnly) {
                    parkAfterFailure(trxId, inMap);
                }
                throw failure;
            }
            if (!unsentOnly) balance(db, resultMap);
        }

        // Redis 복구 등록, 멱등 키 정리, 웹훅 발행은 커밋된 결과를 바꾸지 않는다.
        if (resultMap.isEquals("status", "미확인")) enqueueRecovery(trxId);
        WxWebhookRps.publish(resultMap);
        clearRequest(resultMap);
        return resultMap;
    }

    /** 확정 처리 본문. 호출자가 업무 트랜잭션을 관리한다. */
    private static SharedMap<String, Object> settle(Connection db, String trxId, String outcome,
                                                     SharedMap<String, Object> inMap, boolean unsentOnly) throws SQLException {

        List<SharedMap<String, Object>> bankList = lockTransferBankRows(db, trxId, true);
        long bankBalance;
        if (bankList.isEmpty()) {
            List<SharedMap<String, Object>> ownerList = rows(db, """
                    SELECT t.status,t.mchtId,t.trackId,t.walletId,CASE WHEN h.id IS NULL THEN 0 ELSE 1 END AS hasBank
                      FROM WX.TRX_TRANSFER t LEFT JOIN WX.HQ_ACCNT h ON h.id=t.hqId
                     WHERE t.trxId=?
                    """, trxId);
            if (ownerList.isEmpty()) throw new LedgerEx(LedgerCode.NOT_FOUND, "송금 거래를 찾을 수 없습니다.");
            SharedMap<String, Object> ownerMap = ownerList.get(0);
            if (ownerMap.getInt("hasBank") == 0) throw new LedgerEx(LedgerCode.NOT_FOUND, "모계좌를 찾을 수 없습니다.");
            // 성공과 실패는 미확정으로 돌아가지 않으므로 현재 상태만 읽어 중복 확정을 종결한다.
            if (ownerMap.isEquals("status", "성공") || ownerMap.isEquals("status", "실패")) {
                return existingResult(db, trxId, ownerMap);
            }
            bankBalance = lockTransferBank(db, trxId);
        } else {
            bankBalance = bankList.get(0).getLong("balance");
        }

        List<SharedMap<String, Object>> foundList = rows(db, new Retrieve("WX.TRX_TRANSFER t")
                .columns("t.trxId,t.mchtId,t.walletId,t.hqId,t.amount,t.feeAmt,t.status,t.sender,t.holder")
                .column("t.trackId,t.bankCd,t.bankName,t.accntNo,t.bankTrxId,t.walletTrxId")
                .column("CASE WHEN t.regDate<=NOW()-INTERVAL 2 MINUTE THEN 1 ELSE 0 END AS expired")
                .where("t.trxId=?")
                .forUpdate().build(), trxId);
        if (foundList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "송금 거래를 찾을 수 없습니다.");
        SharedMap<String, Object> transferMap = foundList.get(0);
        String status = transferMap.getString("status");
        if (!OPEN.contains(status) || (unsentOnly && (!"접수".equals(status) || !transferMap.isBlank("bankTrxId")
                || transferMap.getInt("expired") != 1))) {
            return existingResult(db, trxId, transferMap);
        }

        String acqTrxId   = inMap.getString("acqTrxId", transferMap.getString("bankTrxId"));
        String resultCode = inMap.getString("responseCode");
        // 확정 작업자. 기관 응답으로 확정하면 WX_API, 운영자가 종결하면 그 계정이다.
        String                    actor     = inMap.isBlank("actor") ? "WX_API" : WxPostingRps.field(inMap, "actor", 24);
        long                      amount    = transferMap.getLong("amount");
        String                    walletId  = transferMap.getString("walletId");
        String                    hqId      = transferMap.getString("hqId");
        String                    ledgerId  = transferMap.getString("walletTrxId");
        SharedMap<String, Object> resultMap = SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("changed", true)
                .add("walletId", walletId)
                .add("beforeStatus", transferMap.getString("status"))
                .add("mchtId", transferMap.getString("mchtId"))
                .add("trackId", transferMap.getString("trackId"));

        if ("미확인".equals(outcome)) {
            // 차감을 유지한다. 원장도 송금과 같은 미확인으로 두고 모계좌 예약도 풀지 않는다.
            update(db, "UPDATE WX.TRX_WALLET SET status='미확인',resultCd=?,resultMsg='통신장애, 결과 확인 중',"
                    + "acqTrxId=?,acqResultCd=?,acqResultMsg=?,acqTrxDate=? WHERE id=? AND status='처리중'",
                    resultCode, acqTrxId, resultCode, inMap.getString("responseMsg"),
                    inMap.getString("acqTrxDate"), ledgerId);
            update(db, "UPDATE WX.TRX_TRANSFER SET status='미확인',responseCode=?,bankTrxId=?,udtId=?"
                    + " WHERE trxId=?", resultCode, acqTrxId, actor, trxId);
            logger.info("송금 미확정 주차: trxId={}, amount={}, acqTrxId={}, 차감 유지", trxId, amount, acqTrxId);
            return resultMap.add("status", "미확인");
        }

        if ("실패".equals(outcome)) {
            // 원장을 실패로 바꾸면 트리거가 차감한 금액을 원복한다.
            if (update(db, "UPDATE WX.TRX_WALLET SET status='실패',resultCd=?,resultMsg='이체 실패 확정',"
                    + "acqTrxId=?,acqResultCd=?,acqResultMsg=?,acqTrxDate=COALESCE(NULLIF(?,''),acqTrxDate) WHERE id=? AND status IN ('처리중','미확인')",
                    resultCode, acqTrxId, resultCode, inMap.getString("responseMsg"),
                    inMap.getString("acqTrxDate"), ledgerId) != 1) {
                throw new SQLException("송금 원장 실패 확정에 실패했습니다.");
            }
            update(db, """
                    UPDATE WX.TRX_TRANSFER SET status='실패',responseCode=?,bankTrxId=?,feeTarget=0,
                           udtId=?,udtDate=NOW() WHERE trxId=?
                    """, resultCode, acqTrxId, actor, trxId);
            releaseBank(db, hqId, amount);
            logger.info("송금 실패 확정과 잔액 원복: trxId={}, walletId={}, amount={}, responseCode={}",
                    trxId, walletId, amount, resultCode);
            resultMap
                    .add("status", "실패")
                    .add("amount", amount)
                    .add("acqTrxId", acqTrxId);
            // 확정과 통지를 한 트랜잭션에 둔다. 확정만 되고 통지가 빠지는 상태를 만들지 않는다.
            WxWebhookRps.enqueue(db, resultMap, WxWebhookRps.TRANSFER_SETTLED, null);
            return resultMap;
        }

        LocalDateTime postedAt = WxPostingRps.postingTime(db);
        if (update(db, "UPDATE WX.TRX_WALLET SET status='성공',resultCd='0000',resultMsg='정상이체',"
                + "acqTrxId=?,acqResultCd=?,acqResultMsg=?,acqTrxDate=COALESCE(NULLIF(?,''),acqTrxDate) WHERE id=? AND status IN ('처리중','미확인')",
                acqTrxId, resultCode, inMap.getString("responseMsg"), inMap.getString("acqTrxDate"), ledgerId) != 1) {
            throw new SQLException("송금 원장 성공 확정에 실패했습니다.");
        }
        // 실제 지급이므로 모계좌 장부잔액을 줄이고 송금 예약을 해제한다.
        //
        // 지급이 끝나면 고객 Wallet과 모계좌에서 각각 원금만큼 줄고 미배분 입금은 그대로 남는다.
        // 본사 Wallet까지 줄이면 같은 금액을 두 번 뺀다.
        // 수수료 정산처럼 Wallet 사이에서 자금이 옮겨 가는 처리에서만 본사 Wallet에 분개한다.
        hqLedger(db, transferMap, bankBalance, acqTrxId, actor, postedAt);
        if (update(db, "UPDATE WX.HQ_ACCNT SET balance=balance-?,reservedAmt=GREATEST(0,reservedAmt-?) WHERE id=?",
                amount, amount, hqId) != 1) {
            throw new SQLException("모계좌 장부잔액 반영에 실패했습니다.");
        }
        if (inMap.containsKey("reportedBalance") && WalletPolicy.SUCCEEDED.equals(resultCode)) {
            WxDepositRps.syncTransferBalance(db, hqId, trxId, bankBalance - amount, inMap, actor);
        }
        update(db, """
                UPDATE WX.TRX_TRANSFER SET status='성공',responseCode=?,bankTrxId=?,
                       feeTarget=CASE WHEN feeAmt>0 THEN 1 ELSE 0 END,udtId=?,udtDate=NOW()
                 WHERE trxId=?
                """, resultCode, acqTrxId, actor, trxId);
        logger.info("송금 성공 확정: trxId={}, walletId={}, amount={}, walletTrxId={}, acqTrxId={}",
                trxId, walletId, amount, ledgerId, acqTrxId);
        resultMap
                .add("status", "성공")
                .add("walletTrxId", ledgerId)
                .add("amount", amount)
                .add("acqTrxId", acqTrxId);
        // 확정과 통지를 한 트랜잭션에 둔다. 확정만 되고 통지가 빠지는 상태를 만들지 않는다.
        WxWebhookRps.enqueue(db, resultMap, WxWebhookRps.TRANSFER_SETTLED, postedAt);
        return resultMap;
    }

    /** 통지가 먼저 실패를 확정했으면 후착 응답에도 확정된 실패 사유를 사용한다. */
    private static SharedMap<String, Object> existingResult(Connection db, String trxId,
                                                             SharedMap<String, Object> transferMap) throws SQLException {

        SharedMap<String, Object> resultMap = unchanged(trxId, transferMap);
        if (transferMap.isEquals("status", "실패")) {
            List<SharedMap<String, Object>> reasonList = rows(db, """
                    SELECT t.responseCode,w.acqResultMsg FROM WX.TRX_TRANSFER t
                      JOIN WX.TRX_WALLET w ON w.id=t.walletTrxId WHERE t.trxId=?
                    """, trxId);
            if (!reasonList.isEmpty()) resultMap.putAll(reasonList.get(0));
        }
        return resultMap;
    }

    /** 상태가 그대로인 확정 요청에 기존 거래 정보와 감사 기록용 상태를 반환한다. */
    private static SharedMap<String, Object> unchanged(String trxId, SharedMap<String, Object> transferMap) {

        String status = transferMap.getString("status");
        return SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("status", status)
                .add("beforeStatus", status)
                .add("mchtId", transferMap.getString("mchtId"))
                .add("trackId", transferMap.getString("trackId"))
                .add("walletId", transferMap.getString("walletId"))
                .add("changed", false);
    }

    /**
     * 운영자 종결의 근거를 감사 이력에 남긴다.
     *
     * <p>확정과 같은 트랜잭션에 쓴다. 근거 없이 상태만 바뀐 건이 남지 않아야 한다.
     * 이미 확정된 건을 다시 종결하려 한 경우({@code changed=false})도 시도 자체를 기록한다.</p>
     */
    private static void audit(Connection db, SharedMap<String, Object> inMap, SharedMap<String, Object> resultMap,
                               String outcome, String action) throws SQLException {

        String actor = WxPostingRps.field(inMap, "actor", 24);
        update(db, """
                INSERT INTO WX.AUDIT(id,mchtId,unit,action,refType,refId,reason,beforeJson,afterJson,accessIp,
                                     regId,regDay)
                VALUES(?,?,'HQ',?,'TRX_TRANSFER',LEFT(?,32),?,?,?,?,?,
                       DATE_FORMAT(CURRENT_DATE(),'%Y%m%d'))
                """, IDUtils.getRandom(32), resultMap.getString("mchtId"), action, resultMap.getString("trxId"),
                WxPostingRps.field(inMap, "reason", 1024),
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("status", resultMap.getString("beforeStatus"))),
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("outcome", outcome)
                        .add("status", resultMap.getString("status"))
                        .add("changed", resultMap.getBoolean("changed"))
                        .add("responseCode", inMap.getString("responseCode"))),
                inMap.getString("clientIp"), actor);
        logger.info("송금 운영자 종결 기록: trxId={}, outcome={}, changed={}, actor={}",
                resultMap.getString("trxId"), outcome, resultMap.getBoolean("changed"), actor);
    }

    /** 확정 후 Wallet 잔액을 응답에 담는다. 고객사 응답 계약의 잔액 필드로 사용한다. */
    private static void balance(Connection db, SharedMap<String, Object> resultMap) {

        if (resultMap.isBlank("walletId")) return;
        try {
            List<SharedMap<String, Object>> foundList = rows(db, "SELECT balance,available FROM WX.WALLET_BALANCE WHERE walletId=?",
                    resultMap.getString("walletId"));
            if (foundList.isEmpty()) return;
            resultMap
                    .add("balance", foundList.get(0).getLong("balance"))
                    .add("availableBalance", foundList.get(0).getLong("available"));
        } catch (SQLException failure) {
            // 잔액 조회 실패는 이미 확정된 거래 결과를 바꾸지 않는다. 응답에서 잔액만 생략한다.
            logger.debug("확정 후 잔액 조회 실패: walletId={}", resultMap.getString("walletId"), failure);
        }
    }

    /** 성공 확정: 모계좌의 실제 출금 확정 원장을 기록한다. */
    private static void hqLedger(Connection db, SharedMap<String, Object> transferMap, long before, String acqTrxId,
                                 String actor, LocalDateTime postedAt) throws SQLException {

        String hqId   = transferMap.getString("hqId");
        long   amount = transferMap.getLong("amount");
        int    day    = WxPostingRps.businessDay(postedAt);
        update(db, """
                INSERT INTO WX.TRX_HQ(id,hqId,trackId,status,trxType,dtlType,refType,refId,amount,balance,balanceAfter,
                                      bankCd,bankName,holder,accntNo,sender,acqTrxId,trxDay,trxDate,regId,regDay)
                VALUES(?,?,?,'성공','출금','일반출금','TRX_TRANSFER',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, IDUtils.getUnique("H"), hqId, transferMap.getString("trackId"), transferMap.getString("trxId"),
                Math.negateExact(amount), before, Math.subtractExact(before, amount),
                transferMap.getString("bankCd"), transferMap.getString("bankName"), transferMap.getString("holder"),
                WxMerchantRps.maskedAccount(transferMap.getString("accntNo")), transferMap.getString("sender"),
                acqTrxId, day, postedAt, actor, day);
        logger.debug("모계좌 출금 원장: hqId={}, before={}, amount={}", hqId, before, amount);
    }

    /** 실패 확정: 모계좌 송금 예약만 해제한다. 장부잔액은 바뀌지 않는다. */
    private static void releaseBank(Connection db, String hqId, long amount) throws SQLException {

        if (update(db, "UPDATE WX.HQ_ACCNT SET reservedAmt=GREATEST(0,reservedAmt-?) WHERE id=?", amount, hqId) != 1) {
            throw new SQLException("모계좌 송금 예약 해제에 실패했습니다.");
        }
        logger.debug("모계좌 송금 예약 해제: hqId={}, amount={}", hqId, amount);
    }

    /** 결과가 확정된 요청은 멱등 키를 돌려주어 같은 요청번호의 재사용 여부를 유니크 제약이 판정하게 한다. */
    private static void clearRequest(SharedMap<String, Object> resultMap) {

        if (resultMap.isBlank("mchtId") || resultMap.isBlank("trackId")
                || (!resultMap.isEquals("status", "성공") && !resultMap.isEquals("status", "실패"))) {
            return;
        }
        try {
            RedisUtils.del(LedgerKeys.transferRequest(resultMap.getString("mchtId"), resultMap.getString("trackId")));
        } catch (RuntimeException unavailable) {
            logger.info("확정된 송금의 멱등 키 삭제 실패, 기존 만료에 맡긴다: trxId={}, type={}",
                    resultMap.getString("trxId"), unavailable.getClass().getSimpleName());
        }
    }

    /** 중복 확정 생략 조건 없이 모계좌를 먼저 잠그고 차감 전 장부잔액을 반환한다. */
    private static long lockTransferBank(Connection db, String trxId) throws SQLException {

        List<SharedMap<String, Object>> bankList = lockTransferBankRows(db, trxId, false);
        if (!bankList.isEmpty()) return bankList.get(0).getLong("balance");

        // 조회에 실패한 경우에만 거래가 없는지, 연결된 모계좌가 없는지 구분한다.
        List<SharedMap<String, Object>> ownerList = rows(db, "SELECT hqId FROM WX.TRX_TRANSFER WHERE trxId=?", trxId);
        if (ownerList.isEmpty()) throw new LedgerEx(LedgerCode.NOT_FOUND, "송금 거래를 찾을 수 없습니다.");
        throw new LedgerEx(LedgerCode.NOT_FOUND, "모계좌를 찾을 수 없습니다.");
    }

    /** 하위 조회는 송금의 모계좌 ID를 읽고 바깥 조회에서 모계좌 행만 잠근다. */
    private static List<SharedMap<String, Object>> lockTransferBankRows(Connection db, String trxId,
                                                                      boolean skipFinalized) throws SQLException {

        String statusCondition = skipFinalized ? " AND (status IS NULL OR status NOT IN ('성공','실패'))" : "";
        return rows(db, new Retrieve("WX.HQ_ACCNT")
                .columns("balance")
                .where("id=(SELECT hqId FROM WX.TRX_TRANSFER WHERE trxId=?" + statusCondition + ")")
                .forUpdate().build(), trxId);
    }

    /** 확정 트랜잭션이 깨진 경우 자체 연결로 미확정 주차만 수행한다. */
    private static void parkAfterFailure(String trxId, SharedMap<String, Object> inMap) {

        int changed;
        try (Connection db = WxDatabaseRps.open()) {
            db.setAutoCommit(false);
            lockTransferBank(db, trxId);
            changed = update(db, "UPDATE WX.TRX_TRANSFER SET status='미확인',responseCode=?,udtId=?"
                    + " WHERE trxId=? AND status='처리중' AND bankTrxId REGEXP '^[0-9]{12}$'",
                    inMap.getString("responseCode"),
                    inMap.isBlank("actor") ? "WX_API" : inMap.getString("actor"), trxId);
            if (changed == 1) {
                update(db, "UPDATE WX.TRX_WALLET SET status='미확인',resultMsg='통신장애, 결과 확인 중'"
                        + " WHERE refType='TRX_TRANSFER' AND refId=? AND status='처리중'", trxId);
            }
            db.commit();
        } catch (Exception failure) {
            // 여기까지 실패하면 자동 해소 경로가 없다. 운영자가 처리 이력으로 확인해야 한다.
            logger.error("송금 미확정 주차 실패, 수동 확인 필요: trxId={}", trxId, failure);
            return;
        }
        if (changed == 1) enqueueRecovery(trxId);
    }

    /** 커밋한 미확정 송금을 등록한다. 누락된 등록은 주기 조회로 복구한다. */
    private static void enqueueRecovery(String trxId) {

        try {
            RedisText.sync().zadd(LedgerKeys.TRANSFER_RECOVERY_QUEUE, new ZAddArgs().nx(),
                    System.currentTimeMillis(), trxId);
        } catch (RuntimeException failure) {
            logger.warn("송금 복구 큐 등록 실패: trxId={}, type={}", trxId, failure.getClass().getSimpleName());
        }
    }

    /** 복구 처리 시각이 된 거래를 Redis에서 읽고 DB에서 거래번호로 선점한다. */
    public static List<SharedMap<String, Object>> pending(int limit) throws SQLException {

        int                             wanted  = Math.min(Math.max(limit, 1), 10);
        List<SharedMap<String, Object>> outList = new ArrayList<>();
        if (RedisTextUtils.setNx(LedgerKeys.TRANSFER_RECOVERY_SCAN, "1", RECOVERY_SCAN_SECONDS)) {
            try (Connection db = WxDatabaseRps.open()) {
                replenishRecovery(db);
            } catch (SQLException | RuntimeException failure) {
                logger.warn("송금 복구 큐 누락 확인 실패: type={}", failure.getClass().getSimpleName());
            }
        }

        List<String> idsList = RedisText.sync().zrangebyscore(LedgerKeys.TRANSFER_RECOVERY_QUEUE,
                Range.create(0L, System.currentTimeMillis()), Limit.create(0, wanted * 4L));
        if (idsList.isEmpty()) return outList;

        try (Connection db = WxDatabaseRps.open()) {
            db.setAutoCommit(true);
            for (String trxId : idsList) {
                SharedMap<String, Object> rowMap = claimRecovery(db, trxId);
                if (rowMap != null) outList.add(rowMap);
                if (outList.size() == wanted) break;
            }
        }
        return outList;
    }

    /** Redis 대기 목록에 없는 DB 후보만 추가한다. 전송 전 접수는 지난 날짜도 확인한다. */
    private static void replenishRecovery(Connection db) throws SQLException {

        int    lastDay = 0;
        String lastId  = "";
        while (true) {
            List<SharedMap<String, Object>> candidateList = rows(db, """
                    SELECT t.trxId,t.regDay,
                           CAST(UNIX_TIMESTAMP(GREATEST(t.regDate,COALESCE(t.recoveryCheckedAt,t.regDate)))*1000 AS SIGNED)
                           +? AS dueMs
                      FROM WX.TRX_TRANSFER t JOIN WX.HQ_ACCNT h ON h.id=t.hqId
                     WHERE t.status IN ('접수','미확인','처리중')
                       AND t.regDay BETWEEN ? AND DATE_FORMAT(CURRENT_DATE(),'%Y%m%d')+0
                       AND (t.regDay>? OR t.trxId>?)
                       AND (t.status='접수' OR t.regDay>=DATE_FORMAT(NOW()-INTERVAL ? HOUR,'%Y%m%d')+0)
                       AND
                    """ + RECOVERABLE + " ORDER BY t.regDay,t.trxId LIMIT ?",
                    RECOVERY_DELAY_MS, lastDay, lastDay, lastId, RECOVERY_HOURS, RECOVERY_HOURS, RECOVERY_SCAN_BATCH);
            if (candidateList.isEmpty()) return;

            List<ScoredValue<String>> entriesList = new ArrayList<>(candidateList.size());
            for (SharedMap<String, Object> candidateMap : candidateList) {
                entriesList.add(ScoredValue.just(candidateMap.getLong("dueMs"), candidateMap.getString("trxId")));
            }
            @SuppressWarnings("unchecked")
            ScoredValue<String>[] entries = entriesList.toArray(ScoredValue[]::new);
            RedisText.sync().zadd(LedgerKeys.TRANSFER_RECOVERY_QUEUE, new ZAddArgs().nx(), entries);
            if (candidateList.size() < RECOVERY_SCAN_BATCH) return;

            SharedMap<String, Object> lastMap = candidateList.get(candidateList.size() - 1);
            lastDay = lastMap.getInt("regDay");
            lastId  = lastMap.getString("trxId");
        }
    }

    /** 원장의 선점 조건을 만족한 호출만 처리하도록 하고 큐에는 다음 재시도 시각을 남긴다. */
    private static SharedMap<String, Object> claimRecovery(Connection db, String trxId) throws SQLException {

        List<SharedMap<String, Object>> candidateList = rows(db, """
                SELECT t.status,t.bankTrxId,t.hqId,h.acq,h.acqId,h.bankCd acqBankCd,
                       h.accntNo acqAccount,COALESCE(h.key2,'') acqKey2,
                       CAST(UNIX_TIMESTAMP(GREATEST(t.regDate,COALESCE(t.recoveryCheckedAt,t.regDate)))*1000 AS SIGNED)
                       +? AS dueMs,
                       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED) AS nowMs
                  FROM WX.TRX_TRANSFER t JOIN WX.HQ_ACCNT h ON h.id=t.hqId
                 WHERE t.trxId=? AND
                """ + RECOVERABLE, RECOVERY_DELAY_MS, trxId, RECOVERY_HOURS);
        if (candidateList.isEmpty()) {
            RedisText.sync().zrem(LedgerKeys.TRANSFER_RECOVERY_QUEUE, trxId);
            return null;
        }
        SharedMap<String, Object> rowMap = candidateList.get(0);
        long due = rowMap.getLong("dueMs");
        long now = rowMap.getLong("nowMs");
        if (due > now) {
            scheduleRecovery(trxId, due);
            return null;
        }

        int claimed = update(db, """
                UPDATE WX.TRX_TRANSFER t SET recoveryCheckedAt=CURRENT_TIMESTAMP(3)
                 WHERE t.trxId=? AND t.status=? AND COALESCE(t.bankTrxId,'')=?
                   AND t.regDate<=NOW()-INTERVAL 2 MINUTE
                   AND (t.recoveryCheckedAt IS NULL OR t.recoveryCheckedAt<=NOW()-INTERVAL 2 MINUTE)
                   AND
                """ + RECOVERABLE, trxId, rowMap.getString("status"), rowMap.getString("bankTrxId"), RECOVERY_HOURS);
        if (claimed != 1) return null;

        scheduleRecovery(trxId, now + RECOVERY_DELAY_MS);
        if (rowMap.isEquals("status", "접수")) {
            return SharedMap.<String, Object>of()
                    .add("trxId", trxId)
                    .add("status", "접수");
        }
        SharedMap<String, Object> profileMap = upstream(rowMap.getString("hqId"), rowMap);
        if (profileMap == null) return null;
        return SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("bankTrxId", rowMap.getString("bankTrxId"))
                .add("upstream", profileMap);
    }

    /** 앞선 호출이 기록한 더 늦은 재시도 예약을 유지한다. */
    private static void scheduleRecovery(String trxId, long due) {

        try {
            RedisText.sync().zadd(LedgerKeys.TRANSFER_RECOVERY_QUEUE, new ZAddArgs().gt(), due, trxId);
        } catch (RuntimeException failure) {
            logger.warn("송금 복구 재시도 예약 실패: trxId={}, type={}", trxId, failure.getClass().getSimpleName());
        }
    }
}
