package kr.tx24.wx.api;

/**
 * Wallet 업무 실패를 고객사 응답 코드와 함께 전달한다.
 *
 * <p>OPL_API의 {@code EwalletException}과 같은 자리다. 업무 계층은 이 예외만 던지고
 * 응답 변환은 {@link WalletResult}가 한 곳에서 처리한다. 코드를 모르는 실패는 이 예외가 아니라
 * 일반 예외로 올려 {@code W999}로 바꾼다.</p>
 */
public class WalletException extends RuntimeException {

    private final String resultCd;
    private final String advanceMsg;

    /** 업무 실패: 고객사에게 돌려줄 코드와 안내 문구를 지정한다. */
    public WalletException(String resultCd, String advanceMsg) {

        this(resultCd, advanceMsg, null);
    }

    /** 업무 실패: 원인 예외를 함께 보관한다. 원문과 키는 메시지에 넣지 않는다. */
    public WalletException(String resultCd, String advanceMsg, Throwable cause) {

        super(advanceMsg, cause);
        this.resultCd = resultCd;
        this.advanceMsg = advanceMsg;
    }

    public String getResultCd() {

        return resultCd;
    }

    public String getAdvanceMsg() {

        return advanceMsg;
    }
}
