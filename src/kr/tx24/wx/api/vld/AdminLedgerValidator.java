package kr.tx24.wx.api.vld;

import java.util.Set;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;

/**
 * 본사 화면이 요청한 원장 변경의 필수값과 모양을 검증한다.
 *
 * <p>{@link HqWalletValidator}와 같은 자리다. 잔액·중복·상태는 원장이 잠금 안에서 판단하므로
 * 여기서는 보지 않는다. 허용한 필드만 담아 원장으로 넘기고 그 밖의 값은 버린다.</p>
 */
public final class AdminLedgerValidator {

    /** 운영자가 종결할 수 있는 결과. 미확인으로는 종결하지 않는다. 그것은 지금 상태다. */
    private static final Set<String> OUTCOME = Set.of("성공", "실패");

    /** 수동 입금이 받는 수신경로. 통지가 쓰는 값과 같다. */
    private static final Set<String> SOURCE_TYPES = Set.of("va", "sms");

    /** 한 건의 금액 상한. 본사 화면이 실수로 큰 값을 보내는 것을 수신 쪽에서도 막는다. */
    private static final long AMOUNT_MAX = 1_000_000_000L;

    /**
     * 미확정 송금의 운영자 종결 요청을 검증한다.
     *
     * <p>기관 원거래번호와 지급일시는 화면이 보여 준 원장 값을 그대로 전달한다. 없을 수 있으므로
     * 필수로 보지 않는다. 그 값이 없는 건이야말로 운영자 종결의 대상이다.</p>
     */
    public SharedMap<String, Object> settle(SharedMap<String, Object> inMap) {

        String outcome = text(inMap, "outcome", 12);
        if (!OUTCOME.contains(outcome)) {
            throw new WalletException("W001", "종결 결과는 성공 또는 실패만 선택할 수 있습니다.");
        }
        return SharedMap.<String, Object>of()
                .add("trxId", text(inMap, "trxId", 32))
                .add("outcome", outcome)
                .add("responseCode", text(inMap, "responseCode", 8))
                .add("responseMsg", text(inMap, "responseMsg", 64))
                .add("acqTrxId", optional(inMap, "acqTrxId", 32))
                .add("acqTrxDate", optional(inMap, "acqTrxDate", 20))
                .add("actor", text(inMap, "userId", 24))
                .add("reason", text(inMap, "reason", 1024));
    }

    /** 미매칭 입금의 Wallet 지정 요청을 검증한다. */
    public SharedMap<String, Object> assign(SharedMap<String, Object> inMap) {

        return SharedMap.<String, Object>of()
                .add("depositId", text(inMap, "depositId", 32))
                .add("walletId", text(inMap, "walletId", 32))
                .add("userId", text(inMap, "userId", 24))
                .add("reason", text(inMap, "reason", 128));
    }

    /**
     * 수동 입금 요청을 검증한다.
     *
     * <p>통지가 오지 않은 입금을 운영자가 넣는다. Wallet 을 사람이 고르므로 계좌번호는 받지 않고
     * 그 Wallet 의 귀속에서 원장이 세운다. 받는 것은 어디에 얼마가 언제 누구 이름으로 들어왔는지
     * 다섯 값이다.</p>
     */
    public SharedMap<String, Object> manual(SharedMap<String, Object> inMap) {

        long amount = inMap.getLong("amount");
        if (amount <= 0 || amount > AMOUNT_MAX) throw new WalletException("W001", "입금액을 확인해 주세요.");
        String sourceType = text(inMap, "sourceType", 12);
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new WalletException("W001", "수신경로를 확인해 주세요.");
        }
        String occurredAt = text(inMap, "occurredAt", 14);
        if (!occurredAt.matches("[0-9]{14}")) {
            throw new WalletException("W001", "입금시간을 확인해 주세요.");
        }
        return SharedMap.<String, Object>of()
                .add("walletId", text(inMap, "walletId", 32))
                .add("sourceType", sourceType)
                .add("depositName", text(inMap, "depositName", 128))
                .add("occurredAt", occurredAt)
                .add("amount", amount)
                .add("userId", text(inMap, "userId", 24))
                .add("reason", text(inMap, "reason", 128));
    }

    /**
     * 월 청구 처리 요청을 검증한다.
     *
     * <p>금액은 받지 않는다. 바뀌는 것은 상태와 청구일뿐이다. 청구일은 세금계산서 발행일에 맞출 수
     * 있어 오늘이 아닌 날도 받되 모양만 본다.</p>
     */
    public SharedMap<String, Object> bill(SharedMap<String, Object> inMap) {

        int billDay = inMap.getInt("billDay");
        if (billDay < 19000101 || billDay > 99991231) {
            throw new WalletException("W001", "청구일을 확인해 주세요.");
        }
        return SharedMap.<String, Object>of()
                .add("invoiceId", text(inMap, "invoiceId", 64))
                .add("billDay", billDay)
                .add("userId", text(inMap, "userId", 24))
                .add("reason", text(inMap, "reason", 128));
    }

    /**
     * 본사 입금 요청을 검증한다.
     *
     * <p>Wallet 을 받지 않는다. 어느 모계좌의 본사 Wallet 인지는 입금 기록이 알려 주므로 화면이
     * 고를 것이 없다. 잘못된 Wallet 을 받을 여지도 없앤다.</p>
     */
    public SharedMap<String, Object> assignHq(SharedMap<String, Object> inMap) {

        return SharedMap.<String, Object>of()
                .add("depositId", text(inMap, "depositId", 32))
                .add("userId", text(inMap, "userId", 24))
                .add("reason", text(inMap, "reason", 128));
    }

    /** 미매칭 입금의 반려 요청을 검증한다. Wallet 을 지정하지 않는다. */
    public SharedMap<String, Object> reject(SharedMap<String, Object> inMap) {

        return SharedMap.<String, Object>of()
                .add("depositId", text(inMap, "depositId", 32))
                .add("userId", text(inMap, "userId", 24))
                .add("reason", text(inMap, "reason", 128));
    }

    /**
     * Wallet 보류 설정과 해제 요청을 검증한다.
     *
     * <p>목표 금액을 그대로 받는다. 더하거나 빼지 않으므로 같은 요청을 두 번 보내도 결과가 같다.
     * 0 은 해제다. 음수는 받지 않는다.</p>
     */
    public SharedMap<String, Object> hold(SharedMap<String, Object> inMap) {

        long amount = inMap.getLong("amount");
        if (amount < 0) throw new WalletException("W001", "보류 금액을 확인해 주세요.");
        return SharedMap.<String, Object>of()
                .add("walletId", text(inMap, "walletId", 32))
                .add("amount", amount)
                .add("userId", text(inMap, "userId", 24))
                .add("reason", text(inMap, "reason", 512));
    }

    /** 웹훅 재발송 요청을 검증한다. 발송 행 하나를 가리킨다. */
    public SharedMap<String, Object> webhookRetry(SharedMap<String, Object> inMap) {

        return SharedMap.<String, Object>of()
                .add("hookId", text(inMap, "hookId", 32))
                .add("userId", text(inMap, "userId", 24));
    }

    /** 필수 문자열: 공백·길이·제어문자를 확인하고 원문을 유지한다. */
    private static String text(SharedMap<String, Object> inMap, String field, int max) {

        String value = inMap.getString(field).strip();
        if (value.isEmpty() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new WalletException("W001", field + " 값을 확인해 주세요.");
        }
        return value;
    }

    /** 선택 문자열: 비어 있어도 되지만 길이와 제어문자는 같은 기준으로 본다. */
    private static String optional(SharedMap<String, Object> inMap, String field, int max) {

        String value = inMap.getString(field).strip();
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new WalletException("W001", field + " 값을 확인해 주세요.");
        }
        return value;
    }
}
