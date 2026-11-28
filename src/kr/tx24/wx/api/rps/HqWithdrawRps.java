package kr.tx24.wx.api.rps;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.List;

import kr.tx24.lib.db.Create;
import kr.tx24.lib.db.DBTrx;
import kr.tx24.lib.db.DBUtils;
import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.db.Update;
import kr.tx24.lib.kms.KMSUtils;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisTextUtils;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.Acquirer;

/** 본사 외부 출금의 과금 상세 점유, Wallet 선차감, 은행 예약과 확정을 한 거래로 기록한다. */
public final class HqWithdrawRps {

    private static final Charset BANK_CHARSET = Charset.forName("KSC5601");
    private static final String TABLE = "WX.TRX_HQ_WITHDRAW";

    /** 본사 출금의 재접수 여부를 확인하고 신규 건의 Wallet 선차감과 모계좌 예약을 기록한다. */
    public SharedMap<String, Object> accept(SharedMap<String, Object> inMap) throws SQLException {

        String  hqId   = inMap.getString("hqId");
        String  id     = inMap.getString("id");
        long    amount = inMap.getLong("amount");
        boolean fee    = inMap.isEquals("kind", "fee");

        DBTrx tx = new DBTrx();
        try {
            tx.begin();

            // 모계좌와 본사 Wallet을 먼저 잠가 출금 가능액 확인과 선차감 사이에 잔액이 바뀌지 않게 한다.
            SharedMap<String, Object> bankMap = tx.select(new Retrieve("WX.HQ_ACCNT")
                    .columns("id hqId,acq,acqId acqSid,bankCd acqBankCd,accntNo acqAccount,key2 acqKey2")
                    .column("alarmBalance,withdrawBankCd bankCd,withdrawAccntNo accntNo,withdrawAccntNoEnc cipher")
                    .column("withdrawHolder holder,settingRev,status")
                    .where("id=?")
                    .forUpdate().build(), hqId).getRow(0);
            SharedMap<String, Object> walletMap = tx.select(new Retrieve("WX.WALLET w")
                    .columns("w.id,b.balance")
                    .joinInner("WX.WALLET_BALANCE b", "b.walletId=w.id")
                    .where("w.hqWalletHqId=? AND w.status='사용'")
                    .forUpdate().build(), hqId).getRow(0);

            // 접수 중 권한 변경을 막되 같은 관리자의 다른 출금도 권한을 확인할 수 있게 한다.
            SharedMap<String, Object> operatorMap = tx.select(new Retrieve("WX.USER_ADMIN")
                    .columns("id")
                    .where("id=? AND status='사용' AND `2faStatus`='사용' AND role BETWEEN 0 AND 1")
                    .lockInShareMode().build(), inMap.getString("userId")).getRow(0);
            if (operatorMap == null) {
                throw new WalletException("W002", "출금 실행 권한을 확인해 주세요.");
            }

            // 같은 요청의 재접수는 저장된 상태만 반환한다. 요청번호를 다른 출금에 재사용하면 거절한다.
            SharedMap<String, Object> previousMap = tx.select(
                    new Retrieve("WX.TRX_HQ_WITHDRAW")
                            .where("id=?")
                            .forUpdate().build(), id).getRow(0);
            if (previousMap != null) {
                for (String key : List.of("kind", "hqId", "amount", "baseDay", "mchtId", "reason", "settingRev", "sender")) {
                    if (!previousMap.isEquals(key, inMap.getString(key))) {
                        throw refused("이미 다른 출금에 사용한 요청번호입니다.");
                    }
                }
                if (fee) {
                    SharedMap<String, Object> detailMap = tx.select(
                            "SELECT detailId FROM WX.HQ_WITHDRAW_FEE WHERE withdrawId=? AND detailId=?",
                            id, inMap.getString("detailId")).getRow(0);
                    if (detailMap == null) {
                        throw refused("출금 대상이 다릅니다.");
                    }
                }
                if (!previousMap.isEquals("regId", inMap.getString("userId"))) {
                    throw refused("이미 사용한 요청번호입니다.");
                }
                tx.commit();

                return SharedMap.<String, Object>of()
                        .add("status", previousMap.getString("status"));
            }

            if (bankMap != null && Acquirer.from(bankMap.getString("acq")).isInactive()) {
                throw new WalletException(WalletPolicy.INACTIVE_ACQUIRER, WalletPolicy.INACTIVE_ACQUIRER_MSG);
            }

            // 승인 때의 계좌 설정 버전과 대조한 뒤 지정된 수취 계좌를 복호화한다.
            if (walletMap == null || bankMap == null || !bankMap.isEquals("status", "사용")
                    || !bankMap.isEquals("settingRev", inMap.getString("settingRev")) || bankMap.isBlank("cipher")
                    || bankMap.isBlank("holder") || !bankMap.getString("bankCd").matches("[0-9]{3}")) {
                throw refused("지정 출금 계좌와 모계좌 설정을 다시 확인해 주세요.");
            }
            byte[] encrypted = java.util.Base64.getDecoder().decode(bankMap.getString("cipher"));
            byte[] decoded   = KMSUtils.decrypt(encrypted);
            if (decoded == null || java.util.Arrays.equals(encrypted, decoded)) {
                throw refused("지정 계좌 복호화에 실패했습니다.");
            }
            String plain = new String(decoded, java.nio.charset.StandardCharsets.US_ASCII);
            if (!plain.matches("[0-9]{1,20}") || !bankMap.isEquals("acq", "HYPHEN")
                    || !WalletPolicy.isValidAcqSid(bankMap.getString("acqSid"))) {
                throw refused("출금 계좌 또는 기관 설정을 확인해 주세요.");
            }

            // 접수 중지 시간과 출금 종류별 가용액을 현재 원장 기준으로 확인한다.
            String                    hhmm      = DateUtils.getCurrentTime("HHmm");
            SharedMap<String, Object> configMap = tx.select("""
                    SELECT CASE WHEN firmStopStart IS NULL AND firmStopEnd IS NULL THEN 1
                        WHEN firmStopStart<firmStopEnd THEN NOT (? >= firmStopStart AND ? < firmStopEnd)
                        WHEN firmStopStart>firmStopEnd THEN (? >= firmStopEnd AND ? < firmStopStart)
                        ELSE 0 END openFlag FROM WX.CONFIG WHERE id=1
                    """, hhmm, hhmm, hhmm, hhmm).getRow(0);
            if (configMap == null || configMap.getInt("openFlag") != 1) {
                throw refused("송금 접수 중지 시간입니다.");
            }
            int                       today    = DateUtils.getRegDay();
            String                    walletId = walletMap.getString("id");
            SharedMap<String, Object> fundsMap = tx.select("SELECT * FROM WX.V_HQ_WITHDRAW_LIMIT WHERE hqId=?", hqId).getRow(0);
            if (fundsMap == null || amount > fundsMap.getLong(fee ? "payable" : "manualPayable")) {
                throw refused("출금 가능액이 부족합니다.");
            }

            String                          sender      = inMap.getString("sender");
            List<SharedMap<String, Object>> detailsList = List.of();
            if (fee) {
                // 전일까지의 미출금 과금 상세에 요청한 항목이 있고 합계가 승인 금액과 같은지 확인한다.
                if (inMap.getInt("baseDay") >= today) {
                    throw refused("전일까지 반영된 과금액만 출금할 수 있습니다.");
                }
                detailsList = tx.select("""
                        SELECT f.detailId,f.totalAmt,m.editRev FROM WX.V_HQ_WITHDRAW_FEE f
                          JOIN WX.MCHT m ON m.mchtId=f.mchtId
                         WHERE f.baseDay=? AND f.mchtId=? AND f.hqId=? AND f.hqWalletId=? AND f.withdrawId IS NULL
                        """, inMap.getInt("baseDay"), inMap.getString("mchtId"), hqId, walletId).getRows();
                String  detailId = inMap.getString("detailId");
                long    total    = 0;
                boolean selected = false;
                for (SharedMap<String, Object> detailMap : detailsList) {
                    total = Math.addExact(total, detailMap.getLong("totalAmt"));
                    if (detailMap.isEquals("detailId", detailId)) {
                        selected = true;
                    }
                }
                if (!selected || amount != total) {
                    throw refused("미출금 과금액이 변경되었습니다. 다시 조회해 주세요.");
                }

                String name = merchantName(tx, inMap.getString("mchtId"), detailsList.get(0).getLong("editRev"));
                sender = CommonUtils.cut(name, 20, BANK_CHARSET);
            }
            if (sender.isBlank() || !BANK_CHARSET.newEncoder().canEncode(sender) || sender.getBytes(BANK_CHARSET).length > 20
                    || (!fee && sender.codePoints().anyMatch(Character::isISOControl)) || !inMap.isEquals("sender", sender)) {
                throw refused("받는 통장 표시명을 다시 확인해 주세요.");
            }

            // 출금 접수와 과금 상세 점유를 Wallet 선차감 및 모계좌 예약과 함께 커밋한다.
            tx.insert(new Create()
                    .table(TABLE)
                    .record("id", id)
                    .record("kind", inMap.getString("kind"))
                    .record("hqId", hqId)
                    .record("walletId", walletId)
                    .record("baseDay", inMap.getInt("baseDay"))
                    .record("mchtId", inMap.getString("mchtId"))
                    .record("amount", amount)
                    .record("sender", sender)
                    .record("bankCd", bankMap.getString("bankCd"))
                    .record("accntNo", bankMap.getString("accntNo"))
                    .record("holder", bankMap.getString("holder"))
                    .record("settingRev", bankMap.getLong("settingRev"))
                    .record("acqSid", bankMap.getString("acqSid"))
                    .record("sourceBank", bankMap.getString("acqBankCd"))
                    .record("sourceAccount", bankMap.getString("acqAccount"))
                    .record("reason", inMap.getString("reason"))
                    .record("regId", inMap.getString("userId"))
                    .record("regDay", today));
            for (SharedMap<String, Object> detailMap : detailsList) {
                tx.insert(new Create()
                        .table("WX.HQ_WITHDRAW_FEE")
                        .record("withdrawId", id)
                        .record("detailId", detailMap.getString("detailId")));
            }

            // 앞서 잠근 Wallet의 다음 순번으로 차감 전후 잔액을 기록한다.
            long sequence = tx.select("SELECT COALESCE(MAX(`sequence`),0)+1 seq FROM WX.TRX_WALLET WHERE walletId=?", walletId)
                    .getRowFirst().getLong("seq");
            tx.insert(new Create()
                    .table("WX.TRX_WALLET")
                    .record("id", IDUtils.getUnique("T"))
                    .record("walletId", walletId)
                    .record("ownerType", "HQ")
                    .record("hqId", hqId)
                    .record("refId", id)
                    .record("refType", "TRX_HQ_WITHDRAW")
                    .record("`sequence`", sequence)
                    .record("trxType", "출금")
                    .record("dtlType", fee ? "과금액출금" : "본사임의출금")
                    .record("status", "처리중")
                    .record("amount", -amount)
                    .record("withdrawnAmt", amount)
                    .record("balance", walletMap.getLong("balance"))
                    .record("balanceAfter", Math.subtractExact(walletMap.getLong("balance"), amount))
                    .record("bankCd", bankMap.getString("bankCd"))
                    .record("accntNo", bankMap.getString("accntNo"))
                    .record("holder", bankMap.getString("holder"))
                    .record("sender", sender)
                    .record("note", inMap.getString("reason"))
                    .record("trxDay", today)
                    .record("regDay", today)
                    .record("regId", inMap.getString("userId")));
            changed(tx.update("UPDATE WX.HQ_ACCNT SET reservedAmt=reservedAmt+? WHERE id=?", amount, hqId));
            if (!fee) {
                changed(tx.update("""
                        INSERT INTO WX.AUDIT(id,unit,action,refType,refId,reason,regId,regDay)
                        VALUES(?,'HQ','HQ_MANUAL_WITHDRAW','TRX_HQ_WITHDRAW',?,?,?,?)
                        """, IDUtils.getRandom(32), DBUtils.cut(id, 32), inMap.getString("reason"),
                        inMap.getString("userId"), today));
            }
            tx.commit();
            return bankMap
                    .add("destination", plain)
                    .add("sender", sender)
                    .add("send", true);
        } catch (RuntimeException | SQLException | Error ex) {
            tx.rollback(ex);
            throw ex;
        }
    }

    /** 과금 출금의 통장 표시명을 고객사 설정 버전으로 읽는다. */
    private static String merchantName(DBTrx tx, String mchtId, long revision) throws SQLException {

        String  cacheKey       = "WX:API:REFERENCE:MCHT:NAME:" + mchtId + ":" + revision;
        boolean cacheAvailable = true;
        try {
            String name = RedisTextUtils.get(cacheKey);
            if (name != null) {
                return name;
            }
        } catch (RuntimeException unavailable) {
            cacheAvailable = false;
        }

        SharedMap<String, Object> rowMap = tx.select("SELECT name FROM WX.MCHT WHERE mchtId=? AND editRev=?",
                mchtId, revision).getRow(0);
        if (rowMap == null) {
            return null;
        }

        String name = rowMap.getString("name");
        if (cacheAvailable) {
            try {
                RedisTextUtils.setNx(cacheKey, name, WalletPolicy.REFERENCE_CACHE_SECONDS);
            } catch (RuntimeException unavailable) {
                // 원장에서 읽은 고객사명을 사용한다.
            }
        }
        return name;
    }

    /** 기관 전문 쓰기 직전에만 호출한다. 접수 취소와 송신의 경합은 조건부 갱신으로 가른다. */
    public void markSending(String id, String reference) {

        if (!reference.matches("[0-9]{12}")) {
            throw refused("기관 원거래번호를 확인해 주세요.");
        }
        changed(new Update()
                .table(TABLE)
                .record("bankTrxId", reference)
                .record("status", "처리중")
                .where("id", id)
                .where("status", "접수")
                .update());
    }

    /** 본사 출금을 확정하고 성공·실패 결과에 맞춰 Wallet 상태와 모계좌 예약액을 반영한다. */
    public String finish(
            String id, String status, String code, String message, boolean unsentOnly) throws SQLException {

        DBTrx tx = new DBTrx();
        try {
            tx.begin();

            // 접수와 같은 순서로 모계좌를 잠근 다음 출금 건의 최신 상태를 확인한다.
            SharedMap<String, Object> ownerMap = tx.select("SELECT hqId FROM WX.TRX_HQ_WITHDRAW WHERE id=?", id).getRow(0);
            if (ownerMap == null) {
                throw refused("출금을 찾을 수 없습니다.");
            }
            String                    hqId    = ownerMap.getString("hqId");
            SharedMap<String, Object> bankMap = tx.select(new Retrieve("WX.HQ_ACCNT")
                    .columns("balance")
                    .where("id=?")
                    .forUpdate().build(), hqId).getRow(0);
            if (bankMap == null) {
                throw refused("모계좌를 찾을 수 없습니다.");
            }
            SharedMap<String, Object> rowMap = tx.select(new Retrieve("WX.TRX_HQ_WITHDRAW")
                    .where("id=?")
                    .forUpdate().build(), id).getRow(0);
            if (rowMap == null) {
                throw refused("출금을 찾을 수 없습니다.");
            }

            // 이미 끝난 건과 전송을 시작한 뒤 들어온 접수 취소는 기존 상태를 반환한다.
            String current = rowMap.getString("status");
            if (List.of("성공", "실패").contains(current) || (unsentOnly && !current.equals("접수"))) {
                tx.commit();
                return current;
            }
            long amount = rowMap.getLong("amount");
            if (!current.equals(status)) {
                changed(tx.update(new Update()
                        .table("WX.TRX_WALLET")
                        .record("status", status)
                        .record("resultCd", DBUtils.cut(code, 12))
                        .record("resultMsg", DBUtils.cut(message, 128))
                        .where("refType", "TRX_HQ_WITHDRAW")
                        .where("refId", id)));
            }

            // 미확인은 예약을 유지한다. 성공·실패가 확인되면 예약을 해제하고 성공 건만 모계좌에서 차감한다.
            if (!"미확인".equals(status)) {
                long    before  = bankMap.getLong("balance");
                boolean success = "성공".equals(status);
                changed(tx.update("UPDATE WX.HQ_ACCNT SET reservedAmt=reservedAmt-?,balance=balance-? WHERE id=? AND reservedAmt>=?",
                        amount, success ? amount : 0, hqId, amount));
                if (success) {
                    int day = DateUtils.getRegDay();
                    tx.update(new Update()
                            .table("WX.TRX_WALLET")
                            .record("hqBalanceBef", before)
                            .record("hqBalanceAft", Math.subtractExact(before, amount))
                            .record("acqTrxId", rowMap.getString("bankTrxId"))
                            .record("acqResultCd", DBUtils.cut(code, 16))
                            .record("acqResultMsg", DBUtils.cut(message, 128))
                            .where("refType", "TRX_HQ_WITHDRAW")
                            .where("refId", id));
                    tx.insert(new Create()
                            .table("WX.TRX_HQ")
                            .record("id", IDUtils.getUnique("H"))
                            .record("hqId", hqId)
                            .record("refType", "TRX_HQ_WITHDRAW")
                            .record("refId", id)
                            .record("trxType", "출금")
                            .record("dtlType", rowMap.isEquals("kind", "fee") ? "과금액출금" : "본사임의출금")
                            .record("amount", -amount)
                            .record("balance", before)
                            .record("balanceAfter", Math.subtractExact(before, amount))
                            .record("bankCd", rowMap.getString("bankCd"))
                            .record("holder", rowMap.getString("holder"))
                            .record("accntNo", rowMap.getString("accntNo"))
                            .record("sender", rowMap.getString("sender"))
                            .record("acq", "HYPHEN")
                            .record("acqId", rowMap.getString("acqSid"))
                            .record("acqTrxId", rowMap.getString("bankTrxId"))
                            .record("acqResultCd", DBUtils.cut(code, 16))
                            .record("regId", rowMap.getString("regId"))
                            .record("trxDay", day)
                            .record("regDay", day));
                } else {
                    // 실패한 출금에 점유된 과금 상세는 다시 출금 대상으로 돌려준다.
                    tx.update("UPDATE WX.HQ_WITHDRAW_FEE SET active=NULL WHERE withdrawId=?", id);
                }
            }
            tx.update("""
                    UPDATE WX.TRX_HQ_WITHDRAW SET status=?,responseCode=?,message=?,checkedAt=NOW(),
                           settledAt=IF(?='미확인',NULL,NOW()) WHERE id=?
                    """, status, DBUtils.cut(code, 24), DBUtils.cut(message, 128), status, id);
            tx.commit();
            return status;
        } catch (RuntimeException | SQLException | Error ex) {
            tx.rollback(ex);
            throw ex;
        }
    }

    /** 작업기에서 재조회할 본사 출금과 접수 당시 기관 정보를 읽는다. */
    public List<SharedMap<String, Object>> pending(int limit) {

        RecordSet result = new Retrieve().select("""
                SELECT x.id,x.status,x.bankTrxId,x.hqId,x.acqSid,x.sourceBank acqBankCd,x.sourceAccount acqAccount,
                       h.key2 acqKey2,h.acq,h.alarmBalance FROM WX.TRX_HQ_WITHDRAW x
                  LEFT JOIN WX.HQ_ACCNT h ON h.id=x.hqId AND h.acqId=x.acqSid AND h.bankCd=x.sourceBank AND h.accntNo=x.sourceAccount
                 WHERE x.status IN ('접수','처리중','미확인') AND x.regDate<NOW()-INTERVAL 2 MINUTE
                   AND (x.checkedAt IS NULL OR x.checkedAt<=NOW()-INTERVAL 2 MINUTE)
                 ORDER BY COALESCE(x.checkedAt,x.regDate),x.id LIMIT ?
                """, List.of(limit));
        if (result == null) {
            throw refused("미결 출금을 조회하지 못했습니다.");
        }
        return result.getRows();
    }

    /** 조회 당시 상태와 재조회 간격을 다시 확인해 한 작업기만 출금을 선점한다. */
    public boolean claimRecovery(String id, String status) {

        return new Update().update("""
                UPDATE WX.TRX_HQ_WITHDRAW SET checkedAt=NOW()
                 WHERE id=? AND status=? AND regDate<NOW()-INTERVAL 2 MINUTE
                   AND (checkedAt IS NULL OR checkedAt<=NOW()-INTERVAL 2 MINUTE)
                """, id, status) == 1;
    }

    private static void changed(int affected) {

        if (affected != 1) {
            throw refused("출금 상태가 변경되었습니다. 이력을 확인해 주세요.");
        }
    }

    private static WalletException refused(String reason) {

        return new WalletException("W004", reason);
    }
}
