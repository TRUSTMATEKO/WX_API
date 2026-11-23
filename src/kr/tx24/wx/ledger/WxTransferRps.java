package kr.tx24.wx.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.tx24.lib.kms.KMSUtils;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.lib.redis.RedisUtils;

import static kr.tx24.wx.ledger.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.WxDatabaseRps.update;

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

    /**
     * 한 주기에 장애 큐에서 훑어보는 최대 항목 수.
     *
     * <p>운영자가 종결한 건은 큐에 항목이 남는다. {@code recoverable}이 {@code 미확인}이 아닌 건을
     * 걸러 내므로 기관에 다시 묻지는 않지만, 그 죽은 항목이 처리 건수를 차지하면 실제 재조회 대상이
     * 뒤로 밀린다. 그래서 담는 건수와 훑는 횟수를 따로 세고 훑는 쪽에 상한을 둔다.</p>
     */
    private static final int DRAIN_LIMIT = 200;

    /** 송금 요청 멱등 키 보관 시간. OPL과 같은 10분을 사용한다. */
    private static final long REQUEST_TTL = 10 * 60;

    private WxTransferRps() {

    }

    /**
     * 송금 접수. 한도와 잔액을 확인하고 출금 분개를 대기로 기록해 장부잔액을 즉시 차감한다.
     *
     * <p>호출자는 업무 트랜잭션을 열고 접수 중지시간 정책을 먼저 확인한 상태여야 한다.
     * 반환한 결과로 기관 전문을 보내고 {@link #confirm}으로 상태를 확정한다.</p>
     */
    static SharedMap<String, Object> accept(Connection db, SharedMap<String, Object> in, String company,
                                            String walletId, LocalDateTime now) throws SQLException {

        String track   = WxPostingRps.field(in, "trackId", 128);
        String bankCd  = WxPostingRps.field(in, "bankCd", 12);
        String accntNo = WxPostingRps.field(in, "accntNo", 32);
        long   amount  = in.getLong("amount");
        if (!bankCd.matches("[0-9]{3}") || !accntNo.matches("[0-9]{1,20}") || amount < 1) {
            throw new LedgerEx(LedgerCode.INVALID, "송금 요청값을 확인해 주세요.");
        }
        int day = WxPostingRps.businessDay(now);

        // 같은 고객사의 같은 요청번호는 한 건만 접수한다. 유니크 제약은 2차 방어선으로 둔다.
        String idempotent = LedgerKeys.transferRequest(company, track);
        if (!RedisUtils.setNx(idempotent, now.toString(), REQUEST_TTL)) {
            logger.info("송금 중복 요청 차단: mchtId={}, trackId={}", company, track);
            throw new LedgerEx(LedgerCode.CONFLICT, "같은 요청번호가 처리 중입니다. 원거래 조회로 결과를 확인해 주세요.");
        }
        try {
            return register(db, in, company, walletId, track, bankCd, accntNo, amount, day, now);
        } catch (RuntimeException | SQLException failure) {
            // 접수에 이르지 못한 요청은 즉시 재시도할 수 있도록 멱등 키를 돌려준다.
            RedisUtils.del(idempotent);
            throw failure;
        }
    }

    /** 접수 본문. 잔액과 한도를 확인하고 미확정 원장을 만든다. */
    private static SharedMap<String, Object> register(Connection db, SharedMap<String, Object> in, String company,
                                                      String walletId, String track, String bankCd, String accntNo,
                                                      long amount, int day, LocalDateTime now) throws SQLException {

        List<SharedMap<String, Object>> exists = rows(db, "SELECT trxId,status FROM WX.TRX_TRANSFER WHERE mchtId=? AND trackId=?", company, track);
        if (!exists.isEmpty()) {
            logger.info("송금 요청번호 중복: mchtId={}, trackId={}, trxId={}, status={}", company, track,
                    exists.get(0).getString("trxId"), exists.get(0).getString("status"));
            throw new LedgerEx(LedgerCode.CONFLICT, "이미 접수된 요청번호입니다. 원거래 조회로 결과를 확인해 주세요.");
        }

        List<SharedMap<String, Object>> wallet = rows(db, """
                SELECT w.id,w.mchtId,w.hqId,w.sender,w.limitOnce,w.limitDay,w.limitMonth,w.minWithdrawAmt,
                       b.balance,b.held,b.available
                  FROM WX.WALLET w JOIN WX.WALLET_BALANCE b ON b.walletId=w.id
                 WHERE w.id=? AND w.mchtId=? AND w.ownerType='MCHT' AND w.status='사용' AND w.apiStatus='사용'
                 FOR UPDATE
                """, walletId, company);
        if (wallet.size() != 1) throw new LedgerEx(LedgerCode.CONFLICT, "Wallet 사용 상태를 확인해 주세요.");
        SharedMap<String, Object> account = wallet.get(0);

        SharedMap<String, Object> rate = fee(db, company, day, amount);
        long charge = rate.getLong("feeAmt");
        logger.debug("송금 수수료 산정: mchtId={}, amount={}, feeAmt={}, feeRateIdx={}",
                company, amount, charge, rate.getString("feeRateIdx"));

        checkLimit(db, account, amount, day);

        long available = account.getLong("available");
        if (available < amount) {
            logger.info("송금 잔액 부족: walletId={}, amount={}, available={}", walletId, amount, available);
            throw new LedgerEx(LedgerCode.CONFLICT, "출금가능잔액이 부족합니다.");
        }

        String hqId = account.getString("hqId");
        List<SharedMap<String, Object>> bank = rows(db, """
                SELECT id,balance,reservedAmt,balance-reservedAmt AS payable,alarmBalance,
                       acq,acqId,bankCd AS acqBankCd,accntNo AS acqAccount,COALESCE(key2,'') AS acqKey2
                  FROM WX.HQ_ACCNT WHERE id=? AND status='사용' FOR UPDATE
                """, hqId);
        // 문구에 모계좌를 드러내지 않는다. 고객사가 알 일이 아니고 사유는 원장 로그에 남는다.
        if (bank.size() != 1) {
            logger.warn("모계좌 사용 상태가 아니어서 송금을 접수하지 않는다: hqId={}", hqId);
            throw new LedgerEx(LedgerCode.CONFLICT, "송금을 접수할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (bank.get(0).getLong("payable") < amount) {
            logger.warn("모계좌 송금 가능액 부족: hqId={}, amount={}, payable={}",
                    hqId, amount, bank.get(0).getLong("payable"));
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "송금 접수가 일시 중지되었습니다. 잠시 후 다시 시도해 주세요.");
        }

        // 기관 연동 설정은 차감 전에 확인한다. 보낼 수 없는 요청을 접수하면 잔액만 묶인다.
        SharedMap<String, Object> upstream = upstream(hqId, bank.get(0));
        if (upstream == null) {
            logger.warn("모계좌 기관 연동 설정이 없어 송금을 접수하지 않는다: hqId={}, acq={}",
                    hqId, bank.get(0).getString("acq"));
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "기관 연동 설정이 없어 송금 접수가 중지되었습니다.");
        }

        String trxId  = IDUtils.getUnique("S");
        String sender = in.isBlank("sender") ? account.getString("sender") : WxPostingRps.field(in, "sender", 20);
        String holder = in.getString("holder");
        // 수취 계좌번호는 평문으로 받는다. 저장은 마스킹과 암호문으로만 하고 암호 키는 WX_ADM 에만 둔다.
        String masked = WxMerchantRps.maskedAccount(accntNo);
        String encrypted = KMSUtils.encrypt(accntNo);
        String bankName = rows(db, "SELECT COALESCE(NULLIF(nickKr,''),nameKr) AS name FROM WX.BANK WHERE code=?", bankCd)
                .stream().findFirst().map(row -> row.getString("name")).orElse("");

        update(db, """
                INSERT INTO WX.TRX_TRANSFER(trxId,mchtId,walletId,hqId,ioId,sender,trackId,requestHash,amount,
                                            bankCd,bankName,accntNo,accntNoEnc,holder,status,feeAmt,feeRateIdx,
                                            feeTarget,regId,regDay,regDate)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'접수',?,?,0,'WX_API',?,?)
                """, trxId, company, walletId, hqId, in.getString("ioId"), sender, track,
                in.getString("requestHash"), amount, bankCd, bankName, masked, encrypted,
                holder, charge, rate.get("feeRateIdx"), day, now);

        // 출금 분개를 대기로 기록한다. 트리거가 장부잔액을 즉시 차감한다.
        String ledgerId = withdraw(db, account, trxId, track, amount, charge, bankCd, bankName, holder, masked,
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
                .add("upstream", upstream);
    }

    /**
     * 모계좌에 등록된 기관 연동 설정을 WX_API가 사용할 전송 프로필로 만든다.
     *
     * <p>중개사·식별자·모계좌·보호키 중 하나라도 없으면 전문을 보낼 수 없으므로 {@code null}을 돌려준다.
     * 접수에서는 차감 전에 이를 확인해 거절하고, 재조회에서는 운영자 확인 대상으로 남긴다.</p>
     */
    static SharedMap<String, Object> upstream(String hqId, SharedMap<String, Object> bank) {

        if (!"HYPHEN".equals(bank.getString("acq")) || bank.isBlank("acqId") || bank.isBlank("acqBankCd")
                || bank.isBlank("acqAccount") || bank.isBlank("acqKey2")) {
            logger.debug("모계좌 기관 연동 설정 미완성: hqId={}, acq={}, acqId={}",
                    hqId, bank.getString("acq"), bank.getString("acqId"));
            return null;
        }
        // 모계좌 ID 는 기관 거래 일련번호를 모계좌별로 나누는 키에 쓴다. 보호값이 아니다.
        return SharedMap.<String, Object>of()
                .add("hqId", hqId)
                .add("acq", bank.getString("acq"))
                .add("acqSid", bank.getString("acqId"))
                .add("acqBankCd", bank.getString("acqBankCd"))
                .add("acqAccount", bank.getString("acqAccount"))
                .add("acqKey2", bank.getString("acqKey2"))
                // 기관이 지급 전문에 돌려주는 모계좌 잔액의 경고 기준이다. 보호값이 아니다.
                .add("alarmBalance", bank.getLong("alarmBalance", 0L));
    }

    /** 접수: 고객 Wallet의 출금 분개를 대기로 기록한다. 장부잔액은 트리거가 차감한다. */
    private static String withdraw(Connection db, SharedMap<String, Object> account, String trxId, String track,
                                    long amount, long charge, String bankCd, String bankName, String holder,
                                    String accntNo, String sender, LocalDateTime now) throws SQLException {

        String walletId = account.getString("id");
        long   before   = account.getLong("balance");
        long   after    = Math.subtractExact(before, amount);
        long   sequence = rows(db, "SELECT COALESCE(MAX(`sequence`),0)+1 AS nextSequence FROM WX.TRX_WALLET WHERE walletId=?",
                walletId).get(0).getLong("nextSequence");
        String id  = IDUtils.getUnique("T");
        int    day = WxPostingRps.businessDay(now);
        update(db, """
                INSERT INTO WX.TRX_WALLET(id,ownerType,mchtId,walletId,hqId,trackId,refType,refId,refSeq,`sequence`,
                                          trxType,dtlType,status,amount,depositAmt,withdrawnAmt,fee,balance,balanceAfter,
                                          bankCd,bankName,holder,accntNo,sender,trxDay,trxDate,regId,regDay)
                VALUES(?,'MCHT',?,?,?,?,'TRX_TRANSFER',?,1,?,'출금','일반출금','대기',?,0,?,?,?,?,?,?,?,?,?,?,?,'WX_API',?)
                """, id, account.getString("mchtId"), walletId, account.getString("hqId"), track, trxId, sequence,
                Math.negateExact(amount), amount, charge, before, after, bankCd, bankName, holder, accntNo, sender,
                day, now, day);
        logger.debug("고객 Wallet 출금 선차감 분개: walletId={}, before={}, after={}, sequence={}",
                walletId, before, after, sequence);
        return id;
    }

    /** 과금 기준일에 적용할 수수료 단가를 조회한다. 마감 검증과 같은 식으로 계산한다. */
    private static SharedMap<String, Object> fee(Connection db, String company, int day, long amount) throws SQLException {

        List<SharedMap<String, Object>> applied = rows(db, """
                SELECT idx,transferUnitAmt,transferPercentRate,
                       transferUnitAmt+ROUND(?*transferPercentRate/100,0) AS feeAmt
                  FROM WX.MCHT_FEE WHERE mchtId=? AND applyDay<=? AND status='사용'
                 ORDER BY applyDay DESC LIMIT 1
                """, amount, company, day);
        if (applied.isEmpty()) {
            // 계약이 없으면 과금하지 않는다. 마감 검증은 요율 ID가 없고 단가가 0인 건을 정상으로 본다.
            logger.debug("적용 수수료 계약 없음: mchtId={}, baseDay={}", company, day);
            return SharedMap.<String, Object>of().add("feeAmt", 0L).add("feeRateIdx", null);
        }
        return SharedMap.<String, Object>of()
                .add("feeAmt", applied.get(0).getLong("feeAmt"))
                .add("feeRateIdx", applied.get(0).getLong("idx"));
    }

    /** 건당·일·월 한도와 최소 출금액을 확인한다. 0은 무제한으로 본다. */
    private static void checkLimit(Connection db, SharedMap<String, Object> account, long amount, int day)
            throws SQLException {

        long minimum = account.getLong("minWithdrawAmt");
        if (minimum > 0 && amount < minimum) throw new LedgerEx(LedgerCode.INVALID, "최소 출금금액 미만입니다.");

        long once = account.getLong("limitOnce");
        if (once > 0 && amount > once) {
            logger.info("송금 건당 한도 초과: walletId={}, amount={}, limitOnce={}", account.getString("id"), amount, once);
            throw new LedgerEx(LedgerCode.CONFLICT, "건당 출금한도를 초과했습니다.");
        }
        long dayLimit   = account.getLong("limitDay");
        long monthLimit = account.getLong("limitMonth");
        if (dayLimit <= 0 && monthLimit <= 0) return;

        // 실패로 확정된 건은 한도에서 제외하고 미확정과 성공만 센다.
        SharedMap<String, Object> used = rows(db, """
                SELECT COALESCE(SUM(CASE WHEN regDay=? THEN amount ELSE 0 END),0) AS dayAmt,
                       COALESCE(SUM(amount),0) AS monthAmt
                  FROM WX.TRX_TRANSFER
                 WHERE walletId=? AND regDay DIV 100=? AND status<>'실패'
                """, day, account.getString("id"), day / 100).get(0);
        if (dayLimit > 0 && used.getLong("dayAmt") + amount > dayLimit) {
            logger.info("송금 일 한도 초과: walletId={}, used={}, amount={}, limitDay={}",
                    account.getString("id"), used.getLong("dayAmt"), amount, dayLimit);
            throw new LedgerEx(LedgerCode.CONFLICT, "일 출금한도를 초과했습니다.");
        }
        if (monthLimit > 0 && used.getLong("monthAmt") + amount > monthLimit) {
            logger.info("송금 월 한도 초과: walletId={}, used={}, amount={}, limitMonth={}",
                    account.getString("id"), used.getLong("monthAmt"), amount, monthLimit);
            throw new LedgerEx(LedgerCode.CONFLICT, "월 출금한도를 초과했습니다.");
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
                    + " WHERE trxId=? AND status='접수'", acqTrxId, trxId);
            if (changed != 1) {
                logger.warn("기관 원거래번호 선기록 실패: trxId={}, changed={}", trxId, changed);
                throw new SQLException("기관 원거래번호 선기록에 실패했습니다.");
            }
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
    public static SharedMap<String, Object> confirm(SharedMap<String, Object> in) throws SQLException {

        String trxId   = WxPostingRps.field(in, "trxId", 32);
        String outcome = WxPostingRps.field(in, "outcome", 12);
        if (!Set.of("성공", "실패", "미확인").contains(outcome)) {
            throw new LedgerEx(LedgerCode.INVALID, "확정 결과를 확인해 주세요.");
        }
        try (Connection db = WxDatabaseRps.open()) {
            db.setAutoCommit(false);
            try {
                SharedMap<String, Object> result = settle(db, trxId, outcome, in);
                // 운영자 종결은 근거를 같은 트랜잭션에 남긴다. 기관 응답 확정에는 근거가 없다.
                if (!in.isBlank("reason")) audit(db, in, result, outcome);
                db.commit();
                // 커밋 뒤에 웹훅 발송 큐에 올린다. WX_TASK 가 집어 TX24_EXT 로 보낸다.
                // 기관 응답 확정과 통신장애 재조회 확정이 같은 이 경로를 지난다.
                WxWebhookRps.publish(result);
                balance(db, result);
                if ("미확인".equals(outcome) && result.getBoolean("changed")) park(trxId, "결과 미확정");
                clearRequest(result);
                return result;
            } catch (Exception failure) {
                db.rollback();
                // 기관에 전문이 나간 뒤의 확정 실패는 실패로 단정할 수 없다. 반드시 미확정으로 주차한다.
                logger.error("송금 확정 처리 실패, 미확정으로 주차: trxId={}, outcome={}", trxId, outcome, failure);
                parkAfterFailure(trxId, in);
                throw failure;
            }
        }
    }

    /** 확정 처리 본문. 호출자가 업무 트랜잭션을 관리한다. */
    private static SharedMap<String, Object> settle(Connection db, String trxId, String outcome,
                                                     SharedMap<String, Object> in) throws SQLException {

        List<SharedMap<String, Object>> found = rows(db, """
                SELECT t.trxId,t.mchtId,t.walletId,t.hqId,t.amount,t.feeAmt,t.status,t.sender,t.holder,t.trackId,
                       t.bankCd,t.bankName,t.accntNo,t.bankTrxId,t.walletTrxId
                  FROM WX.TRX_TRANSFER t WHERE t.trxId=? FOR UPDATE
                """, trxId);
        if (found.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "송금 거래를 찾을 수 없습니다.");
        SharedMap<String, Object> transfer = found.get(0);
        if (!OPEN.contains(transfer.getString("status"))) {
            logger.info("이미 확정된 송금: trxId={}, status={}", trxId, transfer.getString("status"));
            return SharedMap.<String, Object>of().add("trxId", trxId).add("status", transfer.getString("status"))
                    .add("beforeStatus", transfer.getString("status"))
                    .add("mchtId", transfer.getString("mchtId")).add("trackId", transfer.getString("trackId"))
                    .add("walletId", transfer.getString("walletId")).add("changed", false);
        }

        String acqTrxId   = in.getString("acqTrxId", transfer.getString("bankTrxId"));
        String resultCode = in.getString("responseCode");
        // 확정 작업자. 기관 응답으로 확정하면 WX_API, 운영자가 종결하면 그 계정이다.
        String actor      = in.isBlank("actor") ? "WX_API" : WxPostingRps.field(in, "actor", 24);
        long   amount     = transfer.getLong("amount");
        String walletId   = transfer.getString("walletId");
        String hqId       = transfer.getString("hqId");
        String ledgerId   = transfer.getString("walletTrxId");
        SharedMap<String, Object> result     = SharedMap.<String, Object>of().add("trxId", trxId).add("changed", true)
                .add("walletId", walletId).add("beforeStatus", transfer.getString("status"))
                .add("mchtId", transfer.getString("mchtId")).add("trackId", transfer.getString("trackId"));

        if ("미확인".equals(outcome)) {
            // 차감을 유지한다. 원장은 장애로 두고 모계좌 예약도 풀지 않는다.
            update(db, "UPDATE WX.TRX_WALLET SET status='장애',resultCd=?,resultMsg='통신장애, 결과 확인 중',"
                    + "acqTrxId=?,acqResultCd=?,acqResultMsg=?,acqTrxDate=? WHERE id=? AND status='대기'",
                    resultCode, acqTrxId, resultCode, in.getString("responseMsg"),
                    in.getString("acqTrxDate"), ledgerId);
            update(db, "UPDATE WX.TRX_TRANSFER SET status='미확인',responseCode=?,bankTrxId=?,udtId=?"
                    + " WHERE trxId=?", resultCode, acqTrxId, actor, trxId);
            logger.info("송금 미확정 주차: trxId={}, amount={}, acqTrxId={}, 차감 유지", trxId, amount, acqTrxId);
            return result.add("status", "미확인");
        }

        if ("실패".equals(outcome)) {
            // 원장을 실패로 바꾸면 트리거가 차감한 금액을 원복한다.
            if (update(db, "UPDATE WX.TRX_WALLET SET status='실패',resultCd=?,resultMsg='이체 실패 확정',"
                    + "acqTrxId=?,acqResultCd=?,acqResultMsg=?,acqTrxDate=? WHERE id=? AND status IN ('대기','장애')",
                    resultCode, acqTrxId, resultCode, in.getString("responseMsg"),
                    in.getString("acqTrxDate"), ledgerId) != 1) {
                throw new SQLException("송금 원장 실패 확정에 실패했습니다.");
            }
            update(db, """
                    UPDATE WX.TRX_TRANSFER SET status='실패',responseCode=?,bankTrxId=?,feeTarget=0,
                           udtId=?,udtDate=NOW() WHERE trxId=?
                    """, resultCode, acqTrxId, actor, trxId);
            releaseBank(db, hqId, amount);
            logger.info("송금 실패 확정과 잔액 원복: trxId={}, walletId={}, amount={}, responseCode={}",
                    trxId, walletId, amount, resultCode);
            result.add("status", "실패").add("amount", amount).add("acqTrxId", acqTrxId);
            // 확정과 통지를 한 트랜잭션에 둔다. 확정만 되고 통지가 빠지는 상태를 만들지 않는다.
            WxWebhookRps.enqueue(db, result, WxWebhookRps.TRANSFER_SETTLED, WxPostingRps.postingTime(db));
            return result;
        }

        LocalDateTime postedAt = WxPostingRps.postingTime(db);
        if (update(db, "UPDATE WX.TRX_WALLET SET status='성공',resultCd='0000',resultMsg='정상이체',"
                + "acqTrxId=?,acqResultCd=?,acqResultMsg=?,acqTrxDate=? WHERE id=? AND status IN ('대기','장애')",
                acqTrxId, resultCode, in.getString("responseMsg"), in.getString("acqTrxDate"), ledgerId) != 1) {
            throw new SQLException("송금 원장 성공 확정에 실패했습니다.");
        }
        // 실제 지급이므로 모계좌 장부잔액을 줄이고 송금 예약을 해제한다.
        //
        // 지급이 끝나면 고객 Wallet과 모계좌에서 각각 원금만큼 줄고 미배분 입금은 그대로 남는다.
        // 본사 Wallet까지 줄이면 같은 금액을 두 번 뺀다.
        // 수수료 정산처럼 Wallet 사이에서 자금이 옮겨 가는 처리에서만 본사 Wallet에 분개한다.
        hqLedger(db, transfer, acqTrxId, actor, postedAt);
        if (update(db, "UPDATE WX.HQ_ACCNT SET balance=balance-?,reservedAmt=GREATEST(0,reservedAmt-?) WHERE id=?",
                amount, amount, hqId) != 1) {
            throw new SQLException("모계좌 장부잔액 반영에 실패했습니다.");
        }
        update(db, """
                UPDATE WX.TRX_TRANSFER SET status='성공',responseCode=?,bankTrxId=?,
                       feeTarget=CASE WHEN feeAmt>0 THEN 1 ELSE 0 END,udtId=?,udtDate=NOW()
                 WHERE trxId=?
                """, resultCode, acqTrxId, actor, trxId);
        logger.info("송금 성공 확정: trxId={}, walletId={}, amount={}, walletTrxId={}, acqTrxId={}",
                trxId, walletId, amount, ledgerId, acqTrxId);
        result.add("status", "성공").add("walletTrxId", ledgerId).add("amount", amount).add("acqTrxId", acqTrxId);
        // 확정과 통지를 한 트랜잭션에 둔다. 확정만 되고 통지가 빠지는 상태를 만들지 않는다.
        WxWebhookRps.enqueue(db, result, WxWebhookRps.TRANSFER_SETTLED, postedAt);
        return result;
    }

    /**
     * 운영자 종결의 근거를 감사 이력에 남긴다.
     *
     * <p>확정과 같은 트랜잭션에 쓴다. 근거 없이 상태만 바뀐 건이 남지 않아야 한다.
     * 이미 확정된 건을 다시 종결하려 한 경우({@code changed=false})도 시도 자체를 기록한다.</p>
     */
    private static void audit(Connection db, SharedMap<String, Object> in, SharedMap<String, Object> result,
                               String outcome) throws SQLException {

        String actor = WxPostingRps.field(in, "actor", 24);
        update(db, """
                INSERT INTO WX.AUDIT(id,mchtId,unit,action,refType,refId,reason,beforeJson,afterJson,accessIp,
                                     regId,regDay)
                VALUES(?,?,'HQ','TRANSFER_SETTLE','TRX_TRANSFER',LEFT(?,32),?,?,?,?,?,
                       DATE_FORMAT(CURRENT_DATE(),'%Y%m%d'))
                """, IDUtils.getRandom(32), result.getString("mchtId"), result.getString("trxId"),
                WxPostingRps.field(in, "reason", 1024),
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("status", result.getString("beforeStatus"))),
                JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                        .add("outcome", outcome)
                        .add("status", result.getString("status"))
                        .add("changed", result.getBoolean("changed"))
                        .add("responseCode", in.getString("responseCode"))),
                in.getString("clientIp"), actor);
        logger.info("송금 운영자 종결 기록: trxId={}, outcome={}, changed={}, actor={}",
                result.getString("trxId"), outcome, result.getBoolean("changed"), actor);
    }

    /** 확정 후 Wallet 잔액을 응답에 담는다. 고객사 응답 계약의 잔액 필드로 사용한다. */
    private static void balance(Connection db, SharedMap<String, Object> result) {

        if (result.isBlank("walletId")) return;
        try {
            List<SharedMap<String, Object>> found = rows(db, "SELECT balance,available FROM WX.WALLET_BALANCE WHERE walletId=?",
                    result.getString("walletId"));
            if (found.isEmpty()) return;
            result.add("balance", found.get(0).getLong("balance"))
                    .add("availableBalance", found.get(0).getLong("available"));
        } catch (SQLException failure) {
            // 잔액 조회 실패는 이미 확정된 거래 결과를 바꾸지 않는다. 응답에서 잔액만 생략한다.
            logger.debug("확정 후 잔액 조회 실패: walletId={}", result.getString("walletId"), failure);
        }
    }

    /** 성공 확정: 모계좌의 실제 출금 확정 원장을 기록한다. */
    private static void hqLedger(Connection db, SharedMap<String, Object> transfer, String acqTrxId,
                                  String actor, LocalDateTime postedAt) throws SQLException {

        String hqId   = transfer.getString("hqId");
        long   amount = transfer.getLong("amount");
        long   before = rows(db, "SELECT balance FROM WX.HQ_ACCNT WHERE id=? FOR UPDATE", hqId).get(0).getLong("balance");
        int    day    = WxPostingRps.businessDay(postedAt);
        update(db, """
                INSERT INTO WX.TRX_HQ(id,hqId,trackId,status,trxType,dtlType,refType,refId,amount,balance,balanceAfter,
                                      bankCd,bankName,holder,accntNo,sender,acqTrxId,trxDay,trxDate,regId,regDay)
                VALUES(?,?,?,'성공','출금','일반출금','TRX_TRANSFER',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, IDUtils.getUnique("H"), hqId, transfer.getString("trackId"), transfer.getString("trxId"),
                Math.negateExact(amount), before, Math.subtractExact(before, amount),
                transfer.getString("bankCd"), transfer.getString("bankName"), transfer.getString("holder"),
                WxMerchantRps.maskedAccount(transfer.getString("accntNo")), transfer.getString("sender"),
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
    private static void clearRequest(SharedMap<String, Object> result) {

        if (result.isBlank("mchtId") || result.isBlank("trackId") || result.isEquals("status", "미확인")) return;
        RedisUtils.del(LedgerKeys.transferRequest(result.getString("mchtId"), result.getString("trackId")));
    }

    /** 재조회 대상 적재. 장애 큐는 재조회 Task가 소비한다. */
    public static void park(String trxId, String reason) {

        RedisUtils.rpush(LedgerKeys.TRANSFER_ERROR_BOX, SharedMap.<String, Object>of()
                .add("table", "TRX_TRANSFER")
                .add("trxId", trxId)
                .add("retry", 0)
                .add("reason", reason));
        logger.info("송금 재조회 대기 적재: trxId={}, reason={}", trxId, reason);
    }

    /** 확정 트랜잭션이 깨진 경우 자체 연결로 미확정 주차만 수행한다. */
    private static void parkAfterFailure(String trxId, SharedMap<String, Object> in) {

        try (Connection db = WxDatabaseRps.open()) {
            update(db, "UPDATE WX.TRX_WALLET SET status='장애',resultMsg='통신장애, 결과 확인 중'"
                    + " WHERE refType='TRX_TRANSFER' AND refId=? AND status='대기'", trxId);
            update(db, "UPDATE WX.TRX_TRANSFER SET status='미확인',responseCode=?,udtId=?"
                    + " WHERE trxId=? AND status IN ('접수','처리중')", in.getString("responseCode"),
                    in.isBlank("actor") ? "WX_API" : in.getString("actor"), trxId);
            park(trxId, "확정 처리 실패");
        } catch (Exception failure) {
            // 여기까지 실패하면 자동 해소 경로가 없다. 운영자가 처리 이력으로 확인해야 한다.
            logger.error("송금 미확정 주차 실패, 수동 확인 필요: trxId={}", trxId, failure);
        }
    }

    /**
     * 장애 큐에서 재조회 대상을 꺼내 기관 조회에 필요한 값과 함께 돌려준다.
     *
     * <p>꺼낸 건이 이미 확정되었거나 재조회 유지 시간을 넘겼으면 큐에서 버린다. 기관 원거래번호가 없으면
     * 기관에 물어볼 수 없으므로 운영자 확인 대상으로 남긴다. 큐에서 꺼낸 뒤 결과를 확정하지 못하면
     * 호출자가 {@link #requeue(String, int, String)} 로 다시 넣는다.</p>
     */
    public static List<SharedMap<String, Object>> pending(int limit) throws SQLException {

        List<SharedMap<String, Object>> out = new ArrayList<>();
        // 한 번에 돌려주는 건수는 내부 응답 프레임 8KB 안에 기관 조회 프로필까지 담기는 범위로 제한한다.
        int wanted = Math.min(Math.max(limit, 1), 10);
        // 이미 확정된 건은 큐에 남아 있어도 버린다. 버린 건을 처리 건수로 세면 실제 재조회 대상이
        // 뒤로 밀리므로 훑는 횟수와 담는 건수를 따로 센다. 한 주기가 길어지지 않도록 상한을 둔다.
        for (int scanned = 0; out.size() < wanted && scanned < DRAIN_LIMIT; scanned++) {
            Object popped = RedisUtils.lpop(LedgerKeys.TRANSFER_ERROR_BOX);
            if (popped == null) break;
            if (!(popped instanceof Map<?, ?> raw) || raw.isEmpty()) continue;

            SharedMap<String, Object> entry = SharedMap.<String, Object>of();
            raw.forEach((key, value) -> entry.put(String.valueOf(key), value));
            String trxId = entry.getString("trxId");
            if (trxId.isBlank()) continue;

            SharedMap<String, Object> found = recoverable(trxId);
            if (found == null) {
                logger.debug("재조회 대상에서 제외, 이미 확정됨: trxId={}", trxId);
                continue;
            }
            if (found.getInt("expired") == 1) {
                logger.warn("재조회 유지 시간 초과로 큐에서 제거, 운영자 확인 필요: trxId={}, regDate={}",
                        trxId, found.getString("regDate"));
                continue;
            }
            if (found.isBlank("bankTrxId")) {
                logger.warn("기관 원거래번호가 없어 재조회 불가, 운영자 확인 필요: trxId={}", trxId);
                continue;
            }
            if (!found.containsKey("upstream")) {
                logger.warn("모계좌 기관 연동 설정이 없어 재조회 불가, 운영자 확인 필요: trxId={}, hqId={}",
                        trxId, found.getString("hqId"));
                continue;
            }
            out.add(found.add("retry", entry.getInt("retry")));
        }
        if (!out.isEmpty()) logger.info("송금 재조회 대상 전달: count={}", out.size());
        return out;
    }

    /** 기관 결과를 아직 확정하지 못해 다음 주기에 다시 조회한다. */
    public static void requeue(String trxId, int retry, String reason) {

        RedisUtils.rpush(LedgerKeys.TRANSFER_ERROR_BOX, SharedMap.<String, Object>of()
                .add("table", "TRX_TRANSFER")
                .add("trxId", trxId)
                .add("retry", retry + 1)
                .add("reason", reason));
        logger.info("송금 재조회 재대기: trxId={}, retry={}, reason={}", trxId, retry + 1, reason);
    }

    /** 재조회 대상 한 건의 기관 조회 입력을 조회한다. 재조회 유지 시간이 지난 건은 제외한다. */
    private static SharedMap<String, Object> recoverable(String trxId) throws SQLException {

        try (Connection db = WxDatabaseRps.open()) {
            List<SharedMap<String, Object>> found = rows(db, """
                    SELECT t.trxId,t.mchtId,t.walletId,t.hqId,t.amount,t.bankTrxId,t.trackId,t.status,t.regDate,
                           CASE WHEN t.regDate < NOW() - INTERVAL ? HOUR THEN 1 ELSE 0 END AS expired,
                           h.acq,h.acqId,h.bankCd AS acqBankCd,h.accntNo AS acqAccount,
                           COALESCE(h.key2,'') AS acqKey2
                      FROM WX.TRX_TRANSFER t JOIN WX.HQ_ACCNT h ON h.id=t.hqId
                     WHERE t.trxId=? AND t.status='미확인'
                    """, RECOVERY_HOURS, trxId);
            if (found.isEmpty()) {
                logger.debug("재조회 대상 아님: trxId={}", trxId);
                return null;
            }
            SharedMap<String, Object> row = found.get(0);
            SharedMap<String, Object> profile = upstream(row.getString("hqId"), row);
            // 보호키는 중첩 프로필 한 곳에만 둔다. 평면 컬럼으로 중복 전달하지 않는다.
            for (String name : List.of("acq", "acqId", "acqBankCd", "acqAccount", "acqKey2")) row.remove(name);
            return profile == null ? row : row.add("upstream", profile);
        }
    }

}
