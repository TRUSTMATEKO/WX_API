package kr.tx24.wx.ledger.enm;

/**
 * 원장이 업무 요청을 거절할 때 쓰는 코드다.
 *
 * <p>원장은 자기 거절 사유만 안다. 그 사유를 고객사 응답이나 화면 응답으로 바꾸는 일은 호출자가 한다.
 * WX_API는 {@code WalletLedgerRps}에서 이 코드를 받아 {@code WalletResult.ledger}가 {@code W***}로
 * 바꾸고, WX_ADM은 {@code WxApiRps}가 다시 화면 코드로 바꾼다.</p>
 *
 * <p>코드 문자열은 WX_ADM {@code kr.tx24.wx.enm.ResultCode}의 같은 이름 값과 **같아야 한다.**
 * {@code WalletResult.ledger}의 분기가 이 문자열을 읽는다. 다만 그 쪽은 화면 전용 값
 * ({@code OK}, {@code LOGIN_EXPIRED}, {@code TWO_FA_REQUIRED}, {@code RATE_LIMITED})까지 가진 반면
 * 원장은 여섯 가지 내부 코드와 거래 거절의 세부 사유를 쓴다. 원장이 로그인이나 2차 인증을 판단하지 않기 때문이다.</p>
 */
public enum LedgerCode {

    /** 요청값이 허용 범위를 벗어났다. */
    INVALID("TX400", "요청값을 확인해 주세요."),

    /** 키를 확인하지 못했다. */
    UNAUTHENTICATED("TX401", "인증에 실패했습니다."),

    /** 키는 맞으나 접속 주소가 허용 범위를 벗어났다. */
    FORBIDDEN("TX403", "접근 권한이 없습니다."),

    /** 대상 거래를 찾지 못했다. */
    NOT_FOUND("TX404", "요청한 정보를 찾을 수 없습니다."),

    /** 상태·잔액·중복 때문에 지금 처리할 수 없다. */
    CONFLICT("TX409", "요청 상태가 올바르지 않습니다."),

    // 고객사 거래 거절의 세부 사유. 내부 원장 코드는 모두 TX409를 유지한다.
    DUPLICATE_REQUEST(CONFLICT),
    LIMIT_EXCEEDED(CONFLICT),
    KEY_WALLET_ERROR(CONFLICT),
    WALLET_UNAVAILABLE(CONFLICT),
    INSUFFICIENT_BALANCE(CONFLICT),
    REQUEST_UNAVAILABLE(CONFLICT),
    INACTIVE_ACQUIRER(CONFLICT),

    /** 설정이나 운영시간 정책 때문에 접수를 중지했다. */
    UNAVAILABLE("TX503", "현재 서비스를 이용할 수 없습니다.");

    private final String code;
    private final String message;

    LedgerCode(LedgerCode category) {

        this(category.code, category.message);
    }

    LedgerCode(String code, String message) {

        this.code    = code;
        this.message = message;
    }

    public String getCode() {

        return code;
    }

    public String getMessage() {

        return message;
    }
}
