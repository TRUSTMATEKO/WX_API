package kr.tx24.wx.ledger.rps;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.lib.redis.RedisTextUtils;
import kr.tx24.wx.ledger.enm.LedgerCode;
import kr.tx24.wx.ledger.ex.LedgerEx;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static kr.tx24.wx.ledger.rps.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.rps.WxDatabaseRps.update;

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
    private static final List<String> OPEN_LIST = List.of("접수", "미매칭");

    /** 통지가 알려 주는 입금시간의 모양. */

    /**
     * 같은 입금으로 보는 시간 폭(분).
     *
     * <p>외부 식별자는 초까지 같아야 걸린다. 사람이 통장을 보고 적는 시각은 분까지가 보통이고
     * 통지가 주는 초와 어긋난다. 그래서 수동 입금과 통지가 겹치는지는 이 폭 안에서 같은
     * 모계좌·금액·입금자명인지로 한 번 더 본다.</p>
     */
    private static final long DUP_MINUTES = 5L;

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
    public static SharedMap<String, Object> receive(SharedMap<String, Object> inMap) throws SQLException {

        String vaAccntNo   = inMap.getString("vaAccntNo").trim();
        String hqAccntNo   = inMap.getString("hqAccntNo").trim();
        String depositName = inMap.getString("depositName").trim();
        String occurredAt  = inMap.getString("occurredAt").trim();
        String bankCd      = inMap.getString("bankCd").trim();
        String accessIp    = inMap.getString("clientIp");
        long   amount      = inMap.getLong("amount");

        boolean virtual = !vaAccntNo.isEmpty();
        if (virtual == !hqAccntNo.isEmpty()) {
            throw new LedgerEx(LedgerCode.INVALID, "가상계좌번호 또는 모계좌번호 하나만 보내 주세요.");
        }
        if (amount <= 0) throw new LedgerEx(LedgerCode.INVALID, "입금액을 확인해 주세요.");
        if (depositName.isEmpty()) throw new LedgerEx(LedgerCode.INVALID, "입금자명을 확인해 주세요.");
        if (virtual && !vaAccntNo.matches("[0-9]{8,32}")) {
            throw new LedgerEx(LedgerCode.INVALID, "가상계좌번호를 확인해 주세요.");
        }
        if (!virtual && !hqAccntNo.matches("[0-9-]{6,32}")) {
            throw new LedgerEx(LedgerCode.INVALID, "모계좌번호를 확인해 주세요.");
        }

        String sourceType = virtual ? "va" : "sms";
        LocalDateTime occurred = depositTime(occurredAt);

        SharedMap<String, Object> outMap = transaction(db -> {
            // 통지가 가리키는 모계좌를 먼저 찾는다. 가상계좌는 재고가, SMS는 모계좌번호가 알려 준다.
            SharedMap<String, Object> targetMap  = virtual ? byVirtual(db, vaAccntNo) : byAccount(db, hqAccntNo, bankCd);
            String                    hqId       = targetMap.getString("hqId");
            String                    externalId = externalId(sourceType, virtual ? vaAccntNo : hqAccntNo, occurredAt, amount,
                    depositName);

            // 같은 통지가 다시 왔는지 본다. 유니크 키와 같은 조건으로 잠근다.
            List<SharedMap<String, Object>> existsList = rows(db, new Retrieve("WX.TRX_DEPOSIT")
                    .columns("id,status,mchtId,walletId,walletTrxId,amount")
                    .where("hqId=? AND sourceType=? AND externalId=?")
                    .forUpdate().build(), hqId, sourceType, externalId);
            if (!existsList.isEmpty()) {
                SharedMap<String, Object> rowMap = existsList.get(0);
                logger.info("이미 기록한 입금 통지다. 원장을 다시 움직이지 않는다: depositId={}, status={}",
                        rowMap.getString("id"), rowMap.getString("status"));
                return SharedMap.<String, Object>of()
                        .add("id", rowMap.getString("id"))
                        .add("status", rowMap.getString("status"))
                        .add("mchtId", rowMap.getString("mchtId"))
                        .add("walletId", rowMap.getString("walletId"))
                        .add("walletTrxId", rowMap.getString("walletTrxId"))
                        .add("amount", rowMap.getLong("amount"))
                        .add("matched", rowMap.isEquals("status", "완료"))
                        .add("duplicate", true);
            }

            // 운영자가 이미 수동으로 넣은 입금과 겹치는지 본다. 수동 입금은 통지가 오지 않아 넣은
            // 것이므로, 늦게 도착한 통지가 같은 돈을 두 번 올릴 수 있다. 초가 어긋나면 위의 유니크
            // 키를 비켜 가므로 분 단위로 같은 금액·입금자명을 본다. 사람이 넣은 건만 본다.
            //
            // 겹치면 원장을 움직이지 않는다. 두 쪽 다 틀릴 수 있지만 되돌릴 수 있는 쪽을 고른다 —
            // 빠뜨린 입금은 원장 대조에서 차액으로 드러나 수동 입금으로 메울 수 있고, 두 번 올린
            // 돈은 고객사가 출금해 가면 돌아오지 않는다.
            List<SharedMap<String, Object>> manualList = rows(db, """
                    SELECT id,status,mchtId,walletId,walletTrxId,amount FROM WX.TRX_DEPOSIT
                     WHERE hqId=? AND amount=? AND depositName=? AND regId<>? AND status<>'반려'
                       AND occurredAt BETWEEN ? AND ?
                    """, hqId, amount, depositName, ACTOR,
                    occurred.minusMinutes(DUP_MINUTES), occurred.plusMinutes(DUP_MINUTES));
            if (!manualList.isEmpty()) {
                SharedMap<String, Object> rowMap = manualList.get(0);
                logger.warn("운영자가 수동으로 넣은 입금과 겹친다. 원장을 움직이지 않는다: "
                        + "depositId={}, status={}, hqId={}, amount={}", rowMap.getString("id"),
                        rowMap.getString("status"), hqId, amount);
                return SharedMap.<String, Object>of()
                        .add("id", rowMap.getString("id"))
                        .add("status", rowMap.getString("status"))
                        .add("mchtId", rowMap.getString("mchtId"))
                        .add("walletId", rowMap.getString("walletId"))
                        .add("walletTrxId", rowMap.getString("walletTrxId"))
                        .add("amount", rowMap.getLong("amount"))
                        .add("matched", rowMap.isEquals("status", "완료"))
                        .add("duplicate", true);
            }

            // 돈이 들어온 계좌다. 중지된 모계좌라도 들어온 돈은 기록해야 하므로 상태로 막지 않는다.
            // 기관 설정을 함께 읽는다. 반영이 끝나면 호출자가 이 프로필로 기관 잔액을 조회한다.
            List<SharedMap<String, Object>> banksList = rows(db, new Retrieve("WX.HQ_ACCNT")
                    .columns("id,name,bankCd,balance,status,settingRev,acq,acqId,bankCd AS acqBankCd")
                    .column("accntNo AS acqAccount,COALESCE(key2,'') AS acqKey2")
                    .where("id=?")
                    .forUpdate().build(), hqId);
            if (banksList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "모계좌를 확인해 주세요.");
            SharedMap<String, Object> bankMap = banksList.get(0);
            if (!bankMap.isEquals("status", "사용")) {
                logger.warn("사용 중이 아닌 모계좌에 입금이 들어왔다. 기록은 남기고 운영자 확인이 필요하다: hqId={}, status={}",
                        hqId, bankMap.getString("status"));
            }

            // Wallet 을 정한다. 가상계좌는 재고가 가리키는 고객사 Wallet 이고, SMS 는 입금자명이
            // 고객사 규칙에 걸리면 그 고객사 Wallet, 모계좌 기타 입금 문구에 걸리면 본사 Wallet 이다.
            SharedMap<String, Object> judgedMap = virtual
                    ? SharedMap.<String, Object>of()
                            .add("walletId", targetMap.getString("walletId"))
                            .add("owner", "MCHT")
                            .add("label", "")
                    : byDepositName(db, hqId, depositName);
            String                    resolved  = judgedMap == null ? "" : judgedMap.getString("walletId");
            boolean                   ours      = judgedMap != null && judgedMap.isEquals("owner", "HQ");
            SharedMap<String, Object> walletMap = resolved.isEmpty() ? null
                    : ours ? lockedHqWallet(db, hqId) : lockedWallet(db, resolved, hqId);
            if (!resolved.isEmpty() && walletMap == null) {
                logger.warn("판정한 Wallet 을 잠그지 못해 미매칭으로 둔다: hqId={}, walletId={}", hqId, resolved);
            }

            LocalDateTime postedAt  = WxPostingRps.postingTime(db);
            int           today     = WxPostingRps.businessDay(postedAt);
            String        depositId = IDUtils.getUnique("D");
            String        status    = walletMap == null ? "미매칭" : "완료";
            String        payload   = inMap.getString("payload");
            String        hash      = CommonUtils.hashSHA256(sourceType + "|" + hqId + "|" + externalId);
            bankMap.put("bankName", hqBankName(db, bankMap));

            update(db, """
                    INSERT INTO WX.TRX_DEPOSIT(id,hqId,sourceType,externalId,vaAccntNo,bankCd,bankName,
                                               depositName,amount,occurredAt,mchtId,walletId,status,payloadHash,
                                               payloadEnc,matchedBy,matchedAt,regId,regDay,regDate)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, depositId, hqId, sourceType, externalId, vaAccntNo,
                    bankCd.isEmpty() ? bankMap.getString("bankCd") : bankCd,
                    bankMap.getString("bankName"), depositName, amount, occurred,
                    walletMap == null || ours ? null : walletMap.getString("mchtId"),
                    walletMap == null ? null : walletMap.getString("walletId"),
                    status, hash,
                    payload.isBlank() ? null : WxPostingRps.encryptForLedger(payload),
                    walletMap == null ? null : ACTOR,
                    walletMap == null ? null : postedAt,
                    ACTOR, today, postedAt);

            // 모계좌 장부잔액은 은행에 돈이 들어온 이 시점에 오른다. Wallet 지정과는 별개다.
            hqLedger(db, bankMap, depositId, amount, depositName, postedAt, today);

            SharedMap<String, Object> resultMap = SharedMap.<String, Object>of()
                    .add("id", depositId)
                    .add("status", status)
                    .add("amount", amount)
                    .add("matched", walletMap != null)
                    .add("duplicate", false)
                    // 기관 잔액 동기화에 쓴다. 고객사·화면 응답에는 담지 않는다.
                    .add("hqId", hqId);

            if ("prod".equals(WalletConfig.upstreamEnv())) {
                scheduleBalanceReview(db, hqId, depositId);
                resultMap
                        .add("claimId", depositId)
                        .add("settingRev", bankMap.getLong("settingRev"));
            }
            SharedMap<String, Object> profileMap = WxTransferRps.upstream(hqId, bankMap);
            if (profileMap != null) resultMap.add("upstream", profileMap);

            if (walletMap == null) {
                logger.info("입금 Wallet 을 찾지 못해 미매칭으로 둔다: depositId={}, hqId={}, sourceType={}, amount={}",
                        depositId, hqId, sourceType, amount);
                audit(db, "DEPOSIT_RECEIVE", null, depositId, "입금 수신, Wallet 미매칭", accessIp, ACTOR, today,
                        SharedMap.<String, Object>of(),
                        SharedMap.<String, Object>of()
                                .add("status", status)
                                .add("amount", amount));
                return resultMap;
            }

            // 본사가 받은 돈(이자·환급)은 본사 Wallet 에 기타입금으로 넣고 사유 이름을 비고에 적는다.
            String note = ours
                    ? (judgedMap.isBlank("label") ? "모계좌 기타 입금" : judgedMap.getString("label"))
                    : virtual ? "가상계좌 입금 자동 매칭" : "입금자명 입금 자동 매칭";
            String ledgerId = ours
                    ? creditHq(db, walletMap, depositId, amount, depositName, note, ACTOR, postedAt, today)
                    : credit(db, walletMap, depositId, amount, depositName, note, ACTOR, postedAt, today);
            update(db, "UPDATE WX.TRX_DEPOSIT SET walletTrxId=? WHERE id=?", ledgerId, depositId);
            audit(db, ours ? "DEPOSIT_RECEIVE_HQ" : "DEPOSIT_RECEIVE",
                    ours ? null : walletMap.getString("mchtId"), depositId,
                    ours ? "입금 수신, 모계좌 기타 입금 자동 판정: " + note : "입금 수신, Wallet 자동 매칭",
                    accessIp, ACTOR, today,
                    SharedMap.<String, Object>of(),
                    SharedMap.<String, Object>of()
                            .add("status", status)
                            .add("amount", amount)
                            .add("owner", ours ? "HQ" : "MCHT")
                            .add("walletId", walletMap.getString("walletId"))
                            .add("walletTrxId", ledgerId));
            logger.info("입금 자동 매칭 완료: depositId={}, sourceType={}, owner={}, mchtId={}, walletId={}, "
                    + "walletTrxId={}, amount={}", depositId, sourceType, ours ? "HQ" : "MCHT",
                    ours ? "-" : walletMap.getString("mchtId"), walletMap.getString("walletId"), ledgerId, amount);
            resultMap
                    .add("walletId", walletMap.getString("walletId"))
                    .add("walletTrxId", ledgerId)
                    .add("balanceAfter", walletMap.getLong("balance") + amount)
                    .add("owner", ours ? "HQ" : "MCHT");
            if (ours) {
                // 받을 고객사가 없다. 고객사 통지를 적재하지 않는다.
                return resultMap;
            }
            resultMap.add("mchtId", walletMap.getString("mchtId"))
                    // 고객사 통지는 확정과 같은 트랜잭션에 적재한다. 발송은 WX_TASK 가 한다.
                    .add("trxId", depositId)
                    .add("depositName", depositName)
                    .add("sourceType", sourceType);
            WxWebhookRps.enqueue(db, resultMap, WxWebhookRps.DEPOSIT_CREDITED, postedAt);
            return resultMap;
        });
        // 커밋 뒤 발송 큐에 올린다. 미매칭이면 적재한 것이 없어 아무것도 올라가지 않는다.
        WxWebhookRps.publish(outMap);

        return outMap;
    }

    /** 자동·수동 입금: 통지 시각을 파싱하고 달력 보정이나 날짜 넘김이 없는지 확인한다. */
    private static LocalDateTime depositTime(String value) {

        if (value.matches("[0-9]{14}")) {
            try {
                LocalDateTime occurred = DateUtils.getDate(value);
                if (value.equals(DateUtils.toString(occurred))) return occurred;
            } catch (DateTimeException invalid) {
                // 해석할 수 없는 값은 입금일시 검증 오류로 처리한다.
            }
        }
        throw new LedgerEx(LedgerCode.INVALID, "입금시간을 yyyyMMddHHmmss 형식의 유효한 날짜와 시각으로 입력해 주세요.");
    }

    /**
     * 가상계좌 통지가 가리키는 모계좌와 Wallet.
     *
     * <p>재고에 없는 번호는 받지 않는다. 그 번호의 돈이 어느 모계좌로 들어왔는지 알 수 없고,
     * 모르는 채로 기록하면 모계좌 잔액이 맞지 않는다. 운영자가 재고에 등록한 뒤 다시 보내면 된다.</p>
     */
    private static SharedMap<String, Object> byVirtual(Connection db, String vaAccntNo) throws SQLException {

        List<SharedMap<String, Object>> foundList = rows(db, """
                SELECT hqId,COALESCE(walletId,'') AS walletId FROM WX.HQ_VACCNT
                 WHERE accntNo=? AND status='사용'
                """, vaAccntNo);
        if (foundList.isEmpty()) {
            logger.info("재고에 없는 가상계좌로 입금 통지가 왔다: accntNo 자릿수={}", vaAccntNo.length());
            throw new LedgerEx(LedgerCode.NOT_FOUND, "등록되지 않은 가상계좌입니다. 가상계좌 재고를 확인해 주세요.");
        }
        return foundList.get(0);
    }

    /**
     * SMS 통지가 가리키는 모계좌.
     *
     * <p>모계좌번호는 하이픈을 떼고 비교한다. 은행마다 표기가 달라 통지와 등록값의 모양이 같지 않다.
     * 같은 번호가 두 은행에 있으면 은행 코드를 함께 받아야 한다.</p>
     */
    private static SharedMap<String, Object> byAccount(Connection db, String hqAccntNo, String bankCd)
            throws SQLException {

        List<SharedMap<String, Object>> foundList = rows(db, """
                SELECT id AS hqId,'' AS walletId FROM WX.HQ_ACCNT
                 WHERE REPLACE(accntNo,'-','')=REPLACE(?,'-','') AND (?='' OR bankCd=?)
                 ORDER BY status,id LIMIT 2
                """, hqAccntNo, bankCd, bankCd);
        if (foundList.isEmpty()) {
            logger.info("등록되지 않은 모계좌번호로 입금 통지가 왔다: 자릿수={}, bankCd={}", hqAccntNo.length(), bankCd);
            throw new LedgerEx(LedgerCode.NOT_FOUND, "등록되지 않은 모계좌번호입니다.");
        }

        if (foundList.size() > 1) {
            logger.info("같은 모계좌번호가 둘 이상이어서 입금 통지를 받지 않는다: 자릿수={}", hqAccntNo.length());
            throw new LedgerEx(LedgerCode.CONFLICT, "같은 번호의 모계좌가 둘 이상입니다. 은행 코드를 함께 보내 주세요.");
        }
        return foundList.get(0);
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
    /**
     * 입금자명으로 받을 Wallet 을 정한다. 고객사 규칙과 모계좌 기타 입금 규칙을 함께 본다.
     *
     * <p>SMS 통지의 이름은 은행에서 잘려 들어온다. 등록값이 통지 이름에 들어 있거나 통지 이름이
     * 등록값에 들어 있으면 후보다. 판정은 DB 의 {@code LIKE} 로 하고 애플리케이션에서 이름을
     * 자르지 않는다. 한 자 이름은 거꾸로 보지 않는다 — 너무 많이 걸린다.</p>
     *
     * <p>보는 곳이 둘이다.</p>
     * <ul>
     *   <li>{@code WALLET_MATCH} — 고객사 Wallet 의 입금자명. 그 고객사 Wallet 으로 간다</li>
     *   <li>{@code HQ_DEPOSIT_RULE} — 모계좌 공통 기타 입금 문구(이자·환급 등). 그 입금이 들어온
     *       모계좌의 <b>본사 Wallet</b> 으로 가고 사유 이름이 분개 비고가 된다</li>
     * </ul>
     *
     * <p><b>후보가 정확히 하나일 때만 돌려준다.</b> 둘 이상이면 동명이인이거나 짧은 문구가 여러
     * 규칙에 걸린 것이므로 미매칭으로 두고 사람이 고른다. 고객사 규칙과 본사 문구가 같은 이름에
     * 걸리는 경우도 그렇다 — 어느 쪽인지 기계가 정할 수 없다.</p>
     *
     * @return {@code walletId}·{@code owner}(MCHT 또는 HQ)·{@code label}, 정해지지 않으면 null
     */
    private static SharedMap<String, Object> byDepositName(Connection db, String hqId, String depositName)
            throws SQLException {

        boolean similar = depositName.length() >= 2;
        String mine = similar
                ? "(? LIKE CONCAT('%',C.matchValue,'%') OR C.matchValue LIKE CONCAT('%',?,'%'))"
                : "(? LIKE CONCAT('%',C.matchValue,'%'))";
        // 모계좌 기타 입금은 **완전 일치**다. `이자` 같은 짧은 문구를 LIKE 로 보면 `이자까페` 처럼
        // 고객사 입금자명까지 걸려 고객사 돈이 본사로 간다. 이자 통지의 적요는 잘리지 않으므로
        // 완전 일치로 충분하다. 고객사 쪽은 SMS 이름이 잘려 오므로 양방향 LIKE 를 그대로 둔다.
        String sql = """
                SELECT walletId, owner, label FROM (
                  SELECT DISTINCT W.id AS walletId, 'MCHT' AS owner, '' AS label
                    FROM WX.WALLET_MATCH C JOIN WX.WALLET W ON W.id=C.walletId
                   WHERE C.hqId=? AND C.status='사용' AND C.matchType='depositor'
                     AND W.ownerType='MCHT' AND W.status='사용'
                     AND %s
                  UNION ALL
                  SELECT DISTINCT W.id AS walletId, 'HQ' AS owner, R.label AS label
                    FROM WX.HQ_DEPOSIT_RULE R JOIN WX.WALLET W ON W.hqWalletHqId=?
                   WHERE R.status='사용' AND W.ownerType='HQ' AND W.status='사용'
                     AND R.matchValue=?
                ) F LIMIT 2
                """.formatted(mine);
        List<SharedMap<String, Object>> foundList = similar
                ? rows(db, sql, hqId, depositName, depositName, hqId, depositName)
                : rows(db, sql, hqId, depositName, hqId, depositName);
        if (foundList.size() == 1) return foundList.get(0);
        if (foundList.size() > 1) {
            logger.warn("입금자명 후보가 둘 이상이어서 자동 매칭하지 않는다: hqId={}, 후보={}건", hqId, foundList.size());
        }
        return null;
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

        List<SharedMap<String, Object>> foundList = rows(db, new Retrieve("WX.WALLET W")
                .columns("W.id AS walletId,W.mchtId,W.hqId,B.balance")
                .joinInner("WX.WALLET_BALANCE B", "B.walletId=W.id")
                .where("W.id=? AND W.hqId=? AND W.ownerType='MCHT' AND W.status='사용'")
                .forUpdate().build(), walletId, hqId);
        return foundList.size() == 1 ? foundList.get(0) : null;
    }

    /** 입금과 같은 트랜잭션에서 모계좌별 최신 대조 대상을 보관한다. 첫 조회는 입금 처리기가 맡는다. */
    static void scheduleBalanceReview(Connection db, String hqId, String depositId) throws SQLException {

        update(db, """
                INSERT INTO WX.HQ_BALANCE_RETRY(hqId,depositId,claimId,retryAt)
                VALUES(?,?,?,NOW()+INTERVAL 2 MINUTE)
                ON DUPLICATE KEY UPDATE depositId=VALUES(depositId),claimId=VALUES(claimId),retryAt=VALUES(retryAt)
                """, hqId, depositId, depositId);
    }

    /** 작업기 주기에 기한이 지난 한 계좌를 선점하고 현재 기관 설정을 읽는다. */
    public static SharedMap<String, Object> claimBalanceReview() throws SQLException {

        return transaction(db -> {
            List<SharedMap<String, Object>> jobsList = rows(db, new Retrieve("WX.HQ_BALANCE_RETRY")
                    .columns("hqId,depositId AS id")
                    .where("retryAt<=NOW()")
                    .orderBy("retryAt,hqId")
                    .limit(1)
                    .forUpdate().build());
            if (jobsList.isEmpty()) return null;
            SharedMap<String, Object> jobMap  = jobsList.get(0);
            String                    hqId    = jobMap.getString("hqId");
            String                    claimId = IDUtils.getRandom(32);
            update(db, "UPDATE WX.HQ_BALANCE_RETRY SET claimId=?,retryAt=NOW()+INTERVAL 2 MINUTE WHERE hqId=?",
                    claimId, hqId);
            jobMap.add("claimId", claimId);
            List<SharedMap<String, Object>> banksList = rows(db, """
                    SELECT settingRev,acq,acqId,bankCd AS acqBankCd,accntNo AS acqAccount,key2 AS acqKey2
                      FROM WX.HQ_ACCNT WHERE id=?
                    """, hqId);

            if (!banksList.isEmpty()) {
                SharedMap<String, Object> bankMap = banksList.get(0);
                jobMap.add("settingRev", bankMap.getLong("settingRev"));
                SharedMap<String, Object> profileMap = WxTransferRps.upstream(hqId, bankMap);
                if (profileMap != null) jobMap.add("upstream", profileMap);
            }
            return jobMap;
        });
    }

    /** 입금 잔액 대조: 기관 조회 전에 모계좌 원장의 마지막 순번과 거래 예약을 읽는다. */
    public static long balanceRevision(String hqId) throws SQLException {

        try (Connection db = WxDatabaseRps.open()) {
            List<SharedMap<String, Object>> foundList = rows(db, """
                    SELECT COALESCE((SELECT MAX(T.idx) FROM WX.TRX_HQ T WHERE T.hqId=H.id),0) revision
                      FROM WX.HQ_ACCNT H WHERE H.id=? AND H.reservedAmt=0
                    """, hqId);
            return foundList.isEmpty() ? -1 : foundList.get(0).getLong("revision");
        }
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
     * @param jobMap 모계좌와 입금번호, 선점 식별자, 조회에 사용한 설정 버전
     * @param reported  기관이 알려준 실제 잔액
     * @return 조정 전후와 차이. 조정하지 않았으면 {@code changed=false}
     */
    public static SharedMap<String, Object> syncBalance(SharedMap<String, Object> jobMap, long reported, long revision)
            throws SQLException {

        String hqId      = jobMap.getString("hqId");
        String depositId = jobMap.getString("id");
        if (hqId.isBlank() || depositId.isBlank() || jobMap.isBlank("claimId") || revision < 0) {
            throw new LedgerEx(LedgerCode.INVALID, "모계좌 잔액 동기화 입력을 확인해 주세요.");
        }
        return transaction(db -> {
            List<SharedMap<String, Object>> banksList = rows(db, new Retrieve("WX.HQ_ACCNT")
                    .columns("id,bankCd,balance,reservedAmt,settingRev")
                    .where("id=?")
                    .forUpdate().build(), hqId);
            if (banksList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "모계좌를 확인해 주세요.");
            SharedMap<String, Object> bankMap = banksList.get(0);

            long                      before    = bankMap.getLong("balance");
            long                      delta     = Math.subtractExact(reported, before);
            SharedMap<String, Object> resultMap = SharedMap.<String, Object>of()
                    .add("hqId", hqId)
                    .add("before", before)
                    .add("reported", reported)
                    .add("delta", delta)
                    .add("changed", false);
            // 모계좌 행을 잠근 뒤 기관 조회 중 커밋된 분개가 있는지 현재 값을 읽는다.
            List<SharedMap<String, Object>> latestList = rows(db, new Retrieve("WX.TRX_HQ")
                    .columns("idx")
                    .where("hqId=?")
                    .orderBy("idx DESC")
                    .limit(1)
                    .forUpdate().build(), hqId);
            long currentRevision = latestList.isEmpty() ? 0 : latestList.get(0).getLong("idx");
            if (bankMap.getLong("reservedAmt") != 0 || currentRevision != revision
                    || bankMap.getLong("settingRev") != jobMap.getLong("settingRev")) {
                logger.info("모계좌의 거래가 진행 중이거나 원장이 변경되어 잔액 조정을 보류한다: hqId={}", hqId);
                return resultMap.add("deferred", true);
            }
            // 새 입금 또는 다음 선점이 있으면 이전 응답은 폐기한다. 삭제와 원장 반영은 함께 커밋한다.
            if (update(db, "DELETE FROM WX.HQ_BALANCE_RETRY WHERE hqId=? AND depositId=? AND claimId=?",
                    hqId, depositId, jobMap.getString("claimId")) != 1) return resultMap.add("deferred", true);

            if (delta == 0) {
                logger.debug("모계좌 잔액이 기관 값과 같다: hqId={}, balance={}", hqId, before);
                return resultMap;
            }

            LocalDateTime postedAt = WxPostingRps.postingTime(db);
            int today = WxPostingRps.businessDay(postedAt);
            // 같은 입금으로 두 번 조정하지 않는다. UK_TRX_HQ_ACCNT_2(hqId,refType,refId) 가 막는다.
            update(db, """
                    INSERT INTO WX.TRX_HQ(id,hqId,status,trxType,dtlType,refType,refId,amount,balance,balanceAfter,
                                          bankCd,bankName,summary,trxDay,trxDate,regId,regDay)
                    VALUES(?,?,'성공',?,'잔액조정','HQ_BALANCE_SYNC',?,?,?,?,?,?,?,?,?,?,?)
                    """, IDUtils.getUnique("H"), hqId, delta > 0 ? "입금" : "출금", depositId, delta, before,
                    reported, bankMap.getString("bankCd"), hqBankName(db, bankMap),
                    "입금 수신 후 기관 잔액 동기화", today, postedAt, ACTOR, today);
            if (update(db, "UPDATE WX.HQ_ACCNT SET balance=? WHERE id=?", reported, hqId) != 1) {
                throw new SQLException("모계좌 잔액 동기화 반영에 실패했습니다.");
            }
            logger.warn("모계좌 장부잔액을 기관 값으로 맞췄다. 차이를 확인해야 한다: "
                            + "hqId={}, 장부={}, 기관={}, 차이={}, depositId={}",
                    hqId, before, reported, delta, depositId);
            // SharedMap.add 는 같은 키를 두 번 받지 않는다. 바꿀 때는 put 을 쓴다.
            resultMap.put("changed", true);
            return resultMap;
        });
    }

    /** 입금 지정: 입금 한 건을 지정 Wallet의 확정 원장에 입금으로 반영한다. */
    public static SharedMap<String, Object> assign(SharedMap<String, Object> inMap) throws SQLException {

        String id       = WxPostingRps.field(inMap, "depositId", 32);
        String walletId = WxPostingRps.field(inMap, "walletId", 32);
        String actor    = WxPostingRps.field(inMap, "userId", 24);
        String reason   = WxPostingRps.field(inMap, "reason", 128);
        String accessIp = inMap.getString("clientIp");

        SharedMap<String, Object> outMap = transaction(db -> {
            SharedMap<String, Object> depositMap = locked(db, id);
            long                      amount     = depositMap.getLong("amount");
            if (amount <= 0) throw new LedgerEx(LedgerCode.INVALID, "입금액을 확인해 주세요.");

            SharedMap<String, Object> walletMap = lockedWallet(db, walletId, depositMap.getString("hqId"));
            if (walletMap == null) {
                throw new LedgerEx(LedgerCode.CONFLICT,
                        "같은 모계좌에 귀속된 사용 중인 고객사 Wallet만 지정할 수 있습니다.");
            }

            LocalDateTime postedAt = WxPostingRps.postingTime(db);
            int           today    = WxPostingRps.businessDay(postedAt);
            long          after    = Math.addExact(walletMap.getLong("balance"), amount);

            String ledgerId = credit(db, walletMap, id, amount, depositMap.getString("depositName"), reason,
                    actor, postedAt, today);

            changed(update(db, """
                    UPDATE WX.TRX_DEPOSIT SET status='완료',mchtId=?,walletId=?,walletTrxId=?,matchedBy=?,
                           matchedAt=NOW(),udtId=? WHERE id=? AND status IN ('접수','미매칭')
                    """, walletMap.getString("mchtId"), walletId, ledgerId, actor, actor, id));

            audit(db, "DEPOSIT_ASSIGN", walletMap.getString("mchtId"), id, reason, accessIp, actor, today,
                    SharedMap.<String, Object>of()
                            .add("status", depositMap.getString("status")),
                    SharedMap.<String, Object>of()
                            .add("status", "완료")
                            .add("walletId", walletId)
                            .add("walletTrxId", ledgerId)
                            .add("amount", amount));

            logger.info("입금 Wallet 지정 완료: depositId={}, mchtId={}, walletId={}, walletTrxId={}, amount={}, actor={}",
                    id, walletMap.getString("mchtId"), walletId, ledgerId, amount, actor);
            SharedMap<String, Object> resultMap = SharedMap.<String, Object>of()
                    .add("id", id)
                    .add("mchtId", walletMap.getString("mchtId"))
                    .add("walletId", walletId)
                    .add("walletTrxId", ledgerId)
                    .add("amount", amount)
                    .add("balanceAfter", after)
                    // 자동 매칭과 같은 이벤트를 보낸다. 고객사는 사람이 지정했는지 알 필요가 없다.
                    .add("trxId", id)
                    .add("status", "완료")
                    .add("depositName", depositMap.getString("depositName"))
                    .add("sourceType", depositMap.getString("sourceType"));
            WxWebhookRps.enqueue(db, resultMap, WxWebhookRps.DEPOSIT_CREDITED, postedAt);
            return resultMap;
        });
        WxWebhookRps.publish(outMap);

        return outMap;
    }


    /**
     * 수동 입금: 통지가 오지 않은 입금을 운영자가 직접 기록하고 고른 Wallet 에 반영한다.
     *
     * <p>SMS 중계가 멈췄거나 기관 가상계좌 통지가 끊긴 동안 들어온 돈은 WX 가 모른다. 통장에는
     * 있고 원장에는 없다. 운영자가 통장 거래내역을 보고 이 경로로 넣는다. <b>통지와 거꾸로</b>
     * 돈다 — 통지는 계좌번호가 Wallet 을 알려 주지만, 여기서는 운영자가 고른 Wallet 이 모계좌와
     * 계좌번호를 알려 준다. 사람이 판단한 것이므로 입금자명 규칙으로 다시 찾지 않는다.</p>
     *
     * <p>기록한 뒤의 처리는 자동 매칭과 완전히 같다. 모계좌 원장과 Wallet 분개를 쓰고, 잔액은
     * 트리거가 올리고, 고객사 웹훅({@code deposit.credited})을 같은 트랜잭션에 적재한다. 고객사는
     * 사람이 넣었는지 알 필요가 없다.</p>
     *
     * <p><b>두 번 들어오는 것을 두 군데서 막는다.</b> 외부 식별자를 통지와 같은 규칙으로 만들어
     * 늦게 도착한 통지가 유니크 키에 걸리게 하고, 초가 어긋나 그 키를 비켜 가는 경우를 위해
     * 같은 모계좌·금액·입금자명이 {@code DUP_MINUTES} 안에 있으면 거절한다. 운영자가 두 번 누른
     * 경우도 같은 검사에 걸린다.</p>
     *
     * <p>사람이 넣었다는 사실은 {@code regId}·{@code matchedBy} 에 운영자 ID 로 남는다. 자동
     * 반영은 {@code WX_API} 다. 감사 이력의 업무 이름은 {@code DEPOSIT_MANUAL} 이다.</p>
     */
    public static SharedMap<String, Object> manual(SharedMap<String, Object> inMap) throws SQLException {

        String walletId    = WxPostingRps.field(inMap, "walletId", 32);
        String sourceType  = WxPostingRps.field(inMap, "sourceType", 12);
        String depositName = WxPostingRps.field(inMap, "depositName", 128);
        String occurredAt  = WxPostingRps.field(inMap, "occurredAt", 14);
        String actor       = WxPostingRps.field(inMap, "userId", 24);
        String reason      = WxPostingRps.field(inMap, "reason", 128);
        String accessIp    = inMap.getString("clientIp");
        long   amount      = inMap.getLong("amount");

        boolean virtual = "va".equals(sourceType);
        if (!virtual && !"sms".equals(sourceType)) {
            throw new LedgerEx(LedgerCode.INVALID, "수신경로는 가상계좌 또는 SMS 입니다.");
        }
        if (amount <= 0) throw new LedgerEx(LedgerCode.INVALID, "입금액을 확인해 주세요.");
        LocalDateTime occurred = depositTime(occurredAt);

        SharedMap<String, Object> outMap = transaction(db -> {
            // 모계좌는 Wallet 이 알려 준다. 운영자는 돈의 주인을 고르고, 어느 계좌로 들어왔는지는
            // 그 Wallet 의 귀속이 정한다. 두 값을 따로 받으면 서로 어긋난 조합을 고를 수 있다.
            List<SharedMap<String, Object>> ownersList = rows(db,
                    "SELECT hqId FROM WX.WALLET WHERE id=? AND ownerType='MCHT'", walletId);
            if (ownersList.isEmpty()) throw new LedgerEx(LedgerCode.NOT_FOUND, "고객사 Wallet 을 확인해 주세요.");
            String hqId = ownersList.get(0).getString("hqId");

            SharedMap<String, Object> walletMap = lockedWallet(db, walletId, hqId);
            if (walletMap == null) {
                throw new LedgerEx(LedgerCode.CONFLICT, "사용 중인 고객사 Wallet 만 지정할 수 있습니다.");
            }

            List<SharedMap<String, Object>> banksList = rows(db, new Retrieve("WX.HQ_ACCNT")
                    .columns("id,name,bankCd,accntNo,balance,status,settingRev,acq,acqId")
                    .column("bankCd AS acqBankCd,accntNo AS acqAccount,COALESCE(key2,'') AS acqKey2")
                    .where("id=?")
                    .forUpdate().build(), hqId);
            if (banksList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "모계좌를 확인해 주세요.");
            SharedMap<String, Object> bankMap = banksList.get(0);
            if (!bankMap.isEquals("status", "사용")) {
                logger.warn("사용 중이 아닌 모계좌에 수동 입금을 넣는다: hqId={}, status={}, actor={}",
                        hqId, bankMap.getString("status"), actor);
            }

            // 통지가 보내 올 계좌번호를 여기서 세운다. 가상계좌는 그 Wallet 에 할당된 번호,
            // SMS 는 모계좌번호다. 외부 식별자를 통지와 같은 규칙으로 만들어야 늦게 온 통지가
            // 유니크 키에 걸려 두 번 반영되지 않는다.
            String vaAccntNo  = virtual ? virtualOf(db, walletId) : "";
            String accntNo    = virtual ? vaAccntNo : bankMap.getString("accntNo");
            String externalId = externalId(sourceType, accntNo, occurredAt, amount, depositName);

            if (!rows(db, new Retrieve("WX.TRX_DEPOSIT")
                    .columns("id")
                    .where("hqId=? AND sourceType=? AND externalId=?")
                    .forUpdate().build(), hqId, sourceType, externalId).isEmpty()) {
                throw new LedgerEx(LedgerCode.CONFLICT,
                        "같은 입금이 이미 기록되어 있습니다. 입금내역에서 확인해 주세요.");
            }
            // 초가 어긋나면 위 열쇠를 비켜 간다. 사람이 적는 시각은 분까지가 보통이므로 분 단위로
            // 같은 금액·입금자명을 한 번 더 본다. 범위 조회라 잠그지 않는다 — 열쇠는 위가 지킨다.
            List<SharedMap<String, Object>> nearList = rows(db, """
                    SELECT id,status,regId FROM WX.TRX_DEPOSIT
                     WHERE hqId=? AND amount=? AND depositName=? AND status<>'반려'
                       AND occurredAt BETWEEN ? AND ?
                    """, hqId, amount, depositName,
                    occurred.minusMinutes(DUP_MINUTES), occurred.plusMinutes(DUP_MINUTES));
            if (!nearList.isEmpty()) {
                logger.info("같은 시각대의 입금이 이미 있어 수동 입금을 거절한다: hqId={}, 기존={}, actor={}",
                        hqId, nearList.get(0).getString("id"), actor);
                throw new LedgerEx(LedgerCode.CONFLICT,
                        "같은 시각·금액·입금자명의 입금이 이미 있습니다(" + nearList.get(0).getString("id")
                                + "). 중복이 아닌지 확인해 주세요.");
            }

            LocalDateTime postedAt = WxPostingRps.postingTime(db);
            int    today     = WxPostingRps.businessDay(postedAt);
            String depositId = IDUtils.getUnique("D");
            String hash      = CommonUtils.hashSHA256(sourceType + "|" + hqId + "|" + externalId);
            // 통지 전문 대신 운영자가 적어 넣은 값을 남긴다. 나중에 무엇을 근거로 넣었는지 본다.
            String payload   = JacksonUtils.get().toJson(SharedMap.<String, Object>of()
                    .add("manual", true)
                    .add("actor", actor)
                    .add("reason", reason)
                    .add("sourceType", sourceType)
                    .add("occurredAt", occurredAt)
                    .add("amount", amount));

            bankMap.put("bankName", hqBankName(db, bankMap));
            update(db, """
                    INSERT INTO WX.TRX_DEPOSIT(id,hqId,sourceType,externalId,vaAccntNo,bankCd,bankName,
                                               depositName,amount,occurredAt,mchtId,walletId,status,payloadHash,
                                               payloadEnc,matchedBy,matchedAt,regId,regDay,regDate)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'완료',?,?,?,?,?,?,?)
                    """, depositId, hqId, sourceType, externalId, vaAccntNo,
                    bankMap.getString("bankCd"), bankMap.getString("bankName"), depositName, amount, occurred,
                    walletMap.getString("mchtId"), walletId, hash, WxPostingRps.encryptForLedger(payload),
                    actor, postedAt, actor, today, postedAt);

            // 돈은 모계좌에 들어와 있다. 통지로 들어온 입금과 같은 자리에 같은 분개를 남긴다.
            hqLedger(db, bankMap, depositId, amount, depositName, postedAt, today);

            String ledgerId = credit(db, walletMap, depositId, amount, depositName, reason,
                    actor, postedAt, today);
            update(db, "UPDATE WX.TRX_DEPOSIT SET walletTrxId=? WHERE id=?", ledgerId, depositId);

            audit(db, "DEPOSIT_MANUAL", walletMap.getString("mchtId"), depositId, reason, accessIp, actor, today,
                    SharedMap.<String, Object>of(),
                    SharedMap.<String, Object>of()
                            .add("status", "완료")
                            .add("sourceType", sourceType)
                            .add("amount", amount)
                            .add("occurredAt", occurredAt)
                            .add("depositName", depositName)
                            .add("walletId", walletId)
                            .add("walletTrxId", ledgerId));

            logger.info("수동 입금 완료: depositId={}, sourceType={}, mchtId={}, walletId={}, walletTrxId={}, "
                    + "amount={}, actor={}", depositId, sourceType, walletMap.getString("mchtId"), walletId,
                    ledgerId, amount, actor);

            SharedMap<String, Object> resultMap = SharedMap.<String, Object>of()
                    .add("id", depositId)
                    .add("status", "완료")
                    .add("mchtId", walletMap.getString("mchtId"))
                    .add("walletId", walletId)
                    .add("walletTrxId", ledgerId)
                    .add("amount", amount)
                    .add("balanceAfter", Math.addExact(walletMap.getLong("balance"), amount))
                    .add("hqId", hqId)
                    // 자동 매칭과 같은 이벤트를 보낸다. 고객사는 사람이 넣었는지 알 필요가 없다.
                    .add("trxId", depositId)
                    .add("depositName", depositName)
                    .add("sourceType", sourceType);

            if ("prod".equals(WalletConfig.upstreamEnv())) {
                scheduleBalanceReview(db, hqId, depositId);
                resultMap
                        .add("claimId", depositId)
                        .add("settingRev", bankMap.getLong("settingRev"));
            }
            SharedMap<String, Object> profileMap = WxTransferRps.upstream(hqId, bankMap);
            if (profileMap != null) resultMap.add("upstream", profileMap);
            WxWebhookRps.enqueue(db, resultMap, WxWebhookRps.DEPOSIT_CREDITED, postedAt);
            return resultMap;
        });
        WxWebhookRps.publish(outMap);

        return outMap;
    }

    /**
     * 그 Wallet 에 할당된 가상계좌번호.
     *
     * <p>가상계좌로 들어온 입금을 수동으로 넣으려면 번호가 있어야 한다. 번호가 없으면 어느
     * 가상계좌로 들어온 돈인지 말할 수 없고, 늦게 온 통지와 겹치는지도 볼 수 없다.</p>
     */
    private static String virtualOf(Connection db, String walletId) throws SQLException {

        List<SharedMap<String, Object>> foundList = rows(db,
                "SELECT accntNo FROM WX.HQ_VACCNT WHERE walletId=? AND status='사용'", walletId);
        if (foundList.isEmpty()) {
            throw new LedgerEx(LedgerCode.CONFLICT,
                    "이 Wallet 에 할당된 가상계좌가 없습니다. 가상계좌를 먼저 할당하거나 SMS 로 넣어 주세요.");
        }
        return foundList.get(0).getString("accntNo");
    }


    /**
     * 본사 입금: 미매칭 입금을 그 모계좌의 <b>본사 Wallet</b> 에 넣는다.
     *
     * <p>통장에 들어오는 돈이 모두 고객사 것은 아니다. 금융이자, 오입금 반환, 은행 수수료 환급은
     * 본사가 받은 돈이다. 그 돈을 고객사 Wallet 에 줄 수는 없고, 반려로 두면 "모계좌가 들고 있는
     * 돈" 으로만 세어져 본사 수익으로 인식되지 않는다.</p>
     *
     * <p><b>원장 대조식이 그대로 성립한다.</b> 본사 Wallet 도 {@code WALLET_BALANCE} 에 들어가므로
     * 모계좌 잔액이 오른 만큼 Wallet 잔액 합계도 오른다. 반려나 잔액조정으로 흘리면 대조식에
     * 설명 항목을 새로 더해야 한다.</p>
     *
     * <p>고객사 웹훅은 <b>보내지 않는다.</b> 받을 고객사가 없다. 거래 유형은 {@code 기타입금} 이고
     * 분개의 비고에 운영자가 적은 사유가 남는다. 감사 이력의 업무 이름은 {@code DEPOSIT_ASSIGN_HQ} 다.</p>
     */
    public static SharedMap<String, Object> assignHq(SharedMap<String, Object> inMap) throws SQLException {

        String id       = WxPostingRps.field(inMap, "depositId", 32);
        String actor    = WxPostingRps.field(inMap, "userId", 24);
        String reason   = WxPostingRps.field(inMap, "reason", 128);
        String accessIp = inMap.getString("clientIp");

        return transaction(db -> {
            SharedMap<String, Object> depositMap = locked(db, id);
            long                      amount     = depositMap.getLong("amount");
            if (amount <= 0) throw new LedgerEx(LedgerCode.INVALID, "입금액을 확인해 주세요.");

            String                    hqId      = depositMap.getString("hqId");
            SharedMap<String, Object> walletMap = lockedHqWallet(db, hqId);
            if (walletMap == null) {
                throw new LedgerEx(LedgerCode.NOT_FOUND, "이 모계좌의 본사 Wallet 을 찾을 수 없습니다.");
            }

            LocalDateTime postedAt = WxPostingRps.postingTime(db);
            int           today    = WxPostingRps.businessDay(postedAt);
            long          after    = Math.addExact(walletMap.getLong("balance"), amount);
            String        ledgerId = creditHq(db, walletMap, id, amount, depositMap.getString("depositName"), reason,
                    actor, postedAt, today);

            changed(update(db, """
                    UPDATE WX.TRX_DEPOSIT SET status='완료',mchtId=NULL,walletId=?,walletTrxId=?,matchedBy=?,
                           matchedAt=NOW(),udtId=? WHERE id=? AND status IN ('접수','미매칭')
                    """, walletMap.getString("walletId"), ledgerId, actor, actor, id));

            audit(db, "DEPOSIT_ASSIGN_HQ", null, id, reason, accessIp, actor, today,
                    SharedMap.<String, Object>of()
                            .add("status", depositMap.getString("status")),
                    SharedMap.<String, Object>of()
                            .add("status", "완료")
                            .add("owner", "HQ")
                            .add("walletId", walletMap.getString("walletId"))
                            .add("walletTrxId", ledgerId)
                            .add("amount", amount));

            logger.info("본사 입금 반영: depositId={}, hqId={}, walletId={}, walletTrxId={}, amount={}, actor={}",
                    id, hqId, walletMap.getString("walletId"), ledgerId, amount, actor);
            // 고객사 웹훅을 적재하지 않는다. 받을 고객사가 없다.
            return SharedMap.<String, Object>of()
                    .add("id", id)
                    .add("owner", "HQ")
                    .add("hqId", hqId)
                    .add("walletId", walletMap.getString("walletId"))
                    .add("walletTrxId", ledgerId)
                    .add("amount", amount)
                    .add("balanceAfter", after)
                    .add("status", "완료");
        });
    }

    /**
     * 그 모계좌의 본사 Wallet 을 잠그고 잔액을 읽는다.
     *
     * <p>모계좌마다 하나이고 {@code hqWalletHqId} 가 그 귀속을 가리킨다. 정산 수수료가 모이는 그
     * Wallet 이며 본사가 받은 입금도 같은 자리로 간다.</p>
     */
    private static SharedMap<String, Object> lockedHqWallet(Connection db, String hqId) throws SQLException {

        List<SharedMap<String, Object>> foundList = rows(db, new Retrieve("WX.WALLET W")
                .columns("W.id AS walletId,W.hqId,B.balance")
                .joinInner("WX.WALLET_BALANCE B", "B.walletId=W.id")
                .where("W.hqWalletHqId=? AND W.ownerType='HQ' AND W.status='사용'")
                .forUpdate().build(), hqId);
        return foundList.size() == 1 ? foundList.get(0) : null;
    }

    /**
     * 본사 Wallet 입금 분개.
     *
     * <p>고객사 분개({@link #credit})와 다른 점은 셋이다 — {@code ownerType='HQ'}, 고객사 ID 가 없고,
     * 거래 유형이 {@code 기타입금} 이다. 잔액은 {@code TR_TRX_WALLET_BALANCE_AI} 가 올린다.</p>
     */
    private static String creditHq(Connection db, SharedMap<String, Object> walletMap, String depositId, long amount,
                                    String depositName, String summary, String actor, LocalDateTime postedAt,
                                    int today) throws SQLException {

        String walletId = walletMap.getString("walletId");
        long   before   = walletMap.getLong("balance");
        long   after    = Math.addExact(before, amount);
        long   sequence = rows(db,
                "SELECT COALESCE(MAX(`sequence`),0)+1 AS nextSequence FROM WX.TRX_WALLET WHERE walletId=?",
                walletId).get(0).getLong("nextSequence");

        String ledgerId = IDUtils.getUnique("T");
        changed(update(db, """
                INSERT INTO WX.TRX_WALLET(id,ownerType,walletId,hqId,refType,refId,refSeq,`sequence`,
                                          trxType,dtlType,status,amount,depositAmt,withdrawnAmt,balance,balanceAfter,
                                          sender,summary,trxDay,trxDate,regId,regDay)
                VALUES(?,'HQ',?,?,'TRX_DEPOSIT',?,1,?,'입금','기타입금','성공',?,?,0,?,?,LEFT(?,24),?,?,?,?,?)
                """, ledgerId, walletId, walletMap.getString("hqId"), depositId, sequence,
                amount, amount, before, after, depositName, summary, today, postedAt, actor, today));
        return ledgerId;
    }

    /** 입금 반려: 매칭하지 않고 사유를 남겨 입금을 종결한다. 원장 분개는 없다. */
    public static SharedMap<String, Object> reject(SharedMap<String, Object> inMap) throws SQLException {

        String id       = WxPostingRps.field(inMap, "depositId", 32);
        String actor    = WxPostingRps.field(inMap, "userId", 24);
        String reason   = WxPostingRps.field(inMap, "reason", 128);
        String accessIp = inMap.getString("clientIp");

        return transaction(db -> {
            SharedMap<String, Object> depositMap = locked(db, id);
            int                       today      = WxPostingRps.businessDay(WxPostingRps.postingTime(db));
            changed(update(db, """
                    UPDATE WX.TRX_DEPOSIT SET status='반려',matchedBy=?,matchedAt=NOW(),udtId=?
                     WHERE id=? AND status IN ('접수','미매칭')
                    """, actor, actor, id));
            audit(db, "DEPOSIT_REJECT", depositMap.getString("mchtId"), id, reason, accessIp, actor, today,
                    SharedMap.<String, Object>of()
                            .add("status", depositMap.getString("status")),
                    SharedMap.<String, Object>of()
                            .add("status", "반려"));
            logger.info("입금 반려 완료: depositId={}, actor={}", id, actor);
            return SharedMap.<String, Object>of()
                    .add("id", id)
                    .add("status", "반려");
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
    private static String credit(Connection db, SharedMap<String, Object> walletMap, String depositId, long amount,
                                  String depositName, String summary, String actor, LocalDateTime postedAt,
                                  int today) throws SQLException {

        String walletId = walletMap.getString("walletId");
        long   before   = walletMap.getLong("balance");
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
                """, ledgerId, walletMap.getString("mchtId"), walletId, walletMap.getString("hqId"), depositId, sequence,
                amount, amount, before, after, depositName, summary, today, postedAt, actor, today));
        // WALLET_BALANCE의 balance·lastWalletIdx·available은 TR_TRX_WALLET_BALANCE_AI 트리거가 반영한다.
        return ledgerId;
    }

    /** 입금·잔액 조정 원장에 기록할 은행 표시명을 잠근 모계좌의 설정 버전으로 읽는다. */
    private static String hqBankName(Connection db, SharedMap<String, Object> bankMap) throws SQLException {

        String  hqId           = bankMap.getString("id");
        String  cacheKey       = "WX:API:REFERENCE:HQ_ACCNT:BANK_NAME:" + hqId
                + ":" + bankMap.getLong("settingRev");
        boolean cacheAvailable = true;
        try {
            String name = RedisTextUtils.get(cacheKey);
            if (name != null) {
                return name;
            }
        } catch (RuntimeException unavailable) {
            cacheAvailable = false;
        }

        String name = rows(db, "SELECT bankName FROM WX.HQ_ACCNT WHERE id=? FOR UPDATE", hqId)
                .get(0).getString("bankName");
        if (cacheAvailable) {
            try {
                RedisTextUtils.setNx(cacheKey, name, WalletPolicy.REFERENCE_CACHE_SECONDS);
            } catch (RuntimeException unavailable) {
                // 잠근 모계좌에서 읽은 은행 표시명을 사용한다.
            }
        }
        return name;
    }

    /**
     * 모계좌 입금 원장.
     *
     * <p>은행에 돈이 들어온 사실을 모계좌 원장에 남기고 장부잔액을 올린다. 같은 입금으로 두 번
     * 기록되지 않도록 {@code UK_TRX_HQ_ACCNT_2(hqId,refType,refId)}가 막는다.</p>
     */
    private static void hqLedger(Connection db, SharedMap<String, Object> bankMap, String depositId, long amount,
                                  String depositName, LocalDateTime postedAt, int today) throws SQLException {

        String hqId   = bankMap.getString("id");
        long   before = bankMap.getLong("balance");
        changed(update(db, """
                INSERT INTO WX.TRX_HQ(id,hqId,status,trxType,dtlType,refType,refId,amount,balance,balanceAfter,
                                      bankCd,bankName,sender,trxDay,trxDate,regId,regDay)
                VALUES(?,?,'성공','입금','일반입금','TRX_DEPOSIT',?,?,?,?,?,?,LEFT(?,24),?,?,?,?)
                """, IDUtils.getUnique("H"), hqId, depositId, amount, before, Math.addExact(before, amount),
                bankMap.getString("bankCd"), bankMap.getString("bankName"), depositName, today, postedAt,
                ACTOR, today));
        if (update(db, "UPDATE WX.HQ_ACCNT SET balance=balance+? WHERE id=?", amount, hqId) != 1) {
            throw new SQLException("모계좌 입금 반영에 실패했습니다.");
        }
        logger.debug("모계좌 입금 원장: hqId={}, before={}, amount={}", hqId, before, amount);
    }

    /** 지정·반려 공통: 처리 대상 입금을 잠그고 아직 대기 상태인지 확인한다. */
    private static SharedMap<String, Object> locked(Connection db, String id) throws SQLException {

        List<SharedMap<String, Object>> foundList = rows(db,
                new Retrieve("WX.TRX_DEPOSIT")
                        .columns("id,mchtId,hqId,amount,status,depositName,sourceType")
                        .where("id=?")
                        .forUpdate().build(), id);
        if (foundList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND, "입금 내역을 찾을 수 없습니다.");
        if (!OPEN_LIST.contains(foundList.get(0).getString("status"))) {
            logger.info("이미 처리된 입금: depositId={}, status={}", id, foundList.get(0).getString("status"));
            throw new LedgerEx(LedgerCode.CONFLICT, "이미 처리된 입금입니다.");
        }
        return foundList.get(0);
    }

    /** 자금 처리 공통: 변경 사유와 접근 IP를 포함한 감사 이력을 같은 트랜잭션에 기록한다. */
    private static void audit(Connection db, String action, String merchant, String ref, String reason,
                               String accessIp, String actor, int today, SharedMap<String, Object> beforeMap,
                               SharedMap<String, Object> afterMap) throws SQLException {

        changed(update(db, """
                INSERT INTO WX.AUDIT(id,mchtId,unit,action,refType,refId,reason,beforeJson,afterJson,accessIp,
                                     regId,regDay)
                VALUES(?,?,'HQ',?,'TRX_DEPOSIT',?,?,?,?,NULLIF(?,''),?,?)
                """, IDUtils.getRandom(32), merchant, action, ref, reason,
                JacksonUtils.get().toJson(beforeMap), JacksonUtils.get().toJson(afterMap), accessIp, actor, today));
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
                SharedMap<String, Object> resultMap = work.run(db);
                db.commit();

                return resultMap;
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
