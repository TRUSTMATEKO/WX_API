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

import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.db.Create;
import kr.tx24.lib.db.DBUtils;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.lang.MaskUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisUtils;
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
    private static final String AUTH_CACHE_PREFIX = "WX:API:AUTH:";
    private static final long AUTH_CACHE_SECONDS = 300;
    private static final String ACTIVE_KEY = """
            K.status='사용' AND M.status='사용'
            AND (K.expiresAt IS NULL OR K.expiresAt>NOW()) AND K.revokedAt IS NULL
            """;

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

        String action = inMap.getString("operation");
        if (action.equals("authorize")) return authorize(db, inMap, keyHash);

        List<SharedMap<String, Object>> keysList = rows(db, """
                SELECT K.idx,K.mchtId,K.walletId
                  FROM WX.WALLET_API_KEY K JOIN WX.MCHT M ON M.mchtId=K.mchtId
                 WHERE K.keyHash=? AND
                """ + ACTIVE_KEY, keyHash);
        if (keysList.size() != 1) {
            // 키 해시는 남기지 않는다. 사용 중인 키를 찾지 못했다는 사실만 남긴다.
            logger.info("사용 중인 API 키를 찾지 못해 거절: operation={}, clientIp={}, 조회={}건",
                    inMap.getString("operation"), inMap.getString("clientIp"), keysList.size());
            throw new LedgerEx(LedgerCode.UNAUTHENTICATED);
        }

        SharedMap<String, Object> keyMap   = keysList.get(0);
        String                    company  = keyMap.getString("mchtId");
        String                    walletId = keyMap.getString("walletId");

        // 허용 IP는 키가 아니라 Wallet 의 설정이다. 키를 재발급해도 이 목록이 그대로 쓰인다.
        List<SharedMap<String, Object>> allowedList = rows(db, """
                SELECT ip FROM WX.WALLET_API_IPS WHERE walletId=? AND status='사용'
                """, walletId);
        requireAllowedIp(inMap, keyMap, allowedList);

        Retrieve walletQuery = new Retrieve("WX.WALLET W")
                .columns("B.balance,B.available AS availableBalance,W.status,W.apiStatus")
                .joinInner("WX.WALLET_BALANCE B", "B.walletId=W.id")
                .where("W.id=? AND W.mchtId=? AND W.ownerType='MCHT'");
        if (!action.equals("balance") && !action.equals("inquiry")) {
            walletQuery.column("H.acq").joinLeftOuter("WX.HQ_ACCNT H", "H.id=W.hqId");
        }
        List<SharedMap<String, Object>> walletList = rows(db, walletQuery.build(), walletId, company);
        if (walletList.size() != 1) {
            logger.info("API 키에 연결된 고객사 Wallet을 찾을 수 없어 거절: mchtId={}, walletId={}, operation={}",
                    company, walletId, inMap.getString("operation"));
            throw new LedgerEx(LedgerCode.KEY_WALLET_ERROR, "API 키의 Wallet 연결을 확인해 주세요.");
        }

        SharedMap<String, Object> walletMap = walletList.get(0);
        requireWalletStatus(walletMap);

        if (!inMap.isEquals("walletId", walletId)) {
            // 인증 때와 업무 때의 Wallet 이 다르다. 그 사이에 키 연결이 바뀐 것이다.
            logger.info("인증 후 Wallet 연결이 바뀌어 거절: mchtId={}, 인증={}, 현재={}, operation={}",
                    company, inMap.getString("walletId"), walletId, action);
            throw new LedgerEx(LedgerCode.KEY_WALLET_ERROR, "API 키의 Wallet 연결이 변경되었습니다.");
        }

        switch (action) {
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

    /** 최초 인증은 키와 Wallet을 함께 읽고 사용 가능한 연결만 5분 동안 보관한다. */
    private static SharedMap<String, Object> authorize(Connection db, SharedMap<String, Object> inMap, String keyHash)
            throws SQLException {

        String cacheKey = AUTH_CACHE_PREFIX + keyHash;
        WxDatabaseRps.RowQuery keyQuery = () -> WxDatabaseRps.select(db, """
                SELECT K.idx,K.mchtId,K.walletId,W.status,W.apiStatus,B.walletId AS balanceWalletId
                  FROM WX.WALLET_API_KEY K JOIN WX.MCHT M ON M.mchtId=K.mchtId
                  LEFT JOIN WX.WALLET W ON W.id=K.walletId AND W.mchtId=K.mchtId AND W.ownerType='MCHT'
                  LEFT JOIN WX.WALLET_BALANCE B ON B.walletId=W.id
                 WHERE K.keyHash=? AND
                """ + ACTIVE_KEY, keyHash);
        SharedMap<String, Object> cachedMap = WxDatabaseRps.fetchRow(cacheKey, AUTH_CACHE_SECONDS, keyQuery);
        if (cachedMap == null) throw new LedgerEx(LedgerCode.UNAUTHENTICATED);

        if (cachedMap.getLong("idx") > 0 && !cachedMap.isBlank("walletId")
                && !cachedMap.isBlank("mchtId")) {
            // 캐시 연결은 힌트로 사용한다. IP 조회와 같은 SQL에서 현재 키 및 Wallet의 사용 상태를 확인한다.
            List<SharedMap<String, Object>> allowedList = rows(db, """
                    SELECT I.ip
                      FROM WX.WALLET_API_KEY K JOIN WX.MCHT M ON M.mchtId=K.mchtId
                      JOIN WX.WALLET W ON W.id=K.walletId AND W.mchtId=K.mchtId AND W.ownerType='MCHT'
                      JOIN WX.WALLET_BALANCE B ON B.walletId=W.id
                      LEFT JOIN WX.WALLET_API_IPS I ON I.walletId=W.id AND I.status='사용'
                     WHERE K.idx=? AND K.keyHash=? AND K.walletId=? AND K.mchtId=?
                       AND W.status='사용' AND W.apiStatus='사용' AND
                    """ + ACTIVE_KEY, cachedMap.getLong("idx"), keyHash,
                    cachedMap.getString("walletId"), cachedMap.getString("mchtId"));
            if (!allowedList.isEmpty()) {
                requireAllowedIp(inMap, cachedMap, allowedList);
                return authorizedWallet(cachedMap);
            }
        }

        // 현재 상태가 캐시와 다르면 기존 연결을 폐기하고 DB에서 거절 사유를 판정한다.
        try {
            RedisUtils.del(cacheKey);
        } catch (RuntimeException unavailable) {
            // 현재 요청에는 DB의 값을 사용한다.
        }
        List<SharedMap<String, Object>> keyList = keyQuery.select().getRows();
        if (keyList.size() != 1) {
            logger.info("사용 중인 API 키를 찾지 못해 거절: operation=authorize, clientIp={}, 조회={}건",
                    inMap.getString("clientIp"), keyList.size());
            throw new LedgerEx(LedgerCode.UNAUTHENTICATED);
        }

        SharedMap<String, Object> keyMap = keyList.get(0);
        List<SharedMap<String, Object>> allowedList = rows(db, """
                SELECT ip FROM WX.WALLET_API_IPS WHERE walletId=? AND status='사용'
                """, keyMap.getString("walletId"));
        requireAllowedIp(inMap, keyMap, allowedList);
        if (keyMap.isBlank("balanceWalletId")) {
            logger.info("API 키에 연결된 고객사 Wallet을 찾을 수 없어 거절: mchtId={}, walletId={}, operation=authorize",
                    keyMap.getString("mchtId"), keyMap.getString("walletId"));
            throw new LedgerEx(LedgerCode.KEY_WALLET_ERROR, "API 키의 Wallet 연결을 확인해 주세요.");
        }
        requireWalletStatus(keyMap);

        return authorizedWallet(keyMap);
    }

    /** 고객사 응답 이력은 인증 시점의 고객사, Wallet과 키 행을 사용한다. */
    private static SharedMap<String, Object> authorizedWallet(SharedMap<String, Object> keyMap) {

        return SharedMap.<String, Object>of()
                .add("walletId", keyMap.getString("walletId"))
                .add("mchtId", keyMap.getString("mchtId"))
                .add("idx", keyMap.getLong("idx"));
    }

    /** Wallet에 등록된 현재 IP 규칙으로 접속을 검사한다. */
    private static void requireAllowedIp(SharedMap<String, Object> inMap, SharedMap<String, Object> keyMap,
                                         List<SharedMap<String, Object>> allowedList) {

        byte[] clientIp = LedgerIp.address(inMap.getString("clientIp"));
        if (clientIp == null || allowedList.stream().map(rowMap -> rowMap.getString("ip"))
                .noneMatch(rule -> !rule.isBlank() && LedgerIp.matches(clientIp, rule))) {
            long registered = allowedList.stream().filter(rowMap -> !rowMap.isBlank("ip")).count();
            logger.info("허용 IP가 아니어서 거절: mchtId={}, walletId={}, operation={}, clientIp={}, 등록={}건",
                    keyMap.getString("mchtId"), keyMap.getString("walletId"), inMap.getString("operation"),
                    inMap.getString("clientIp"), registered);
            throw new LedgerEx(LedgerCode.FORBIDDEN);
        }
    }

    /** Wallet과 API가 모두 사용 중이어야 한다. */
    private static void requireWalletStatus(SharedMap<String, Object> walletMap) {

        if (!walletMap.isEquals("status", "사용") || !walletMap.isEquals("apiStatus", "사용")) {
            throw new LedgerEx(LedgerCode.WALLET_UNAVAILABLE, "Wallet 사용 상태를 확인해 주세요.");
        }
    }

    /** 고객사 응답 종료 뒤 요청 이력을 완료 상태로 한 번 삽입한다. */
    public static void recordIo(SharedMap<String, Object> inMap) {

        if (!inMap.getString("ioId").matches("[a-f0-9]{32}")
                || !java.util.Set.of("transfer", "holder", "inquiry", "balance").contains(inMap.getString("apiCode"))
                || !inMap.getString("requestHash").matches("[a-f0-9]{64}")
                || !inMap.getString("resultCd").matches("[A-Z0-9]{1,24}") || inMap.getString("trackId").length() > 128
                || inMap.getLong("apiKeyIdx") <= 0 || inMap.isBlank("mchtId") || inMap.isBlank("walletId")) {
            throw new LedgerEx(LedgerCode.INVALID);
        }

        SharedMap<String, Object> rowMap = SharedMap.<String, Object>of()
                .add("id", inMap.getString("ioId"))
                .add("mchtId", inMap.getString("mchtId"))
                .add("walletId", inMap.getString("walletId"))
                .add("apiCode", inMap.getString("apiCode"))
                .add("apiKeyIdx", inMap.getLong("apiKeyIdx"))
                .add("requestHash", inMap.getString("requestHash"))
                .add("status", "완료")
                .add("resultCd", inMap.getString("resultCd"))
                .add("httpStatus", 200)
                .add("regId", "WX_API")
                .add("regDay", inMap.getInt("regDay"))
                .add("regDate", inMap.get("regDate"))
                .add("udtDate", DateUtils.getCurrentTimestamp());
        if (!inMap.isBlank("resultMsg")) rowMap.put("resultMsg", DBUtils.cut(inMap.getString("resultMsg"), 128));
        if (!inMap.isBlank("trackId")) rowMap.put("trackId", inMap.getString("trackId"));

        if (new Create("WX.TRX_IO").record(rowMap).insert() != 1) throw new LedgerEx(LedgerCode.CONFLICT);
    }

    /**
     * 송금 결과 조회.
     *
     * <p>거래를 가리키는 방법은 두 가지다. WX 거래번호 {@code trxId} 하나로 찾거나,
     * 고객사 주문번호 {@code trackId}와 거래일 {@code trxDay} 한 쌍으로 찾는다. 두 길 모두
     * 인증된 Wallet 범위 안에서만 찾으므로 다른 고객사의 거래가 나오지 않는다.</p>
     *
     * <p>주문번호 조회의 거래일은 송금 접수일(regDay)이다.</p>
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
                LocalDate date = LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE);
                if (date.getYear() < 1) throw new IllegalArgumentException();
                lookupDay = Integer.parseInt(DateUtils.toString(date));
            } catch (IllegalArgumentException | java.time.DateTimeException ex) {
                throw new LedgerEx(LedgerCode.INVALID, "거래조회 요청번호와 거래일을 확인해 주세요.");
            }
            foundList = rows(db, select + "T.regDay=? AND T.trackId=?", company, walletId, lookupDay, trackId);
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

        String key = "WX:API:REFERENCE:BANK:ROW:" + bankCd;
        SharedMap<String, Object> nameMap = WxDatabaseRps.fetchRow(key, WalletPolicy.REFERENCE_CACHE_SECONDS,
                () -> WxDatabaseRps.select(db, "SELECT COALESCE(NULLIF(nickKr,''),nameKr) AS name FROM WX.BANK WHERE code=?", bankCd));
        return nameMap == null ? "" : nameMap.getString("name");
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
                SELECT '사용' AS status,
                       (firmStopStart IS NOT NULL OR firmStopEnd IS NOT NULL) AS stopEnabled,
                       firmStopStart AS stopStart, firmStopEnd AS stopEnd
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
        String startValue = ruleMap.getString("stopStart");
        String endValue   = ruleMap.getString("stopEnd");
        String hhmm       = "(?:[01][0-9]|2[0-3])[0-5][0-9]";
        if (!startValue.matches(hhmm) || !endValue.matches(hhmm)) return true;

        try {
            LocalTime start = DateUtils.getTime(startValue, "HHmm");
            LocalTime end   = DateUtils.getTime(endValue, "HHmm");
            if (start.equals(end)) return true;
            if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
            return !now.isBefore(start) || now.isBefore(end);
        } catch (java.time.DateTimeException invalid) {
            return true;
        }
    }
}

