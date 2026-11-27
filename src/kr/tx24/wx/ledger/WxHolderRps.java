package kr.tx24.wx.ledger;

import java.util.List;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Set;

import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static kr.tx24.wx.ledger.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.WxDatabaseRps.update;

/**
 * 예금주 조회의 접수와 결과 확정을 기록한다.
 *
 * <p>송금과 달리 자금이 움직이지 않는다. 잔액·모계좌·원장을 건드리지 않고 요청과 결과만 남긴다.
 * 과금은 후불이며 성공한 건만 마감에서 집계한다({@code feeTarget=1}).</p>
 *
 * <p>조회로 받은 예금주 이름은 저장하지 않는다. 고객사 응답에만 담는다. {@code TRX_HOLDER}에
 * 이름 컬럼이 없는 이유다.</p>
 */
public final class WxHolderRps {

    private static final Logger logger = LoggerFactory.getLogger(WxHolderRps.class);

    /** 결과를 확정하지 못한 상태. */
    static final Set<String> OPEN = Set.of("접수");

    /** 확정 가능한 결과. 불일치는 계좌는 있으나 이름이 다른 경우다. */
    static final Set<String> OUTCOME = Set.of("성공", "실패", "불일치");

    private WxHolderRps() {

    }

    /**
     * 예금주 조회 접수. 요청을 기록하고 기관 전송 입력을 돌려준다.
     *
     * <p>호출자는 업무 트랜잭션을 연 상태여야 한다. 기관 연동 설정이 없으면 접수하지 않는다.
     * 보낼 수 없는 요청을 접수하면 과금 대상만 늘어난다.</p>
     */
    static SharedMap<String, Object> accept(Connection db, SharedMap<String, Object> in, String company,
                                             String walletId, LocalDateTime now) throws SQLException {

        String track   = WxPostingRps.field(in, "trackId", 128);
        String bankCd  = WxPostingRps.field(in, "bankCd", 12);
        String accntNo = WxPostingRps.field(in, "accntNo", 32);
        if (!bankCd.matches("[0-9]{3}") || !accntNo.matches("[0-9]{1,20}")) {
            throw new LedgerEx(LedgerCode.INVALID, "예금주 조회 요청값을 확인해 주세요.");
        }
        int day = WxPostingRps.businessDay(now);

        List<SharedMap<String, Object>> exists = rows(db, "SELECT trxId,status FROM WX.TRX_HOLDER WHERE mchtId=? AND trackId=?", company, track);
        if (!exists.isEmpty()) {
            logger.info("예금주 조회 요청번호 중복: mchtId={}, trackId={}, trxId={}, status={}", company, track,
                    exists.get(0).getString("trxId"), exists.get(0).getString("status"));
            throw new LedgerEx(LedgerCode.CONFLICT, "이미 접수된 요청번호입니다.");
        }

        List<SharedMap<String, Object>> wallet = rows(db, """
                SELECT w.id,w.mchtId,w.hqId
                  FROM WX.WALLET w
                 WHERE w.id=? AND w.mchtId=? AND w.ownerType='MCHT' AND w.status='사용' AND w.apiStatus='사용'
                """, walletId, company);
        if (wallet.size() != 1) throw new LedgerEx(LedgerCode.CONFLICT, "Wallet 사용 상태를 확인해 주세요.");

        // 기관 연동 설정은 접수 전에 확인한다. 보낼 수 없는 요청을 과금 대상으로 남기지 않는다.
        List<SharedMap<String, Object>> bank = rows(db, """
                SELECT id,acq,acqId,bankCd AS acqBankCd,accntNo AS acqAccount,COALESCE(key2,'') AS acqKey2
                  FROM WX.HQ_ACCNT WHERE id=? AND status='사용'
                """, wallet.get(0).getString("hqId"));
        if (bank.size() != 1) {
            logger.warn("모계좌 사용 상태가 아니어서 예금주 조회를 접수하지 않는다: hqId={}",
                    wallet.get(0).getString("hqId"));
            throw new LedgerEx(LedgerCode.CONFLICT, "예금주 조회를 접수할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        SharedMap<String, Object> upstream = upstream(bank.get(0));
        if (upstream == null) {
            logger.warn("모계좌 기관 연동 설정이 없어 예금주 조회를 접수하지 않는다: hqId={}, acq={}",
                    bank.get(0).getString("id"), bank.get(0).getString("acq"));
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "기관 연동 설정이 없어 예금주 조회가 중지되었습니다.");
        }

        SharedMap<String, Object> rate = fee(db, company, day);
        long charge = rate.getLong("feeAmt");
        String trxId = IDUtils.getUnique("V");
        // 조회 대상 계좌번호는 마스킹과 암호문으로만 남긴다. 암호 키는 WX_ADM에만 있다.
        String masked = WxMerchantRps.maskedAccount(accntNo);

        update(db, """
                INSERT INTO WX.TRX_HOLDER(trxId,mchtId,walletId,ioId,trackId,requestHash,bankCd,bankName,
                                          accntNo,accntNoEnc,status,feeAmt,feeRateIdx,feeTarget,regId,regDay,regDate)
                VALUES(?,?,?,?,?,?,?,?,?,?,'접수',?,?,0,'WX_API',?,?)
                """, trxId, company, walletId, in.getString("ioId"), track, in.getString("requestHash"),
                bankCd, bankName(db, bankCd), masked, WxPostingRps.encryptForLedger(accntNo),
                charge, rate.get("feeRateIdx"), day, now);

        logger.info("예금주 조회 접수: trxId={}, mchtId={}, walletId={}, bankCd={}, feeAmt={}",
                trxId, company, walletId, bankCd, charge);
        return SharedMap.<String, Object>of()
                .add("trxId", trxId)
                .add("trackId", track)
                .add("status", "접수")
                .add("bankCd", bankCd)
                .add("accntNo", accntNo)
                .add("accntNoMasked", masked)
                .add("feeAmt", charge)
                .add("hqId", wallet.get(0).getString("hqId"))
                .add("upstream", upstream);
    }

    /**
     * 예금주 조회 결과 확정.
     *
     * <p>수수료가 있는 성공·불일치 결과를 과금 대상으로 둔다.
     * 자금이 움직이지 않으므로 원장과 잔액은 손대지 않는다.</p>
     */
    public static SharedMap<String, Object> confirm(SharedMap<String, Object> in) throws SQLException {

        String trxId   = WxPostingRps.field(in, "trxId", 32);
        String outcome = WxPostingRps.field(in, "outcome", 12);
        if (!OUTCOME.contains(outcome)) {
            throw new LedgerEx(LedgerCode.INVALID, "확정 결과를 확인해 주세요.");
        }
        try (Connection db = WxDatabaseRps.open()) {
            db.setAutoCommit(false);
            try {
                List<SharedMap<String, Object>> found = rows(db, "SELECT trxId,mchtId,status,feeAmt FROM WX.TRX_HOLDER WHERE trxId=? FOR UPDATE",
                        trxId);
                if (found.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "예금주 조회 거래를 찾을 수 없습니다.");
                if (!OPEN.contains(found.get(0).getString("status"))) {
                    logger.info("이미 확정된 예금주 조회: trxId={}, status={}", trxId, found.get(0).getString("status"));
                    db.commit();
                    return SharedMap.<String, Object>of().add("trxId", trxId)
                            .add("status", found.get(0).getString("status")).add("changed", false);
                }
                int changed = update(db, """
                        UPDATE WX.TRX_HOLDER SET status=?,responseCode=?,
                               feeTarget=CASE WHEN ? IN ('성공','불일치') AND feeAmt>0 THEN 1 ELSE 0 END,udtDate=NOW()
                         WHERE trxId=? AND status='접수'
                        """, outcome, in.getString("responseCode"), outcome, trxId);
                if (changed != 1) throw new SQLException("예금주 조회 결과 확정에 실패했습니다.");
                db.commit();
                logger.info("예금주 조회 확정: trxId={}, outcome={}, feeAmt={}",
                        trxId, outcome, found.get(0).getLong("feeAmt"));
                return SharedMap.<String, Object>of().add("trxId", trxId).add("status", outcome)
                        .add("mchtId", found.get(0).getString("mchtId")).add("changed", true);
            } catch (Exception failure) {
                db.rollback();
                logger.error("예금주 조회 확정 처리 실패: trxId={}, outcome={}", trxId, outcome, failure);
                throw failure;
            }
        }
    }

    /** 예금주 조회 단가. 계약이 없으면 과금하지 않는다. */
    private static SharedMap<String, Object> fee(Connection db, String company, int day) throws SQLException {

        List<SharedMap<String, Object>> applied = rows(db, """
                SELECT idx,holderUnitAmt
                  FROM WX.MCHT_FEE WHERE mchtId=? AND applyDay<=? AND status='사용'
                 ORDER BY applyDay DESC LIMIT 1
                """, company, day);
        if (applied.isEmpty()) {
            logger.debug("적용 수수료 계약 없음: mchtId={}, baseDay={}", company, day);
            return SharedMap.<String, Object>of().add("feeAmt", 0L);
        }
        return SharedMap.<String, Object>of()
                .add("feeAmt", applied.get(0).getLong("holderUnitAmt"))
                .add("feeRateIdx", applied.get(0).getLong("idx"));
    }

    /** 거래 시점의 은행 이름을 기록한다. 코드만 남기면 나중에 읽기 어렵다. */
    private static String bankName(Connection db, String bankCd) throws SQLException {

        return rows(db, "SELECT COALESCE(NULLIF(nickKr,''),nameKr) AS name FROM WX.BANK WHERE code=?", bankCd)
                .stream().findFirst().map(row -> row.getString("name")).orElse("");
    }

    /** 모계좌의 기관 연동 설정을 WX_API가 사용할 조회 프로필로 만든다. 송금과 같은 계약이다. */
    private static SharedMap<String, Object> upstream(SharedMap<String, Object> bank) {

        if (!"HYPHEN".equals(bank.getString("acq")) || bank.isBlank("acqId") || bank.isBlank("acqBankCd")
                || bank.isBlank("acqAccount") || bank.isBlank("acqKey2")) {
            return null;
        }
        // 모계좌 ID 는 기관 거래 일련번호를 모계좌별로 나누는 키에 쓴다. 보호값이 아니다.
        return SharedMap.<String, Object>of()
                .add("hqId", bank.getString("id"))
                .add("acq", bank.getString("acq"))
                .add("acqSid", bank.getString("acqId"))
                .add("acqBankCd", bank.getString("acqBankCd"))
                .add("acqAccount", bank.getString("acqAccount"))
                .add("acqKey2", bank.getString("acqKey2"));
    }
}
