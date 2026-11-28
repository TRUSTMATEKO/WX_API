package kr.tx24.wx.ledger.rps;

import java.util.List;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Set;

import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.ledger.enm.LedgerCode;
import kr.tx24.wx.ledger.ex.LedgerEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static kr.tx24.wx.ledger.rps.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.rps.WxDatabaseRps.update;

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
     * <p>호출자는 공통 기관 설정을 확인한 후 업무 트랜잭션에서 접수한다.</p>
     */
    static SharedMap<String, Object> accept(Connection db, SharedMap<String, Object> inMap, String company,
                                             String walletId, LocalDateTime now) throws SQLException {

        String track   = WxPostingRps.field(inMap, "trackId", 128);
        String bankCd  = WxPostingRps.field(inMap, "bankCd", 12);
        String accntNo = WxPostingRps.field(inMap, "accntNo", 32);
        if (!bankCd.matches("[0-9]{3}") || !accntNo.matches("[0-9]{1,20}")) {
            throw new LedgerEx(LedgerCode.INVALID, "예금주 조회 요청값을 확인해 주세요.");
        }
        int day = WxPostingRps.businessDay(now);

        List<SharedMap<String, Object>> existsList = rows(db, "SELECT trxId,status FROM WX.TRX_HOLDER WHERE mchtId=? AND trackId=?", company, track);
        if (!existsList.isEmpty()) {
            logger.info("예금주 조회 요청번호 중복: mchtId={}, trackId={}, trxId={}, status={}", company, track,
                    existsList.get(0).getString("trxId"), existsList.get(0).getString("status"));
            throw new LedgerEx(LedgerCode.DUPLICATE_REQUEST, "이미 접수된 요청번호입니다.");
        }

        List<SharedMap<String, Object>> walletList = rows(db, """
                SELECT w.id,w.mchtId,w.hqId
                  FROM WX.WALLET w
                 WHERE w.id=? AND w.mchtId=? AND w.ownerType='MCHT' AND w.status='사용' AND w.apiStatus='사용'
                """, walletId, company);
        if (walletList.size() != 1) throw new LedgerEx(LedgerCode.WALLET_UNAVAILABLE, "Wallet 사용 상태를 확인해 주세요.");

        SharedMap<String, Object> rateMap = fee(db, company, day);
        long                      charge  = rateMap.getLong("feeAmt");
        String                    trxId   = IDUtils.getUnique("V");
        // 조회 대상 계좌번호는 마스킹과 암호문으로만 남긴다. 암호 키는 WX_ADM에만 있다.
        String masked = WxMerchantRps.maskedAccount(accntNo);

        update(db, """
                INSERT INTO WX.TRX_HOLDER(trxId,mchtId,walletId,ioId,trackId,requestHash,bankCd,bankName,
                                          accntNo,accntNoEnc,status,feeAmt,feeRateIdx,feeTarget,regId,regDay,regDate)
                VALUES(?,?,?,?,?,?,?,?,?,?,'접수',?,?,0,'WX_API',?,?)
                """, trxId, company, walletId, inMap.getString("ioId"), track, inMap.getString("requestHash"),
                bankCd, WxMerchantRps.bankName(db, bankCd), masked, WxPostingRps.encryptForLedger(accntNo),
                charge, rateMap.get("feeRateIdx"), day, now);

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
                .add("hqId", walletList.get(0).getString("hqId"));
    }

    /**
     * 예금주 조회 결과 확정.
     *
     * <p>수수료가 있는 성공·불일치 결과를 과금 대상으로 둔다.
     * 자금이 움직이지 않으므로 원장과 잔액은 손대지 않는다.</p>
     */
    public static SharedMap<String, Object> confirm(SharedMap<String, Object> inMap) throws SQLException {

        String trxId   = WxPostingRps.field(inMap, "trxId", 32);
        String outcome = WxPostingRps.field(inMap, "outcome", 12);
        if (!OUTCOME.contains(outcome)) {
            throw new LedgerEx(LedgerCode.INVALID, "확정 결과를 확인해 주세요.");
        }
        try (Connection db = WxDatabaseRps.open()) {
            db.setAutoCommit(false);
            try {
                List<SharedMap<String, Object>> foundList = rows(db, new Retrieve("WX.TRX_HOLDER")
                        .columns("trxId,mchtId,status,feeAmt")
                        .where("trxId=?")
                        .forUpdate().build(),
                        trxId);
                if (foundList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "예금주 조회 거래를 찾을 수 없습니다.");
                if (!OPEN.contains(foundList.get(0).getString("status"))) {
                    logger.info("이미 확정된 예금주 조회: trxId={}, status={}", trxId, foundList.get(0).getString("status"));
                    db.commit();

                    return SharedMap.<String, Object>of()
                            .add("trxId", trxId)
                            .add("status", foundList.get(0).getString("status"))
                            .add("changed", false);
                }
                int changed = update(db, """
                        UPDATE WX.TRX_HOLDER SET status=?,responseCode=?,
                               feeTarget=CASE WHEN ? IN ('성공','불일치') AND feeAmt>0 THEN 1 ELSE 0 END,udtDate=NOW()
                         WHERE trxId=? AND status='접수'
                        """, outcome, inMap.getString("responseCode"), outcome, trxId);
                if (changed != 1) throw new SQLException("예금주 조회 결과 확정에 실패했습니다.");
                db.commit();

                logger.info("예금주 조회 확정: trxId={}, outcome={}, feeAmt={}",
                        trxId, outcome, foundList.get(0).getLong("feeAmt"));
                return SharedMap.<String, Object>of()
                        .add("trxId", trxId)
                        .add("status", outcome)
                        .add("mchtId", foundList.get(0).getString("mchtId"))
                        .add("changed", true);
            } catch (Exception failure) {
                db.rollback();
                logger.debug("예금주 조회 확정 예외 상세: trxId={}, outcome={}", trxId, outcome, failure);
                throw failure;
            }
        }
    }

    /** 예금주 조회 단가. 계약이 없으면 과금하지 않는다. */
    private static SharedMap<String, Object> fee(Connection db, String company, int day) throws SQLException {

        List<SharedMap<String, Object>> appliedList = rows(db, """
                SELECT idx,holderUnitAmt
                  FROM WX.MCHT_FEE WHERE mchtId=? AND applyDay<=? AND status='사용'
                 ORDER BY applyDay DESC LIMIT 1
                """, company, day);
        if (appliedList.isEmpty()) {
            logger.debug("적용 수수료 계약 없음: mchtId={}, baseDay={}", company, day);
            return SharedMap.<String, Object>of()
                    .add("feeAmt", 0L);
        }
        return SharedMap.<String, Object>of()
                .add("feeAmt", appliedList.get(0).getLong("holderUnitAmt"))
                .add("feeRateIdx", appliedList.get(0).getLong("idx"));
    }
}
