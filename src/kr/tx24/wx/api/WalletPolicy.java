package kr.tx24.wx.api;

import java.util.Set;

/**
 * Wallet API의 업무 계약 상수를 한곳에 둔다.
 *
 * <p>OPL_API의 {@code EwalletPolicy}와 같은 자리다. 코드·상한·기관 응답 코드처럼
 * 여러 계층이 같이 보는 값만 둔다. 설정에서 읽는 값은 {@link WalletConfig}에 있다.</p>
 */
public final class WalletPolicy {

    /** 기관 전문이 응답 없이 끝난 경우의 코드. HyphenPisp가 요청 전문에 심어 돌려준다. */
    public static final String NO_RESPONSE = "X000";

    /** 기관 정상 처리 코드. */
    public static final String SUCCEEDED = "0000";

    /** 기관에 전문을 보내기 전 단계에서 끝난 실패. 잔액을 즉시 원복한다. */
    public static final String NOT_SENT = "W005";

    /** 송금 확정 상태. WX_ADM의 확정 계약과 같은 값을 쓴다. */
    public static final String OUTCOME_SUCCESS = "성공";
    public static final String OUTCOME_FAILED  = "실패";
    public static final String OUTCOME_UNKNOWN = "미확인";

    /** 내부 응답 프레임 상한과 고객사 요청 본문 상한. 두 값이 같아야 한다. */
    public static final int FRAME = 8192;

    /** 조회성 내부 호출 제한. */
    public static final int QUERY_TIMEOUT = 5000;

    /**
     * 원장 확정 내부 호출 제한.
     *
     * <p>기관 읽기 상한 15초보다 넉넉히 두어 확정 호출이 먼저 끊기지 않게 한다.
     * 확정 호출이 끊기면 결과를 아는데도 미확정으로 남는다.</p>
     */
    public static final int LEDGER_TIMEOUT = 20000;

    /** 송금 checksum 서명 접두. 고객사와 공유하는 고정 문자열이다. */
    public static final String TRANSFER_SIGNATURE = "WX-WALLET-TRANSFER-V1";

    /** 송금 요청에 허용하는 필드. 그 밖의 필드가 있으면 거절한다. */
    public static final Set<String> TRANSFER_FIELDS = Set.of("trackId", "bankCd", "accntNo", "amount",
            "sender", "holder", "note", "checksum");

    /** 거래조회 응답에 복사하는 원장 필드. */
    public static final Set<String> TRANSFER_RESULT_FIELDS = Set.of("trxId", "trackId", "acqTrxId", "status",
            "bankCd", "accntNo", "sender", "holder", "amount", "feeAmt", "requestedAt", "createAt", "balance",
            "availableBalance");

    /** 송금 금액 상한. 전문 자릿수와 무관하게 입력 단계에서 먼저 막는다. */
    public static final long AMOUNT_MAX = 999_999_999_999_999_999L;

    /** 통신장애 재조회에서 한 번에 꺼내는 최대 건수. WX_ADM도 같은 상한을 둔다. */
    public static final int RECOVERY_LIMIT = 10;

    private WalletPolicy() {

    }
}
