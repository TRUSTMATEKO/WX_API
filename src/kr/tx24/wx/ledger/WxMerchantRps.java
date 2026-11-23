package kr.tx24.wx.ledger;

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

import kr.tx24.lib.lang.MaskUtils;
import kr.tx24.lib.map.SharedMap;

import static kr.tx24.wx.ledger.WxDatabaseRps.rows;

/** 인증된 키, 고객사 허용 IP와 키에 매핑된 Wallet을 기준으로 공개 API 업무 범위를 결정한다. */
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
    public static SharedMap<String, Object> query(SharedMap<String, Object> in) throws SQLException {

        // 송금과 예금주 조회는 기록을 남긴다. 나머지 조회는 조회 전용 트랜잭션을 쓴다.
        boolean write = java.util.Set.of("payout", "holder").contains(in.getString("operation"));
        try (Connection db = WxDatabaseRps.open()) {
            db.setTransactionIsolation(write ? Connection.TRANSACTION_READ_COMMITTED
                    : Connection.TRANSACTION_REPEATABLE_READ);
            db.setReadOnly(!write);
            db.setAutoCommit(false);
            try {
                SharedMap<String, Object> result = query(db, in);
                db.commit();
                return result;
            } catch (RuntimeException | SQLException | Error failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    /** WX_API 내부 계약: 키가 속한 고객사를 찾고 IP를 확인한 후 해당 Wallet의 업무만 처리한다. */
    static SharedMap<String, Object> query(Connection db, SharedMap<String, Object> in)
            throws SQLException {

        String keyHash = in.getString("keyHash");
        if (!keyHash.matches("(?i)[0-9a-f]{64}")) {
            logger.warn("API 키 형식 오류로 거절: operation={}, clientIp={}",
                    in.getString("operation"), in.getString("clientIp"));
            throw new LedgerEx(LedgerCode.UNAUTHENTICATED);
        }

        List<SharedMap<String, Object>> keys = rows(db, """
                SELECT K.idx,K.mchtId,K.walletId,K.ips
                  FROM WX.MCHT_WALLET_API_KEY K JOIN WX.MCHT M ON M.mchtId=K.mchtId
                 WHERE K.keyHash=? AND K.status='사용' AND M.status='사용'
                   AND (K.expiresAt IS NULL OR K.expiresAt>NOW()) AND K.revokedAt IS NULL
                """, keyHash);
        if (keys.size() != 1) {
            // 키 해시는 남기지 않는다. 사용 중인 키를 찾지 못했다는 사실만 남긴다.
            logger.warn("사용 중인 API 키를 찾지 못해 거절: operation={}, clientIp={}, 조회={}건",
                    in.getString("operation"), in.getString("clientIp"), keys.size());
            throw new LedgerEx(LedgerCode.UNAUTHENTICATED);
        }

        SharedMap<String, Object> key = keys.get(0);
        String company  = key.getString("mchtId");
        byte[] clientIp = LedgerIp.address(in.getString("clientIp"));
        if (clientIp == null || java.util.Arrays.stream(key.getString("ips").split(","))
                .noneMatch(rule -> !rule.isBlank() && LedgerIp.matches(clientIp, rule))) {
            logger.warn("허용 IP가 아니어서 거절: mchtId={}, walletId={}, operation={}, clientIp={}",
                    company, key.getString("walletId"), in.getString("operation"), in.getString("clientIp"));
            throw new LedgerEx(LedgerCode.FORBIDDEN);
        }

        String walletId = key.getString("walletId");
        List<SharedMap<String, Object>> wallet   = rows(db, """
                SELECT B.balance,B.available AS availableBalance
                  FROM WX.WALLET W JOIN WX.WALLET_BALANCE B ON B.walletId=W.id
                 WHERE W.id=? AND W.mchtId=? AND W.ownerType='MCHT' AND W.status='사용' AND W.apiStatus='사용'
                """, walletId, company);
        if (wallet.size() != 1) {
            logger.warn("Wallet 사용 상태가 아니어서 거절: mchtId={}, walletId={}, operation={}",
                    company, walletId, in.getString("operation"));
            throw new LedgerEx(LedgerCode.CONFLICT, "API 키의 Wallet 연결을 확인해 주세요.");
        }

        String action = in.getString("operation");
        if (!action.equals("authorize") && !in.isEquals("authorizedWalletId", walletId)) {
            // 인증 때와 업무 때의 Wallet 이 다르다. 그 사이에 키 연결이 바뀐 것이다.
            logger.warn("인증 후 Wallet 연결이 바뀌어 거절: mchtId={}, 인증={}, 현재={}, operation={}",
                    company, in.getString("authorizedWalletId"), walletId, action);
            throw new LedgerEx(LedgerCode.CONFLICT, "API 키의 Wallet 연결이 변경되었습니다.");
        }
        switch (action) {
            case "authorize":
                return SharedMap.<String, Object>of().add("walletId", walletId);
            case "balance":
                return wallet.get(0);
            case "inquiry":
                return inquiry(db, in, company, walletId);
            case "payout":
                requireServiceWindow(db, "transfer");
                return WxTransferRps.accept(db, in, company, walletId, WxPostingRps.postingTime(db));
            case "holder":
                requireServiceWindow(db, "holder");
                return WxHolderRps.accept(db, in, company, walletId, WxPostingRps.postingTime(db));
            default:
                logger.warn("알 수 없는 업무 요청으로 거절: mchtId={}, operation={}", company, action);
                throw new LedgerEx(LedgerCode.INVALID);
        }
    }

    /** /api/internal/wallet/io: 본문 원문과 인증 키를 저장하지 않고 접수·응답 시각을 기록한다. */
    public static void recordIo(SharedMap<String,Object> in) throws SQLException {

        String id = in.getString("ioId");
        if (!id.matches("[a-f0-9]{32}") || !in.getString("keyHash").matches("[a-f0-9]{64}")) throw new LedgerEx(LedgerCode.INVALID);
        try (Connection db= WxDatabaseRps.open()) {
            int count;
            if (in.isEquals("phase","received")) {
                if (!java.util.Set.of("transfer","holder","inquiry","balance").contains(in.getString("apiCode"))
                        || !in.getString("requestHash").matches("[a-f0-9]{64}")) {
                    throw new LedgerEx(LedgerCode.INVALID);
                }
                count=WxDatabaseRps.update(db,"INSERT INTO WX.MCHT_IO(id,mchtId,walletId,apiCode,apiKeyIdx,requestHash,regId,regDay)"
                        +" SELECT ?,k.mchtId,k.walletId,?,k.idx,?,'WX_API',DATE_FORMAT(CURRENT_DATE(),'%Y%m%d')"
                        +" FROM WX.MCHT_WALLET_API_KEY k WHERE k.keyHash=? AND k.walletId=? AND k.status='사용' AND k.revokedAt IS NULL AND (k.expiresAt IS NULL OR k.expiresAt>NOW())",
                        id,in.getString("apiCode"),in.getString("requestHash"),in.getString("keyHash"),in.getString("walletId"));
            } else if (in.isEquals("phase","completed") && in.getString("resultCd").matches("[A-Z0-9]{4}") && in.getString("trackId").length()<=128) {
                count=WxDatabaseRps.update(db,"UPDATE WX.MCHT_IO i JOIN WX.MCHT_WALLET_API_KEY k ON k.idx=i.apiKeyIdx"
                        +" SET i.status='완료',i.udtDate=NOW(),i.resultCd=?,i.httpStatus=200,i.trackId=NULLIF(?,'')"
                        +" WHERE i.id=? AND i.walletId=? AND k.keyHash=? AND i.status='접수'",
                        in.getString("resultCd"),in.getString("trackId"),id,in.getString("walletId"),in.getString("keyHash"));
            } else {
                throw new LedgerEx(LedgerCode.INVALID);
            }
            if (count!=1) throw new LedgerEx(LedgerCode.CONFLICT);
        }
    }

    /**
     * 송금 결과 조회.
     *
     * <p>거래를 가리키는 방법은 두 가지다. WX 거래번호 {@code trxId} 하나로 찾거나,
     * 고객사 요청번호 {@code trackId}와 거래일 {@code trxDay} 한 쌍으로 찾는다. 두 길 모두
     * 인증된 Wallet 범위 안에서만 찾으므로 다른 고객사의 거래가 나오지 않는다.</p>
     *
     * <p>날짜는 접수일과 완료일 둘 다 맞춘다. 응답이 유실된 고객사는 완료일을 모르기 때문이다.</p>
     */
    private static SharedMap<String, Object> inquiry(Connection db, SharedMap<String, Object> in,
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

        List<SharedMap<String, Object>> found;
        if (!in.isBlank("trxId")) {
            found = rows(db, select + "T.trxId=?", company, walletId, WxPostingRps.field(in, "trxId", 32));
        } else {
            String trackId = WxPostingRps.field(in, "trackId", 64);
            int lookupDay;
            try {
                String day = WxPostingRps.field(in, "trxDay", 8);
                if (!day.matches("[0-9]{8}")) throw new IllegalArgumentException();
                lookupDay = Integer.parseInt(LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE)
                        .format(DateTimeFormatter.BASIC_ISO_DATE));
            } catch (IllegalArgumentException | java.time.DateTimeException ex) {
                throw new LedgerEx(LedgerCode.INVALID, "거래조회 요청번호와 거래일을 확인해 주세요.");
            }
            found = rows(db, select + "T.trackId=? AND (T.regDay=? OR DATE_FORMAT(T.udtDate,'%Y%m%d')=?)",
                    company, walletId, trackId, lookupDay, lookupDay);
        }
        if (found.size() != 1) throw new LedgerEx(LedgerCode.NOT_FOUND);

        SharedMap<String, Object> transfer = found.get(0);
        String status   = transfer.getString("status");
        String bankCode = transfer.getString("responseCode");
        SharedMap<String, Object> result = switch (status) {
            case "성공" -> originalResult("0000", "정상이체");
            case "환입" -> originalResult("0000", "원거래 송금 완료, 환입 처리됨");
            case "접수", "대기", "예약", "처리중" -> originalResult("W00X", "처리 중");
            case "실패" -> originalResult(bankCode.matches("[0-9]{4}") && !bankCode.equals("0000")
                    ? bankCode : "W010", "이체 실패 확정");
            default -> originalResult("W00X", "결과 확인 중");
        };

        result.add("trxId", transfer.getString("trxId"))
                .add("trackId", transfer.getString("trackId"))
                .add("status", status)
                .add("bankCd", transfer.getString("bankCd"))
                .add("accntNo", maskedAccount(transfer.getString("accntNo")))
                .add("sender", transfer.getString("sender"))
                .add("holder", transfer.getString("holder"))
                .add("amount", transfer.getLong("amount"))
                // 고객사가 정산에 바로 쓰도록 이 건에 적용한 수수료를 함께 내보낸다. VAT 별도 원화 정수다.
                .add("feeAmt", transfer.getLong("feeAmt"))
                .add("requestedAt", transfer.getString("requestedAt"));
        if (!transfer.isBlank("acqTrxId")) result.add("acqTrxId", transfer.getString("acqTrxId"));
        if (!transfer.isBlank("ledgerId")) result.add("balance", transfer.getLong("balance"));

        String bankTime = transfer.getString("acqTrxDate");
        String createAt = result.isEquals("rootResultCd", "0000") && isBankTime(bankTime)
                ? bankTime : transfer.getString("registeredAt");
        if (!createAt.isBlank()) result.add("createAt", createAt);
        return result;
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

    /** 송금·예금주 조회: WX DB에 승인된 업무 운영시간을 우선 적용한다. */
    private static void requireServiceWindow(Connection db, String apiCode) throws SQLException {

        if (!"transfer".equals(apiCode)) return;
        List<SharedMap<String, Object>> rules = rows(db, """
                SELECT '사용' AS status, 'Asia/Seoul' AS timeZone,
                       (firmStopStart IS NOT NULL OR firmStopEnd IS NOT NULL) AS stopEnabled,
                       INSERT(firmStopStart,3,0,':') AS stopStart, INSERT(firmStopEnd,3,0,':') AS stopEnd
                  FROM WX.CONFIG WHERE id=1
                """);
        if (rules.size() != 1) {
            logger.warn("API 운영정책 행이 하나가 아니어서 접수 중지: apiCode={}, 조회={}건", apiCode, rules.size());
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "API 운영시간 정책을 확인해 주세요.");
        }
        if (isStopped(rules.get(0), WxPostingRps.postingTime(db).toLocalTime())) {
            logger.info("운영시간 정책에 따라 접수 중지: apiCode={}", apiCode);
            throw new LedgerEx(LedgerCode.UNAVAILABLE, "API 운영시간 정책에 따라 접수가 중지되었습니다.");
        }
    }

    /** 송금·예금주 조회: 자정 경유 구간도 시작 시각 포함, 종료 시각 제외로 판정한다. */
    static boolean isStopped(SharedMap<String, Object> rule, LocalTime now) {

        if (!rule.isEquals("status", "사용")) return true;
        if (rule.getInt("stopEnabled") == 0) return false;
        if (!rule.isEquals("timeZone", "Asia/Seoul")) return true;
        try {
            LocalTime start = LocalTime.parse(rule.getString("stopStart"));
            LocalTime end = LocalTime.parse(rule.getString("stopEnd"));
            if (start.equals(end)) return true;
            if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
            return !now.isBefore(start) || now.isBefore(end);
        } catch (java.time.DateTimeException invalid) {
            return true;
        }
    }
}

