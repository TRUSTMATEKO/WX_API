package kr.tx24.wx.ledger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import kr.tx24.lib.kms.KMSUtils;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static kr.tx24.wx.ledger.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.WxDatabaseRps.update;

/**
 * 입금 통지를 받아 Wallet을 찾아 반영하고, 찾지 못한 건은 운영자가 지정하게 남긴다.
 *
 * <p>입금은 두 경로로 들어온다. SMS는 입금자명으로 어느 Wallet인지 찾고, 가상계좌는 수취
 * 가상계좌번호가 곧 Wallet이다. 번호는 열쇠여서 후보가 하나뿐이고, 입금자명은 여러 Wallet에
 * 걸릴 수 있다. 그래서 SMS는 후보가 정확히 하나일 때만 자동으로 반영한다.</p>
 *
 * <p>찾지 못했거나 후보가 둘 이상이면 {@code 미매칭}으로 둔다. 본사 화면이 같은 모계좌의 Wallet
 * 목록을 보여 주고, 운영자가 하나를 지정하면 {@link #assign(SharedMap)}이 반영한다.</p>
 *
 * <p>돈은 모계좌에 들어온 것이므로 수신 시점에 모계좌 장부잔액과 모계좌 원장이 함께 올라간다.
 * 고객 Wallet 잔액은 Wallet이 정해질 때 오른다. 그래서 미매칭 금액만큼 모계좌 잔액이 Wallet 잔액
 * 합계보다 많고, 본사 운영 현황의 원장 대조가 그 차액을 미매칭 입금액으로 설명한다.</p>
 *
 * <p>잔액은 트리거가 반영한다. 이 클래스는 분개와 상태만 기록한다.</p>
 */
public final class WxDepositRps {

    private static final Logger logger = LoggerFactory.getLogger(WxDepositRps.class);

    /** 매칭 대기로 보는 상태. 반영이 끝난 완료와 종결된 반려는 다시 처리하지 않는다. */
    private static final List<String> OPEN = List.of("접수", "미매칭");

    /** 통지가 알려 주는 입금시간의 모양. */
    private static final DateTimeFormatter OCCURRED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 자동 반영의 작업 주체. 사람이 지정한 건과 구분해 기록한다. */
    private static final String ACTOR = "WX_API";

    private WxDepositRps() {

    }

    /**
     * 입금 수신: 통지 한 건을 기록하고 Wallet이 하나로 정해지면 바로 반영한다.
     *
     * <p>통지가 주는 값은 네 개다. 가상계좌는 가상계좌번호·입금시간·입금자명·금액, SMS는
     * 모계좌번호·입금자명·입금시간·금액이다. 모계좌와 Wallet은 그 값으로 WX가 찾는다. 통지하는
     * 쪽이 WX의 내부 식별자를 알 필요가 없다.</p>
     *
     * <p>같은 통지가 다시 와도 한 번만 기록한다. 통지에 식별자가 없으므로 모계좌·경로·입금시각·금액과
     * 입금 주체로 식별자를 만들어 유니크 키에 맡긴다. 그래서 재전송은 안전하고, 같은 초에 같은 금액이
     * 같은 이름으로 두 번 들어온 입금은 한 건으로 본다.</p>
     */
    public static SharedMap<String, Object> receive(SharedMap<String, Object> in) throws SQLException {

        String vaAccntNo   = in.getString("vaAccntNo").trim();
        String hqAccntNo   = in.getString("hqAccntNo").trim();
        String depositName = in.getString("depositName").trim();
        String occurredAt  = in.getString("occurredAt").trim();
        String bankCd      = in.getString("bankCd").trim();
        String accessIp    = in.getString("clientIp");
        long   amount      = in.getLong("amount");

        boolean virtual = !vaAccntNo.isEmpty();
        if (virtual == !hqAccntNo.isEmpty()) {
            throw new LedgerEx(LedgerCode.INVALID, "가상계좌번호 또는 모계좌번호 하나만 보내 주세요.");
        }
        if (amount <= 0) throw new LedgerEx(LedgerCode.INVALID, "입금액을 확인해 주세요.");
        if (depositName.isEmpty()) throw new LedgerEx(LedgerCode.INVALID, "입금자명을 확인해 주세요.");
        if (!occurredAt.matches("[0-9]{14}")) {
            throw new LedgerEx(LedgerCode.INVALID, "입금시간은 yyyyMMddHHmmss 형식입니다.");
        }
        if (virtual && !vaAccntNo.matches("[0-9]{8,32}")) {
            throw new LedgerEx(LedgerCode.INVALID, "가상계좌번호를 확인해 주세요.");
        }
        if (!virtual && !hqAccntNo.matches("[0-9-]{6,32}")) {
            throw new LedgerEx(LedgerCode.INVALID, "모계좌번호를 확인해 주세요.");
        }

        String sourceType = virtual ? "va" : "sms";
        LocalDateTime occurred = LocalDateTime.parse(occurredAt, OCCURRED);

        SharedMap<String, Object> out = transaction(db -> {
            // 통지가 가리키는 모계좌를 먼저 찾는다. 가상계좌는 재고가, SMS는 모계좌번호가 알려 준다.
            SharedMap<String, Object> target = virtual ? byVirtual(db, vaAccntNo) : byAccount(db, hqAccntNo, bankCd);
            String hqId       = target.getString("hqId");
            String externalId = externalId(sourceType, virtual ? vaAccntNo : hqAccntNo, occurredAt, amount,
                    depositName);

            // 같은 통지가 다시 왔는지 본다. 유니크 키와 같은 조건으로 잠근다.
            List<SharedMap<String, Object>> exists = rows(db, """
                    SELECT id,status,mchtId,walletId,walletTrxId,amount FROM WX.TRX_DEPOSIT
                     WHERE hqId=? AND sourceType=? AND externalId=? FOR UPDATE
                    """, hqId, sourceType, externalId);
            if (!exists.isEmpty()) {
                SharedMap<String, Object> row = exists.get(0);
                logger.info("이미 기록한 입금 통지다. 원장을 다시 움직이지 않는다: depositId={}, status={}",
                        row.getString("id"), row.getString("status"));
                return SharedMap.<String, Object>of()
                        .add("id", row.getString("id"))
                        .add("status", row.getString("status"))
                        .add("mchtId", row.getString("mchtId"))
                        .add("walletId", row.getString("walletId"))
                        .add("walletTrxId", row.getString("walletTrxId"))
                        .add("amount", row.getLong("amount"))
                        .add("matched", row.isEquals("status", "완료"))
                        .add("duplicate", true);
            }

            // 돈이 들어온 계좌다. 중지된 모계좌라도 들어온 돈은 기록해야 하므로 상태로 막지 않는다.
            // 기관 설정을 함께 읽는다. 반영이 끝나면 호출자가 이 프로필로 기관 잔액을 조회한다.
            List<SharedMap<String, Object>> banks = rows(db, """
                    SELECT id,name,bankCd,bankName,balance,status,
                           acq,acqId,bankCd AS acqBankCd,accntNo AS acqAccount,COALESCE(key2,'') AS acqKey2
                      FROM WX.HQ_ACCNT WHERE id=? FOR UPDATE
                    """, hqId);
            if (banks.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "모계좌를 확인해 주세요.");
            SharedMap<String, Object> bank = banks.get(0);
            if (!bank.isEquals("status", "사용")) {
                logger.warn("사용 중이 아닌 모계좌에 입금이 들어왔다. 기록은 남기고 운영자 확인이 필요하다: hqId={}, status={}",
                        hqId, bank.getString("status"));
            }

            // Wallet 을 정한다. 가상계좌는 재고가 가리키는 Wallet, SMS는 등록한 입금자명으로 찾는다.
            String resolved = virtual ? target.getString("walletId") : byDepositName(db, hqId, depositName);
            SharedMap<String, Object> wallet = resolved.isEmpty() ? null : lockedWallet(db, resolved, hqId);
            if (!resolved.isEmpty() && wallet == null) {
                logger.warn("판정한 Wallet 을 잠그지 못해 미매칭으로 둔다: hqId={}, walletId={}", hqId, resolved);
            }

            LocalDateTime postedAt = WxPostingRps.postingTime(db);
            int    today     = WxPostingRps.businessDay(postedAt);
            String depositId = IDUtils.getUnique("D");
            String status    = wallet == null ? "미매칭" : "완료";
            String payload   = in.getString("payload");
            String hash      = CommonUtils.hashSHA256(sourceType + "|" + hqId + "|" + externalId);

            update(db, """
                    INSERT INTO WX.TRX_DEPOSIT(id,hqId,sourceType,externalId,vaAccntNo,bankCd,bankName,
                                               depositName,amount,occurredAt,mchtId,walletId,status,payloadHash,
                                               payloadEnc,matchedBy,matchedAt,regId,regDay,regDate)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, depositId, hqId, sourceType, externalId, vaAccntNo,
                    bankCd.isEmpty() ? bank.getString("bankCd") : bankCd,
                    bank.getString("bankName"), depositName, amount, occurred,
                    wallet == null ? null : wallet.getString("mchtId"),
                    wallet == null ? null : wallet.getString("walletId"),
                    status, hash,
                    payload.isBlank() ? null : KMSUtils.encrypt(payload),
                    wallet == null ? null : ACTOR,
                    wallet == null ? null : postedAt,
                    ACTOR, today, postedAt);

            // 모계좌 장부잔액은 은행에 돈이 들어온 이 시점에 오른다. Wallet 지정과는 별개다.
            hqLedger(db, bank, depositId, amount, depositName, postedAt, today);

            SharedMap<String, Object> result = SharedMap.<String, Object>of()
                    .add("id", depositId)
                    .add("status", status)
                    .add("amount", amount)
                    .add("matched", wallet != null)
                    .add("duplicate", false)
                    // 기관 잔액 동기화에 쓴다. 고객사·화면 응답에는 담지 않는다.
                    .add("hqId", hqId);
            SharedMap<String, Object> profile = WxTransferRps.upstream(hqId, bank);
            if (profile != null) result.add("upstream", profile);
            if (wallet == null) {
                logger.info("입금 Wallet 을 찾지 못해 미매칭으로 둔다: depositId={}, hqId={}, sourceType={}, amount={}",
                        depositId, hqId, sourceType, amount);
                audit(db, "DEPOSIT_RECEIVE", null, depositId, "입금 수신, Wallet 미매칭", accessIp, ACTOR, today,
                        SharedMap.<String, Object>of(),
                        SharedMap.<String, Object>of().add("status", status).add("amount", amount));
                return result;
            }

            String ledgerId = credit(db, wallet, depositId, amount, depositName,
                    virtual ? "가상계좌 입금 자동 매칭" : "입금자명 입금 자동 매칭",
                    ACTOR, postedAt, today);
            update(db, "UPDATE WX.TRX_DEPOSIT SET walletTrxId=? WHERE id=?", ledgerId, depositId);
            audit(db, "DEPOSIT_RECEIVE", wallet.getString("mchtId"), depositId,
                    "입금 수신, Wallet 자동 매칭", accessIp, ACTOR, today,
                    SharedMap.<String, Object>of(),
                    SharedMap.<String, Object>of().add("status", status).add("amount", amount)
                            .add("walletId", wallet.getString("walletId")).add("walletTrxId", ledgerId));
            logger.info("입금 자동 매칭 완료: depositId={}, sourceType={}, mchtId={}, walletId={}, walletTrxId={}, amount={}",
                    depositId, sourceType, wallet.getString("mchtId"), wallet.getString("walletId"),
                    ledgerId, amount);
            result.add("mchtId", wallet.getString("mchtId"))
                    .add("walletId", wallet.getString("walletId"))
                    .add("walletTrxId", ledgerId)
                    .add("balanceAfter", wallet.getLong("balance") + amount)
                    // 고객사 통지는 확정과 같은 트랜잭션에 적재한다. 발송은 WX_TASK 가 한다.
                    .add("trxId", depositId)
                    .add("depositName", depositName)
                    .add("sourceType", sourceType);
            WxWebhookRps.enqueue(db, result, WxWebhookRps.DEPOSIT_CREDITED, postedAt);
            return result;
        });
        // 커밋 뒤 발송 큐에 올린다. 미매칭이면 적재한 것이 없어 아무것도 올라가지 않는다.
        WxWebhookRps.publish(out);
        return out;
    }

    /**
     * 가상계좌 통지가 가리키는 모계좌와 Wallet.
     *
     * <p>재고에 없는 번호는 받지 않는다. 그 번호의 돈이 어느 모계좌로 들어왔는지 알 수 없고,
     * 모르는 채로 기록하면 모계좌 잔액이 맞지 않는다. 운영자가 재고에 등록한 뒤 다시 보내면 된다.</p>
     */
    private static SharedMap<String, Object> byVirtual(Connection db, String vaAccntNo) throws SQLException {

        List<SharedMap<String, Object>> found = rows(db, """
                SELECT hqId,COALESCE(walletId,'') AS walletId FROM WX.VA_ACCNT
                 WHERE accntNo=? AND status='사용'
                """, vaAccntNo);
        if (found.isEmpty()) {
            logger.warn("재고에 없는 가상계좌로 입금 통지가 왔다: accntNo 자릿수={}", vaAccntNo.length());
            throw new LedgerEx(LedgerCode.NOT_FOUND, "등록되지 않은 가상계좌입니다. 가상계좌 재고를 확인해 주세요.");
        }
        return found.get(0);
    }

    /**
     * SMS 통지가 가리키는 모계좌.
     *
     * <p>모계좌번호는 하이픈을 떼고 비교한다. 은행마다 표기가 달라 통지와 등록값의 모양이 같지 않다.
     * 같은 번호가 두 은행에 있으면 은행 코드를 함께 받아야 한다.</p>
     */
    private static SharedMap<String, Object> byAccount(Connection db, String hqAccntNo, String bankCd)
            throws SQLException {

        List<SharedMap<String, Object>> found = rows(db, """
                SELECT id AS hqId,'' AS walletId FROM WX.HQ_ACCNT
                 WHERE REPLACE(accntNo,'-','')=REPLACE(?,'-','') AND (?='' OR bankCd=?)
                 ORDER BY status,id LIMIT 2
                """, hqAccntNo, bankCd, bankCd);
        if (found.isEmpty()) {
            logger.warn("등록되지 않은 모계좌번호로 입금 통지가 왔다: 자릿수={}, bankCd={}", hqAccntNo.length(), bankCd);
            throw new LedgerEx(LedgerCode.NOT_FOUND, "등록되지 않은 모계좌번호입니다.");
        }
        if (found.size() > 1) {
            logger.warn("같은 모계좌번호가 둘 이상이어서 입금 통지를 받지 않는다: 자릿수={}", hqAccntNo.length());
            throw new LedgerEx(LedgerCode.CONFLICT, "같은 번호의 모계좌가 둘 이상입니다. 은행 코드를 함께 보내 주세요.");
        }
        return found.get(0);
    }

    /**
     * SMS 입금자명으로 Wallet을 찾는다.
     *
     * <p>통지의 이름은 잘려 들어올 수 있다. 은행 적요가 짧거나 중계가 앞부분만 넘기기 때문이다.
     * 그래서 양쪽으로 본다. 등록한 입금자명이 통지 이름에 들어 있거나, 통지 이름이 등록한 입금자명에
     * 들어 있으면 후보다. 판정은 DB의 {@code LIKE}로 하고 애플리케이션에서 이름을 자르지 않는다.</p>
     *
     * <p>후보가 정확히 하나일 때만 돌려준다. 둘 이상이면 동명이인이거나 짧은 이름이 여러 규칙에 걸린
     * 것이므로 사람이 확인한다. 한 자 이름은 거꾸로 보지 않는다. 너무 많이 걸린다.</p>
     *
     * @return 정해진 Wallet ID, 또는 정해지지 않으면 공백
     */
    private static String byDepositName(Connection db, String hqId, String depositName) throws SQLException {

        boolean similar = depositName.length() >= 2;
        String sql = """
                SELECT DISTINCT W.id AS walletId
                  FROM WX.MCHAT_MATCH C JOIN WX.WALLET W ON W.id=C.walletId
                 WHERE C.hqId=? AND C.status='사용' AND C.matchType='depositor'
                   AND W.ownerType='MCHT' AND W.status='사용'
                   AND (? LIKE CONCAT('%',C.matchValue,'%')
                """
                + (similar ? " OR C.matchValue LIKE CONCAT('%',?,'%'))" : ")")
                + " LIMIT 2";
        List<SharedMap<String, Object>> found = similar
                ? rows(db, sql, hqId, depositName, depositName)
                : rows(db, sql, hqId, depositName);
        if (found.size() == 1) return found.get(0).getString("walletId");
        if (found.size() > 1) {
            logger.warn("입금자명 후보가 둘 이상이어서 자동 매칭하지 않는다: hqId={}, 후보={}건", hqId, found.size());
        }
        return "";
    }

    /**
     * 통지의 멱등 식별자.
     *
     * <p>통지에 식별자가 없으므로 내용으로 만든다. 같은 계좌·같은 시각·같은 금액·같은 이름이면 같은
     * 통지로 본다. 이름이 길면 칼럼 길이를 넘기므로 해시 앞자리로 줄인다.</p>
     */
    private static String externalId(String sourceType, String accntNo, String occurredAt, long amount,
                                      String depositName) {

        String name = depositName.length() > 32
                ? CommonUtils.hashSHA256(depositName).substring(0, 16) : depositName;
        String value = sourceType + ":" + accntNo + ":" + occurredAt + ":" + amount + ":" + name;
        return value.length() <= 128 ? value
                : sourceType + ":" + accntNo + ":" + occurredAt + ":" + amount + ":"
                        + CommonUtils.hashSHA256(depositName).substring(0, 16);
    }

    /**
     * 분개 대상 Wallet을 잠그고 잔액을 읽는다.
     *
     * <p>자동 매칭과 운영자 지정이 같은 조건을 쓴다. 같은 모계좌에 귀속된 사용 중인 고객사 Wallet만
     * 입금을 받는다.</p>
     *
     * @return 잠근 Wallet 한 건, 또는 조건에 맞지 않으면 {@code null}
     */
    private static SharedMap<String, Object> lockedWallet(Connection db, String walletId, String hqId)
            throws SQLException {

        List<SharedMap<String, Object>> found = rows(db, """
                SELECT W.id AS walletId,W.mchtId,W.hqId,B.balance
                  FROM WX.WALLET W JOIN WX.WALLET_BALANCE B ON B.walletId=W.id
                 WHERE W.id=? AND W.hqId=? AND W.ownerType='MCHT' AND W.status='사용'
                 FOR UPDATE
                """, walletId, hqId);
        return found.size() == 1 ? found.get(0) : null;
    }

    /**
     * 모계좌 잔액을 기관이 알려준 값으로 맞춘다.
     *
     * <p>입금 반영이 끝난 뒤 호출자가 기관 잔액을 조회해 넘긴다. 들어온 금액만큼 더하는 것으로는
     * 시간차를 메울 수 없다. 통지가 늦게 오거나 순서가 바뀌거나, WX 가 모르는 입출금(기관 수수료,
     * 다른 경로의 출금)이 있으면 장부잔액이 실제와 벌어진다. 그래서 수신 때마다 실제 값으로 맞춘다.</p>
     *
     * <p>차이가 있으면 <b>모계좌 원장에 조정 분개를 남긴다.</b> 잔액만 덮어쓰면 원장의 잔액 흐름이
     * 끊기고, 운영 현황의 원장 대조가 설명되지 않는 차액을 보여 준다. 조정 분개의 금액이 그 차이를
     * 설명한다. 차이가 0이면 아무것도 쓰지 않는다.</p>
     *
     * <p>조정은 고객 Wallet 잔액을 건드리지 않는다. 모계좌에 돈이 더 있거나 덜 있다는 사실만 기록한다.
     * 그 돈의 주인을 정하는 것은 입금 매칭이 할 일이다.</p>
     *
     * @param hqId      모계좌 ID
     * @param depositId 이 동기화를 일으킨 입금번호. 같은 입금으로 두 번 조정하지 않도록 열쇠로 쓴다
     * @param reported  기관이 알려준 실제 잔액
     * @return 조정 전후와 차이. 조정하지 않았으면 {@code changed=false}
     */
    public static SharedMap<String, Object> syncBalance(String hqId, String depositId, long reported)
            throws SQLException {

        if (hqId == null || hqId.isBlank() || depositId == null || depositId.isBlank()) {
            throw new LedgerEx(LedgerCode.INVALID, "모계좌 잔액 동기화 입력을 확인해 주세요.");
        }
        return transaction(db -> {
            List<SharedMap<String, Object>> banks = rows(db, """
                    SELECT id,bankCd,bankName,balance FROM WX.HQ_ACCNT WHERE id=? FOR UPDATE
                    """, hqId);
            if (banks.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "모계좌를 확인해 주세요.");
            SharedMap<String, Object> bank = banks.get(0);

            long before = bank.getLong("balance");
            long delta  = Math.subtractExact(reported, before);
            SharedMap<String, Object> result = SharedMap.<String, Object>of()
                    .add("hqId", hqId)
                    .add("before", before)
                    .add("reported", reported)
                    .add("delta", delta)
                    .add("changed", false);
            if (delta == 0) {
                logger.debug("모계좌 잔액이 기관 값과 같다: hqId={}, balance={}", hqId, before);
                return result;
            }

            LocalDateTime postedAt = WxPostingRps.postingTime(db);
            int today = WxPostingRps.businessDay(postedAt);
            // 같은 입금으로 두 번 조정하지 않는다. UK_TRX_HQ_ACCNT_2(hqId,refType,refId) 가 막는다.
            int written = update(db, """
                    INSERT INTO WX.TRX_HQ(id,hqId,status,trxType,dtlType,refType,refId,amount,balance,balanceAfter,
                                          bankCd,bankName,summary,trxDay,trxDate,regId,regDay)
                    VALUES(?,?,'성공',?,'잔액조정','HQ_BALANCE_SYNC',?,?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE id=id
                    """, IDUtils.getUnique("H"), hqId, delta > 0 ? "입금" : "출금", depositId, delta, before,
                    reported, bank.getString("bankCd"), bank.getString("bankName"),
                    "입금 수신 후 기관 잔액 동기화", today, postedAt, ACTOR, today);
            if (written != 1) {
                logger.info("이미 조정한 입금이다. 다시 맞추지 않는다: hqId={}, depositId={}", hqId, depositId);
                return result;
            }
            if (update(db, "UPDATE WX.HQ_ACCNT SET balance=? WHERE id=?", reported, hqId) != 1) {
                throw new SQLException("모계좌 잔액 동기화 반영에 실패했습니다.");
            }
            logger.warn("모계좌 장부잔액을 기관 값으로 맞췄다. 차이를 확인해야 한다: "
                            + "hqId={}, 장부={}, 기관={}, 차이={}, depositId={}",
                    hqId, before, reported, delta, depositId);
            // SharedMap.add 는 같은 키를 두 번 받지 않는다. 바꿀 때는 put 을 쓴다.
            result.put("changed", true);
            return result;
        });
    }

    /** 입금 지정: 입금 한 건을 지정 Wallet의 확정 원장에 입금으로 반영한다. */
    public static SharedMap<String, Object> assign(SharedMap<String, Object> in) throws SQLException {

        String id       = WxPostingRps.field(in, "depositId", 32);
        String walletId = WxPostingRps.field(in, "walletId", 32);
        String actor    = WxPostingRps.field(in, "userId", 24);
        String reason   = WxPostingRps.field(in, "reason", 128);
        String accessIp = in.getString("clientIp");

        SharedMap<String, Object> out = transaction(db -> {
            SharedMap<String, Object> deposit = locked(db, id);
            long amount = deposit.getLong("amount");
            if (amount <= 0) throw new LedgerEx(LedgerCode.INVALID, "입금액을 확인해 주세요.");

            SharedMap<String, Object> wallet = lockedWallet(db, walletId, deposit.getString("hqId"));
            if (wallet == null) {
                throw new LedgerEx(LedgerCode.CONFLICT,
                        "같은 모계좌에 귀속된 사용 중인 고객사 Wallet만 지정할 수 있습니다.");
            }

            LocalDateTime postedAt = WxPostingRps.postingTime(db);
            int today  = WxPostingRps.businessDay(postedAt);
            long after = Math.addExact(wallet.getLong("balance"), amount);

            String ledgerId = credit(db, wallet, id, amount, deposit.getString("depositName"), reason,
                    actor, postedAt, today);

            changed(update(db, """
                    UPDATE WX.TRX_DEPOSIT SET status='완료',mchtId=?,walletId=?,walletTrxId=?,matchedBy=?,
                           matchedAt=NOW(),udtId=? WHERE id=? AND status IN ('접수','미매칭')
                    """, wallet.getString("mchtId"), walletId, ledgerId, actor, actor, id));

            audit(db, "DEPOSIT_ASSIGN", wallet.getString("mchtId"), id, reason, accessIp, actor, today,
                    SharedMap.<String, Object>of().add("status", deposit.getString("status")),
                    SharedMap.<String, Object>of().add("status", "완료").add("walletId", walletId)
                            .add("walletTrxId", ledgerId).add("amount", amount));

            logger.info("입금 Wallet 지정 완료: depositId={}, mchtId={}, walletId={}, walletTrxId={}, amount={}, actor={}",
                    id, wallet.getString("mchtId"), walletId, ledgerId, amount, actor);
            SharedMap<String, Object> result = SharedMap.<String, Object>of()
                    .add("id", id)
                    .add("mchtId", wallet.getString("mchtId"))
                    .add("walletId", walletId)
                    .add("walletTrxId", ledgerId)
                    .add("amount", amount)
                    .add("balanceAfter", after)
                    // 자동 매칭과 같은 이벤트를 보낸다. 고객사는 사람이 지정했는지 알 필요가 없다.
                    .add("trxId", id)
                    .add("status", "완료")
                    .add("depositName", deposit.getString("depositName"))
                    .add("sourceType", deposit.getString("sourceType"));
            WxWebhookRps.enqueue(db, result, WxWebhookRps.DEPOSIT_CREDITED, postedAt);
            return result;
        });
        WxWebhookRps.publish(out);
        return out;
    }

    /** 입금 반려: 매칭하지 않고 사유를 남겨 입금을 종결한다. 원장 분개는 없다. */
    public static SharedMap<String, Object> reject(SharedMap<String, Object> in) throws SQLException {

        String id       = WxPostingRps.field(in, "depositId", 32);
        String actor    = WxPostingRps.field(in, "userId", 24);
        String reason   = WxPostingRps.field(in, "reason", 128);
        String accessIp = in.getString("clientIp");

        return transaction(db -> {
            SharedMap<String, Object> deposit = locked(db, id);
            int today = WxPostingRps.businessDay(WxPostingRps.postingTime(db));
            changed(update(db, """
                    UPDATE WX.TRX_DEPOSIT SET status='반려',matchedBy=?,matchedAt=NOW(),udtId=?
                     WHERE id=? AND status IN ('접수','미매칭')
                    """, actor, actor, id));
            audit(db, "DEPOSIT_REJECT", deposit.getString("mchtId"), id, reason, accessIp, actor, today,
                    SharedMap.<String, Object>of().add("status", deposit.getString("status")),
                    SharedMap.<String, Object>of().add("status", "반려"));
            logger.info("입금 반려 완료: depositId={}, actor={}", id, actor);
            return SharedMap.<String, Object>of().add("id", id).add("status", "반려");
        });
    }

    /**
     * 고객 Wallet 입금 분개.
     *
     * <p>자동 매칭과 운영자 지정이 같은 분개를 쓴다. 두 경로가 다른 모양으로 기록되면 집계와 대조가
     * 갈라진다. 장부잔액은 {@code TR_TRX_WALLET_BALANCE_AI} 트리거가 반영한다.</p>
     *
     * @return 만든 Wallet 원장 거래 ID
     */
    private static String credit(Connection db, SharedMap<String, Object> wallet, String depositId, long amount,
                                  String depositName, String summary, String actor, LocalDateTime postedAt,
                                  int today) throws SQLException {

        String walletId = wallet.getString("walletId");
        long   before   = wallet.getLong("balance");
        long   after    = Math.addExact(before, amount);
        // 잔액은 잠근 WALLET_BALANCE 가 기준이다. 순번만 Wallet 단위 최대값에서 이어 붙인다.
        long sequence = rows(db,
                "SELECT COALESCE(MAX(`sequence`),0)+1 AS nextSequence FROM WX.TRX_WALLET WHERE walletId=?",
                walletId).get(0).getLong("nextSequence");

        String ledgerId = IDUtils.getUnique("T");
        changed(update(db, """
                INSERT INTO WX.TRX_WALLET(id,ownerType,mchtId,walletId,hqId,refType,refId,refSeq,`sequence`,
                                          trxType,dtlType,status,amount,depositAmt,withdrawnAmt,balance,balanceAfter,
                                          sender,summary,trxDay,trxDate,regId,regDay)
                VALUES(?,'MCHT',?,?,?,'TRX_DEPOSIT',?,1,?,'입금','일반입금','성공',?,?,0,?,?,LEFT(?,24),?,?,?,?,?)
                """, ledgerId, wallet.getString("mchtId"), walletId, wallet.getString("hqId"), depositId, sequence,
                amount, amount, before, after, depositName, summary, today, postedAt, actor, today));
        // WALLET_BALANCE의 balance·lastWalletIdx·available은 TR_TRX_WALLET_BALANCE_AI 트리거가 반영한다.
        return ledgerId;
    }

    /**
     * 모계좌 입금 원장.
     *
     * <p>은행에 돈이 들어온 사실을 모계좌 원장에 남기고 장부잔액을 올린다. 같은 입금으로 두 번
     * 기록되지 않도록 {@code UK_TRX_HQ_ACCNT_2(hqId,refType,refId)}가 막는다.</p>
     */
    private static void hqLedger(Connection db, SharedMap<String, Object> bank, String depositId, long amount,
                                  String depositName, LocalDateTime postedAt, int today) throws SQLException {

        String hqId   = bank.getString("id");
        long   before = bank.getLong("balance");
        changed(update(db, """
                INSERT INTO WX.TRX_HQ(id,hqId,status,trxType,dtlType,refType,refId,amount,balance,balanceAfter,
                                      bankCd,bankName,sender,trxDay,trxDate,regId,regDay)
                VALUES(?,?,'성공','입금','일반입금','TRX_DEPOSIT',?,?,?,?,?,?,LEFT(?,24),?,?,?,?)
                """, IDUtils.getUnique("H"), hqId, depositId, amount, before, Math.addExact(before, amount),
                bank.getString("bankCd"), bank.getString("bankName"), depositName, today, postedAt,
                ACTOR, today));
        if (update(db, "UPDATE WX.HQ_ACCNT SET balance=balance+? WHERE id=?", amount, hqId) != 1) {
            throw new SQLException("모계좌 입금 반영에 실패했습니다.");
        }
        logger.debug("모계좌 입금 원장: hqId={}, before={}, amount={}", hqId, before, amount);
    }

    /** 지정·반려 공통: 처리 대상 입금을 잠그고 아직 대기 상태인지 확인한다. */
    private static SharedMap<String, Object> locked(Connection db, String id) throws SQLException {

        List<SharedMap<String, Object>> found = rows(db,
                "SELECT id,mchtId,hqId,amount,status,depositName,sourceType FROM WX.TRX_DEPOSIT WHERE id=? FOR UPDATE", id);
        if (found.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "입금 내역을 찾을 수 없습니다.");
        if (!OPEN.contains(found.get(0).getString("status"))) {
            logger.info("이미 처리된 입금: depositId={}, status={}", id, found.get(0).getString("status"));
            throw new LedgerEx(LedgerCode.CONFLICT, "이미 처리된 입금입니다.");
        }
        return found.get(0);
    }

    /** 자금 처리 공통: 변경 사유와 접근 IP를 포함한 감사 이력을 같은 트랜잭션에 기록한다. */
    private static void audit(Connection db, String action, String merchant, String ref, String reason,
                               String accessIp, String actor, int today, SharedMap<String, Object> before,
                               SharedMap<String, Object> after) throws SQLException {

        changed(update(db, """
                INSERT INTO WX.AUDIT(id,mchtId,unit,action,refType,refId,reason,beforeJson,afterJson,accessIp,
                                     regId,regDay)
                VALUES(?,?,'HQ',?,'TRX_DEPOSIT',?,?,?,?,NULLIF(?,''),?,?)
                """, IDUtils.getRandom(32), merchant, action, ref, reason,
                JacksonUtils.get().toJson(before), JacksonUtils.get().toJson(after), accessIp, actor, today));
    }

    /** 한 행만 바뀌어야 하는 처리. 동시에 같은 입금을 처리한 경우를 상태 충돌로 돌린다. */
    private static void changed(int count) {

        if (count != 1) throw new LedgerEx(LedgerCode.CONFLICT, "입금 상태가 바뀌었습니다. 목록을 새로 조회해 주세요.");
    }

    /** 입금 처리 공통: 분개와 상태·감사 이력을 한 트랜잭션에 담는다. */
    private static SharedMap<String, Object> transaction(Work work) throws SQLException {

        try (Connection db = WxDatabaseRps.open()) {
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                SharedMap<String, Object> result = work.run(db);
                db.commit();
                return result;
            } catch (RuntimeException | SQLException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    /** 열린 업무 트랜잭션에서 수행하는 입금 처리. */
    private interface Work {

        SharedMap<String, Object> run(Connection db) throws SQLException;
    }
}
