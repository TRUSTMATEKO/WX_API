package kr.tx24.wx.ledger.rps;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

import kr.tx24.lib.db.DBUtils;
import kr.tx24.lib.lang.MaskUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisTextUtils;
import kr.tx24.wx.ledger.enm.LedgerCode;
import kr.tx24.wx.ledger.ex.LedgerEx;
import kr.tx24.wx.ledger.utils.LedgerIp;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.Acquirer;

import static kr.tx24.wx.ledger.rps.WxDatabaseRps.rows;

/** 인증된 키, 고객사 허용 IP와 키에 매핑된 Wallet을 기준으로 고객사 API 업무 범위를 결정한다. */
public final class WxMerchantRps {

    private static final Logger logger = LoggerFactory.getLogger(WxMerchantRps.class);

    private static final DateTimeFormatter BANK_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);

    private WxMerchantRps() {

    }

    /**
     * WX_API 내부 요청: 인증과 업무 조회를 동일 DB 조회 시점에 처리한다.
     *
     * <p>송금 접수는 원장을 쓰므로 쓰기 가능한 READ COMMITTED 트랜잭션을 사용한다.
     * 그 밖의 업무는 조회 전용 REPEATABLE READ로 둔다.</p>
     */
    public static SharedMap<String, Object> query(SharedMap<String, Object> inMap) throws SQLException {

        // 송금과 예금주 조회는 기록을 남긴다. 나머지 조회는 조회 전용 트랜잭션을 쓴다.
        boolean write = java.util.Set.of("payout", "holder").contains(inMap.getString("operation"));
        try (Connection db = WxDatabaseRps.open()) {
            db.setTransactionIsolation(write ? Connection.TRANSACTION_READ_COMMITTED
                    : Connection.TRANSACTION_REPEATABLE_READ);
            db.setReadOnly(!write);
            db.setAutoCommit(false);
            try {
                SharedMap<String, Object> resultMap = query(db, inMap);
                db.commit();

                return resultMap;
            } catch (RuntimeException | SQLException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    /** WX_API 내부 계약: 키가 속한 고객사를 찾고 IP를 확인한 후 해당 Wallet의 업무만 처리한다. */
    static SharedMap<String, Object> query(Connection db, SharedMap<String, Object> inMap) throws SQLException {

        String keyHash = inMap.getString("keyHash");
        if (!keyHash.matches("(?i)[0-9a-f]{64}")) {
            logger.info("API 키 형식 오류로 거절: operation={}, clientIp={}",
                    inMap.getString("operation"), inMap.getString("clientIp"));
            throw new LedgerEx(LedgerCode.UNAUTHENTICATED);
        }

        List<SharedMap<String, Object>> keysList = rows(db, """
                SELECT K.idx,K.mchtId,K.walletId
                  FROM WX.WALLET_API_KEY K JOIN WX.MCHT M ON M.mchtId=K.mchtId
                 WHERE K.keyHash=? AND K.status='사용' AND M.status='사용'
                   AND (K.expiresAt IS NULL OR K.expiresAt>NOW()) AND K.revokedAt IS NULL
                """, keyHash);
        if (keysList.size() != 1) {
            // 키 해시는 남기지 않는다. 사용 중인 키를 찾지 못했다는 사실만 남긴다.
            logger.info("사용 중인 API 키를 찾지 못해 거절: operation={}, clientIp={}, 조회={}건",
                    inMap.getString("operation"), inMap.getString("clientIp"), keysList.size());
            throw new LedgerEx(LedgerCode.UNAUTHENTICATED);
        }

        SharedMap<String, Object> keyMap   = keysList.get(0);
        String                    company  = keyMap.getString("mchtId");
        byte[]                    clientIp = LedgerIp.address(inMap.getString("clientIp"));

        // 허용 IP는 키가 아니라 Wallet 의 설정이다. 키를 재발급해도 이 목록이 그대로 쓰인다.
        List<SharedMap<String, Object>> allowedList = rows(db, """
                SELECT ip FROM WX.WALLET_API_IPS WHERE walletId=? AND status='사용'
                """, keyMap.getString("walletId"));
        if (clientIp == null || allowedList.isEmpty()
                || allowedList.stream().map(rowMap -> rowMap.getString("ip"))
                        .noneMatch(rule -> !rule.isBlank() && LedgerIp.matches(clientIp, rule))) {
            logger.info("허용 IP가 아니어서 거절: mchtId={}, walletId={}, operation={}, clientIp={}, 등록={}건",
                    company, keyMap.getString("walletId"), inMap.getString("operation"),
                    inMap.getString("clientIp"), allowedList.size());
            throw new LedgerEx(LedgerCode.FORBIDDEN);
        }

        String                          walletId   = keyMap.getString("walletId");
        List<SharedMap<String, Object>> walletList = rows(db, """
                SELECT B.balance,B.available AS availableBalance,W.status,W.apiStatus,H.acq
                  FROM WX.WALLET W JOIN WX.WALLET_BALANCE B ON B.walletId=W.id
                  LEFT JOIN WX.HQ_ACCNT H ON H.id=W.hqId
                 WHERE W.id=? AND W.mchtId=? AND W.ownerType='MCHT'
                """, walletId, company);
        if (walletList.size() != 1) {
            logger.info("API 키에 연결된 고객사 Wallet을 찾을 수 없어 거절: mchtId={}, walletId={}, operation={}",
                    company, walletId, inMap.getString("operation"));
            throw new LedgerEx(LedgerCode.KEY_WALLET_ERROR, "API 키의 Wallet 연결을 확인해 주세요.");
        }

        SharedMap<String, Object> walletMap = walletList.get(0);
        if (!walletMap.isEquals("status", "사용") || !walletMap.isEquals("apiStatus", "사용")) {
            throw new LedgerEx(LedgerCode.WALLET_UNAVAILABLE, "Wallet 사용 상태를 확인해 주세요.");
        }

        String action = inMap.getString("operation");
        if (!action.equals("authorize") && !inMap.isEquals("walletId", walletId)) {
            // 인증 때와 업무 때의 Wallet 이 다르다. 그 사이에 키 연결이 바뀐 것이다.
            logger.info("인증 후 Wallet 연결이 바뀌어 거절: mchtId={}, 인증={}, 현재={}, operation={}",
                    company, inMap.getString("walletId"), walletId, action);
            throw new LedgerEx(LedgerCode.KEY_WALLET_ERROR, "API 키의 Wallet 연결이 변경되었습니다.");
        }

        switch (action) {
            case "authorize":
                return SharedMap.<String, Object>of()
                        .add("walletId", walletId);
            case "balance":
                return SharedMap.<String, Object>of()
                        .add("balance", walletMap.getLong("balance"))
                        .add("availableBalance", walletMap.getLong("availableBalance"));
            case "inquiry":
                return inquiry(db, inMap, company, walletId);
            case "payout":
                requireActiveAcquirer(walletMap);
                requireServiceWindow(db, "transfer");
                return WxTransferRps.accept(db, inMap, company, walletId, WxPostingRps.postingTime(db));
            case "holder":
                requireServiceWindow(db, "holder");
                return WxHolderRps.accept(db, inMap, company, walletId, WxPostingRps.postingTime(db));
            default:
                logger.info("알 수 없는 업무 요청으로 거절: mchtId={}, operation={}", company, action);
                throw new LedgerEx(LedgerCode.INVALID);
        }
    }

    /** /api/internal/wallet/io: 본문 원문과 인증 키를 저장하지 않고 접수·응답 시각을 기록한다. */
    public static void recordIo(SharedMap<String,Object> inMap) throws SQLException {

        String id = inMap.getString("ioId");
        if (!id.matches("[a-f0-9]{32}") || !inMap.getString("keyHash").matches("[a-f0-9]{64}")) throw new LedgerEx(LedgerCode.INVALID);
        try (Connection db= WxDatabaseRps.open()) {
            int count;
            if (inMap.isEquals("phase","received")) {
                if (!java.util.Set.of("transfer","holder","inquiry","balance").contains(inMap.getString("apiCode"))
                        || !inMap.getString("requestHash").matches("[a-f0-9]{64}")) {
                    throw new LedgerEx(LedgerCode.INVALID);
                }
                count=WxDatabaseRps.update(db,"INSERT INTO WX.TRX_IO(id,mchtId,walletId,apiCode,apiKeyIdx,requestHash,regId,regDay)"
                        +" SELECT ?,k.mchtId,k.walletId,?,k.idx,?,'WX_API',DATE_FORMAT(CURRENT_DATE(),'%Y%m%d')"
                        +" FROM WX.WALLET_API_KEY k WHERE k.keyHash=? AND k.walletId=? AND k.status='사용' AND k.revokedAt IS NULL AND (k.expiresAt IS NULL OR k.expiresAt>NOW())",
                        id,inMap.getString("apiCode"),inMap.getString("requestHash"),inMap.getString("keyHash"),inMap.getString("walletId"));
            } else if (inMap.isEquals("phase","completed") && inMap.getString("resultCd").matches("[A-Z0-9]{1,24}") && inMap.getString("trackId").length()<=128) {
                // 결과 문구는 길이로 거절하지 않고 열 길이로 자른다. 문구를 못 담는 것이
                // 이력 한 건을 접수로 남기는 것보다 가볍다.
                count=WxDatabaseRps.update(db,"UPDATE WX.TRX_IO i JOIN WX.WALLET_API_KEY k ON k.idx=i.apiKeyIdx"
                        +" SET i.status='완료',i.udtDate=NOW(),i.resultCd=?,i.resultMsg=NULLIF(?,''),i.httpStatus=200,i.trackId=NULLIF(?,'')"
                        +" WHERE i.id=? AND i.walletId=? AND k.keyHash=? AND i.status='접수'",
                        inMap.getString("resultCd"),DBUtils.cut(inMap.getString("resultMsg"),128),
                        inMap.getString("trackId"),id,inMap.getString("walletId"),inMap.getString("keyHash"));
            } else {
                throw new LedgerEx(LedgerCode.INVALID);
            }
            if (count!=1) throw new LedgerEx(LedgerCode.CONFLICT);
            if (!db.getAutoCommit()) db.commit();
        }
    }

    /**
     * 송금 결과 조회.
     *
     * <p>거래를 가리키는 방법은 두 가지다. WX 거래번호 {@code trxId} 하나로 찾거나,
     * 고객사 주문번호 {@code trackId}와 거래일 {@code trxDay} 한 쌍으로 찾는다. 두 길 모두
     * 인증된 Wallet 범위 안에서만 찾으므로 다른 고객사의 거래가 나오지 않는다.</p>
     *
     * <p>날짜는 접수일과 완료일 둘 다 맞춘다. 응답이 유실된 고객사는 완료일을 모르기 때문이다.</p>
     */
    private static SharedMap<String, Object> inquiry(Connection db, SharedMap<String, Object> inMap,
                                                      String company, String walletId) throws SQLException {

        String select = """
                SELECT T.trxId,T.trackId,T.status,T.responseCode,T.bankCd,T.accntNo,T.sender,T.holder,T.amount,
                       T.feeAmt,
                       COALESCE(NULLIF(T.bankTrxId,''),NULLIF(W.acqTrxId,'')) AS acqTrxId,
                       DATE_FORMAT(T.regDate,'%Y%m%d%H%i%s') AS requestedAt,
                       DATE_FORMAT(COALESCE(T.udtDate,T.regDate),'%Y%m%d%H%i%s') AS registeredAt,W.acqTrxDate,W.id AS ledgerId,
                       W.balanceAfter AS balance
                  FROM WX.TRX_TRANSFER T
                  LEFT JOIN WX.TRX_WALLET W ON W.id=T.walletTrxId AND W.mchtId=T.mchtId AND W.walletId=T.walletId
                 WHERE T.mchtId=? AND T.walletId=? AND
                """;

        List<SharedMap<String, Object>> foundList;
        if (!inMap.isBlank("trxId")) {
            foundList = rows(db, select + "T.trxId=?", company, walletId, WxPostingRps.field(inMap, "trxId", 32));
        } else {
            String trackId = WxPostingRps.field(inMap, "trackId", 64);
            int lookupDay;
            try {
                String day = WxPostingRps.field(inMap, "trxDay", 8);
                if (!day.matches("[0-9]{8}")) throw new IllegalArgumentException();
                lookupDay = Integer.parseInt(LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE)
                        .format(DateTimeFormatter.BASIC_ISO_DATE));
            } catch (IllegalArgumentException | java.time.DateTimeException ex) {
                throw new LedgerEx(LedgerCode.INVALID, "거래조회 요청번호와 거래일을 확인해 주세요.");
            }
            foundList = rows(db, select + "T.trackId=? AND (T.regDay=? OR DATE_FORMAT(T.udtDate,'%Y%m%d')=?)",
                    company, walletId, trackId, lookupDay, lookupDay);
        }
        if (foundList.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND);

        SharedMap<String, Object> transferMap = foundList.get(0);
        String                    status      = transferMap.getString("status");
        String                    bankCode    = transferMap.getString("responseCode");
        SharedMap<String, Object> resultMap   = switch (status) {
            case "성공" -> originalResult("0000", "정상이체");
            case "환입" -> originalResult("0000", "원거래 송금 완료, 환입 처리됨");
            case "접수", "대기", "예약", "처리중" -> originalResult("W00X", "처리 중");
            case "실패" -> originalResult(bankCode.matches("[0-9]{4}") && !bankCode.equals("0000")
                    ? bankCode : "W010", "이체 실패 확정");
            default -> originalResult("W00X", "결과 확인 중");
        };

        resultMap.add("trxId", transferMap.getString("trxId"))
                .add("trackId", transferMap.getString("trackId"))
                .add("status", status)
                .add("bankCd", transferMap.getString("bankCd"))
                .add("accntNo", maskedAccount(transferMap.getString("accntNo")))
                .add("sender", transferMap.getString("sender"))
                .add("holder", transferMap.getString("holder"))
                .add("amount", transferMap.getLong("amount"))
                // 고객사가 정산에 바로 쓰도록 이 건에 적용한 수수료를 함께 내보낸다. VAT 별도 원화 정수다.
                .add("feeAmt", transferMap.getLong("feeAmt"))
                .add("requestedAt", transferMap.getString("requestedAt"));
        if (!transferMap.isBlank("acqTrxId")) resultMap.add("acqTrxId", transferMap.getString("acqTrxId"));
        if (!transferMap.isBlank("ledgerId")) resultMap.add("balance", transferMap.getLong("balance"));

        String bankTime = transferMap.getString("acqTrxDate");
        String createAt = resultMap.isEquals("rootResultCd", "0000") && isBankTime(bankTime)
                ? bankTime : transferMap.getString("registeredAt");
        if (!createAt.isBlank()) resultMap.add("createAt", createAt);
        return resultMap;
    }

    /** 송금·예금주 조회 원장에 기록할 은행 표시명을 은행 코드로 읽는다. */
    static String bankName(Connection db, String bankCd) throws SQLException {

        String  cacheKey       = "WX:API:REFERENCE:BANK:NAME:" + bankCd + ":0";
        boolean cacheAvailable = true;
        try {
            String name = RedisTextUtils.get(cacheKey);
            if (name != null) {
                return name;
            }
        } catch (RuntimeException unavailable) {
            cacheAvailable = false;
        }

        List<SharedMap<String, Object>> rowsList = rows(db,
                "SELECT COALESCE(NULLIF(nickKr,''),nameKr) AS name FROM WX.BANK WHERE code=?", bankCd);
        if (rowsList.isEmpty()) {
            return "";
        }

        String name = rowsList.get(0).getString("name");
        if (cacheAvailable) {
            try {
                RedisTextUtils.setNx(cacheKey, name, WalletPolicy.REFERENCE_CACHE_SECONDS);
            } catch (RuntimeException unavailable) {
                // 원장에서 읽은 은행 표시명을 사용한다.
            }
        }
        return name;
    }

    /** 기관 전문에 회신된 지급일시가 유효한 달력 값인지 확인한다. */
    private static boolean isBankTime(String value) {

        if (!value.matches("[0-9]{14}")) return false;
        try {
            return LocalDateTime.parse(value, BANK_TIME).getYear() > 0;
        } catch (DateTimeException invalid) {
            return false;
        }
    }

    /** 송금 응답의 계좌번호는 기존 마스킹을 유지하고 짧거나 비정상인 데이터도 원문을 노출하지 않는다. */
    static String maskedAccount(String account) {

        if (account.matches("[0-9*]*\\*[0-9*]*")) return account;
        if (account.matches("[0-9]{5,32}")) return MaskUtils.account(account);
        return "*".repeat(account.length());
    }

    /** 거래 조회 성공과 원거래 상태를 별도 코드로 전달한다. */
    private static SharedMap<String, Object> originalResult(String code, String message) {

        return SharedMap.<String, Object>of()
                .add("rootResultCd", code)
                .add("rootResultMsg", message);
    }

    /** 송금은 모계좌의 기관이 미사용이면 접수 전에 거절한다. */
    static void requireActiveAcquirer(SharedMap<String, Object> bankMap) {

        if (Acquirer.from(bankMap.getString("acq")).isInactive()) {
            throw new LedgerEx(LedgerCode.INACTIVE_ACQUIRER, WalletPolicy.INACTIVE_ACQUIRER_MSG);
        }
    }

    /** 송금·예금주 조회: WX DB에 승인된 업무 운영시간을 우선 적용한다. */
    private static void requireServiceWindow(Connection db, String apiCode) throws SQLException {

        if (!"transfer".equals(apiCode)) return;
        List<SharedMap<String, Object>> rulesList = rows(db, """
                SELECT '사용' AS status, 'Asia/Seoul' AS timeZone,
                       (firmStopStart IS NOT NULL OR firmStopEnd IS NOT NULL) AS stopEnabled,
                       INSERT(firmStopStart,3,0,':') AS stopStart, INSERT(firmStopEnd,3,0,':') AS stopEnd
                  FROM WX.CONFIG WHERE id=1
                """);
        if (rulesList.size() != 1) {
            logger.error("API 운영정책 행이 하나가 아니어서 접수 중지: apiCode={}, 조회={}건", apiCode, rulesList.size());
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "API 운영시간 정책을 확인해 주세요.");
        }
        if (isStopped(rulesList.get(0), WxPostingRps.postingTime(db).toLocalTime())) {
            logger.info("운영시간 정책에 따라 접수 중지: apiCode={}", apiCode);
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "API 운영시간 정책에 따라 송금이 중지되었습니다.");
        }
    }

    /** 송금·예금주 조회: 자정 경유 구간도 시작 시각 포함, 종료 시각 제외로 판정한다. */
    static boolean isStopped(SharedMap<String, Object> ruleMap, LocalTime now) {

        if (!ruleMap.isEquals("status", "사용")) return true;
        if (ruleMap.getInt("stopEnabled") == 0) return false;
        if (!ruleMap.isEquals("timeZone", "Asia/Seoul")) return true;
        try {
            LocalTime start = LocalTime.parse(ruleMap.getString("stopStart"));
            LocalTime end   = LocalTime.parse(ruleMap.getString("stopEnd"));
            if (start.equals(end)) return true;
            if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
            return !now.isBefore(start) || now.isBefore(end);
        } catch (java.time.DateTimeException invalid) {
            return true;
        }
    }
}

