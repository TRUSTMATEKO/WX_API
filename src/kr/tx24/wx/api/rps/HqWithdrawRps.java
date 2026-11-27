package kr.tx24.wx.api.rps;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import kr.tx24.lib.db.Create;
import kr.tx24.lib.db.DBTrx;
import kr.tx24.lib.db.DBUtils;
import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.db.Update;
import kr.tx24.lib.kms.KMSUtils;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;

/** 본사 외부 출금의 과금 상세 점유, Wallet 선차감, 은행 예약과 확정을 한 거래로 기록한다. */
public final class HqWithdrawRps {

    private static final Charset BANK_CHARSET = Charset.forName("KSC5601");
    private static final String TABLE = "WX.TRX_HQ_WITHDRAW";

    public SharedMap<String, Object> accept(SharedMap<String, Object> in) throws SQLException {

        DBTrx tx = new DBTrx();
        try {
            tx.begin();
            String hq = in.getString("hqId");
            SharedMap<String, Object> bank = tx.select("""
                    SELECT id hqId,acq,acqId acqSid,bankCd acqBankCd,accntNo acqAccount,key2 acqKey2,alarmBalance,
                           withdrawBankCd bankCd,withdrawAccntNo accntNo,withdrawAccntNoEnc cipher,withdrawHolder holder,
                           settingRev,status FROM WX.HQ_ACCNT WHERE id=? FOR UPDATE
                    """, hq).getRow(0);
            SharedMap<String, Object> wallet = tx.select("""
                    SELECT w.id,b.balance FROM WX.WALLET w JOIN WX.WALLET_BALANCE b ON b.walletId=w.id
                     WHERE w.hqWalletHqId=? AND w.status='사용' FOR UPDATE
                    """, hq).getRow(0);
            boolean fee = in.isEquals("kind","fee");
            String id = in.getString("id");
            long amount = in.getLong("amount");
            if (tx.select("""
                    SELECT id FROM WX.USER_ADMIN WHERE id=? AND status='사용' AND `2faStatus`='사용'
                       AND role BETWEEN 0 AND 1 FOR UPDATE
                    """, in.getString("userId")).getRow(0) == null) {
                throw new WalletException("W002","출금 실행 권한을 확인해 주세요.");
            }
            SharedMap<String, Object> previous = tx.select("SELECT * FROM WX.TRX_HQ_WITHDRAW WHERE id=? FOR UPDATE", id).getRow(0);
            if (previous != null) {
                for (String key : List.of("kind","hqId","amount","baseDay","mchtId","reason","settingRev","sender")) {
                    if (!previous.isEquals(key, in.getString(key))) throw refused("이미 다른 출금에 사용한 요청번호입니다.");
                }
                if (fee && tx.select("SELECT detailId FROM WX.HQ_WITHDRAW_FEE WHERE withdrawId=? AND detailId=?",
                        id, in.getString("detailId")).getRow(0) == null) throw refused("출금 대상이 다릅니다.");
                if (!previous.isEquals("regId", in.getString("userId"))) throw refused("이미 사용한 요청번호입니다.");
                tx.commit();
                return SharedMap.<String, Object>of().add("status", previous.getString("status"));
            }
            if (wallet == null || bank == null || !bank.isEquals("status","사용")
                    || !bank.isEquals("settingRev", in.getString("settingRev")) || bank.isBlank("cipher")
                    || bank.isBlank("holder") || !bank.getString("bankCd").matches("[0-9]{3}")) {
                throw refused("지정 출금 계좌와 모계좌 설정을 다시 확인해 주세요.");
            }
            byte[] encrypted = java.util.Base64.getDecoder().decode(bank.getString("cipher"));
            byte[] decoded = KMSUtils.decrypt(encrypted);
            if (decoded == null || java.util.Arrays.equals(encrypted, decoded)) throw refused("지정 계좌 복호화에 실패했습니다.");
            String plain = new String(decoded, java.nio.charset.StandardCharsets.US_ASCII);
            if (plain == null || !plain.matches("[0-9]{1,20}") || !bank.isEquals("acq","HYPHEN")) {
                throw refused("출금 계좌 또는 기관 설정을 확인해 주세요.");
            }
            String hhmm = time();
            SharedMap<String, Object> config = tx.select("""
                    SELECT CASE WHEN firmStopStart IS NULL AND firmStopEnd IS NULL THEN 1
                        WHEN firmStopStart<firmStopEnd THEN NOT (? >= firmStopStart AND ? < firmStopEnd)
                        WHEN firmStopStart>firmStopEnd THEN (? >= firmStopEnd AND ? < firmStopStart)
                        ELSE 0 END openFlag FROM WX.CONFIG WHERE id=1
                    """, hhmm, hhmm, hhmm, hhmm).getRow(0);
            if (config == null || config.getInt("openFlag") != 1) throw refused("송금 접수 중지 시간입니다.");
            int today = Integer.parseInt(LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE));
            String walletId = wallet.getString("id");
            SharedMap<String, Object> funds = tx.select("SELECT * FROM WX.V_HQ_WITHDRAW_LIMIT WHERE hqId=?", hq).getRow(0);
            if (funds == null || amount>funds.getLong(fee ? "payable" : "manualPayable")) throw refused("출금 가능액이 부족합니다.");

            String sender = in.getString("sender");
            List<SharedMap<String, Object>> details = List.of();
            if (fee) {
                if (in.getInt("baseDay") >= today) throw refused("전일까지 반영된 과금액만 출금할 수 있습니다.");
                details = tx.select("""
                        SELECT f.detailId,f.totalAmt,m.name FROM WX.V_HQ_WITHDRAW_FEE f
                          JOIN WX.MCHT m ON m.mchtId=f.mchtId
                         WHERE f.baseDay=? AND f.mchtId=? AND f.hqId=? AND f.hqWalletId=? AND f.withdrawId IS NULL
                        """, in.getInt("baseDay"), in.getString("mchtId"), hq, walletId).getRows();
                long total = 0;
                for (SharedMap<String, Object> detail : details) total = Math.addExact(total, detail.getLong("totalAmt"));
                boolean selected = details.stream().anyMatch(d -> d.isEquals("detailId", in.getString("detailId")));
                if (!selected || amount != total) throw refused("미출금 과금액이 변경되었습니다. 다시 조회해 주세요.");
                sender = CommonUtils.cut(details.get(0).getString("name"), 20, BANK_CHARSET);
            }
            if (sender.isBlank() || !BANK_CHARSET.newEncoder().canEncode(sender) || sender.getBytes(BANK_CHARSET).length > 20
                    || (!fee && sender.codePoints().anyMatch(Character::isISOControl)) || !in.isEquals("sender", sender)) {
                throw refused("받는 통장 표시명을 다시 확인해 주세요.");
            }
            tx.insert(new Create().table(TABLE).record("id", id).record("kind", in.getString("kind"))
                    .record("hqId", hq).record("walletId", walletId).record("baseDay", in.getInt("baseDay"))
                    .record("mchtId", in.getString("mchtId")).record("amount", amount).record("sender", sender)
                    .record("bankCd", bank.getString("bankCd")).record("accntNo", bank.getString("accntNo"))
                    .record("holder", bank.getString("holder")).record("settingRev", bank.getLong("settingRev"))
                    .record("acqSid", bank.getString("acqSid")).record("sourceBank", bank.getString("acqBankCd"))
                    .record("sourceAccount", bank.getString("acqAccount")).record("reason", in.getString("reason"))
                    .record("regId", in.getString("userId")).record("regDay", today));
            for (SharedMap<String, Object> detail : details) {
                tx.insert(new Create().table("WX.HQ_WITHDRAW_FEE").record("withdrawId", id).record("detailId", detail.getString("detailId")));
            }
            long sequence = tx.select("SELECT COALESCE(MAX(`sequence`),0)+1 seq FROM WX.TRX_WALLET WHERE walletId=?", walletId)
                    .getRowFirst().getLong("seq");
            tx.insert(new Create().table("WX.TRX_WALLET").record("id", IDUtils.getUnique("T"))
                    .record("walletId", walletId).record("ownerType","HQ").record("hqId", hq).record("refId", id)
                    .record("refType","TRX_HQ_WITHDRAW").record("`sequence`", sequence).record("trxType","출금")
                    .record("dtlType", fee ? "과금액출금" : "본사임의출금").record("status","처리중")
                    .record("amount",-amount).record("withdrawnAmt", amount).record("balance", wallet.getLong("balance"))
                    .record("balanceAfter", Math.subtractExact(wallet.getLong("balance"), amount)).record("bankCd", bank.getString("bankCd"))
                    .record("accntNo", bank.getString("accntNo")).record("holder", bank.getString("holder")).record("sender", sender)
                    .record("note", in.getString("reason")).record("trxDay", today).record("regDay", today).record("regId", in.getString("userId")));
            changed(tx.update("UPDATE WX.HQ_ACCNT SET reservedAmt=reservedAmt+? WHERE id=?", amount, hq));
            if (!fee) {
                changed(tx.update("""
                        INSERT INTO WX.AUDIT(id,unit,action,refType,refId,reason,regId,regDay)
                        VALUES(?,'HQ','HQ_MANUAL_WITHDRAW','TRX_HQ_WITHDRAW',?,?,?,?)
                        """, IDUtils.getRandom(32), DBUtils.cut(id, 32), in.getString("reason"),
                        in.getString("userId"), today));
            }
            tx.commit();
            return bank.add("destination", plain).add("sender", sender).add("send", true);
        } catch (RuntimeException | SQLException | Error ex) {
            tx.rollback(ex);
            throw ex;
        }
    }

    private static String time() {

        return java.time.LocalTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("HHmm"));
    }

    /** 기관 전문 쓰기 직전에만 호출한다. 접수 취소와 송신의 경합은 조건부 갱신으로 가른다. */
    public void markSending(String id, String reference) {

        if (!reference.matches("[0-9]{12}")) throw refused("기관 원거래번호를 확인해 주세요.");
        changed(new Update().table(TABLE).record("bankTrxId", reference).record("status","처리중")
                .where("id", id).where("status","접수").update());
    }

    public String finish(String id, String status, String code, String message, boolean unsentOnly) throws SQLException {

        DBTrx tx = new DBTrx();
        try {
            tx.begin();
            SharedMap<String, Object> owner = tx.select("SELECT hqId FROM WX.TRX_HQ_WITHDRAW WHERE id=?", id).getRow(0);
            if (owner == null) throw refused("출금을 찾을 수 없습니다.");
            String hq = owner.getString("hqId");
            SharedMap<String, Object> bank = tx.select("SELECT balance FROM WX.HQ_ACCNT WHERE id=? FOR UPDATE", hq).getRow(0);
            if (bank == null) throw refused("모계좌를 찾을 수 없습니다.");
            SharedMap<String, Object> row = tx.select("SELECT * FROM WX.TRX_HQ_WITHDRAW WHERE id=? FOR UPDATE", id).getRow(0);
            if (row == null) throw refused("출금을 찾을 수 없습니다.");
            String current = row.getString("status");
            if (List.of("성공","실패").contains(current) || (unsentOnly && !current.equals("접수"))) {
                tx.commit();
                return current;
            }
            long amount = row.getLong("amount");
            String walletStatus = status;
            if (!current.equals(status)) {
                changed(tx.update(new Update().table("WX.TRX_WALLET").record("status", walletStatus)
                        .record("resultCd", DBUtils.cut(code, 12)).record("resultMsg", DBUtils.cut(message, 128))
                        .where("refType","TRX_HQ_WITHDRAW").where("refId", id)));
            }
            if (!"미확인".equals(status)) {
                long before = bank.getLong("balance");
                boolean success = "성공".equals(status);
                changed(tx.update("UPDATE WX.HQ_ACCNT SET reservedAmt=reservedAmt-?,balance=balance-? WHERE id=? AND reservedAmt>=?",
                        amount, success ? amount : 0, hq, amount));
                if (success) {
                    int day = Integer.parseInt(LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE));
                    tx.update(new Update().table("WX.TRX_WALLET")
                            .record("hqBalanceBef", before).record("hqBalanceAft", Math.subtractExact(before, amount))
                            .record("acqTrxId", row.getString("bankTrxId")).record("acqResultCd", DBUtils.cut(code, 16))
                            .record("acqResultMsg", DBUtils.cut(message, 128)).where("refType","TRX_HQ_WITHDRAW").where("refId", id));
                    tx.insert(new Create().table("WX.TRX_HQ").record("id", IDUtils.getUnique("H")).record("hqId", hq)
                            .record("refType","TRX_HQ_WITHDRAW").record("refId", id).record("trxType","출금")
                            .record("dtlType", row.isEquals("kind","fee") ? "과금액출금" : "본사임의출금")
                            .record("amount",-amount).record("balance", before).record("balanceAfter", Math.subtractExact(before, amount))
                            .record("bankCd", row.getString("bankCd")).record("holder", row.getString("holder")).record("accntNo", row.getString("accntNo"))
                            .record("sender", row.getString("sender")).record("acq","HYPHEN").record("acqId", row.getString("acqSid"))
                            .record("acqTrxId", row.getString("bankTrxId")).record("acqResultCd", DBUtils.cut(code, 16))
                            .record("regId", row.getString("regId")).record("trxDay", day).record("regDay", day));
                } else {
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

    public List<SharedMap<String, Object>> pending(int limit) {

        RecordSet result = new Retrieve().select("""
                SELECT x.id,x.status,x.bankTrxId,x.hqId,x.acqSid,x.sourceBank acqBankCd,x.sourceAccount acqAccount,
                       h.key2 acqKey2,h.acq,h.alarmBalance FROM WX.TRX_HQ_WITHDRAW x
                  LEFT JOIN WX.HQ_ACCNT h ON h.id=x.hqId AND h.acqId=x.acqSid AND h.bankCd=x.sourceBank AND h.accntNo=x.sourceAccount
                 WHERE x.status IN ('접수','처리중','미확인') AND x.regDate<NOW()-INTERVAL 2 MINUTE
                   AND (x.checkedAt IS NULL OR x.checkedAt<=NOW()-INTERVAL 2 MINUTE)
                 ORDER BY COALESCE(x.checkedAt,x.regDate),x.id LIMIT ?
                """, List.of(limit));
        if (result == null) throw refused("미결 출금을 조회하지 못했습니다.");
        return result.getRows();
    }

    public boolean claimRecovery(String id, String status) {

        return new Update().update("""
                UPDATE WX.TRX_HQ_WITHDRAW SET checkedAt=NOW()
                 WHERE id=? AND status=? AND regDate<NOW()-INTERVAL 2 MINUTE
                   AND (checkedAt IS NULL OR checkedAt<=NOW()-INTERVAL 2 MINUTE)
                """, id, status) == 1;
    }

    private static void changed(int affected) {

        if (affected != 1) throw refused("출금 상태가 변경되었습니다. 이력을 확인해 주세요.");
    }

    private static WalletException refused(String reason) {

        return new WalletException("W004", reason);
    }
}
