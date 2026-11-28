package kr.tx24.wx.ledger.ex;

import kr.tx24.wx.ledger.enm.LedgerCode;

/**
 * 원장이 업무 요청을 거절할 때 던지는 예외다.
 *
 * <p>거절 사유를 아는 실패만 이 예외로 던진다. 사유를 모르는 실패는 그대로 올려 호출자가
 * 시스템 오류로 처리한다. 그래서 {@code SQLException}을 이 예외로 감싸지 않는다.</p>
 *
 * <p>메시지는 화면이나 고객사에 그대로 보일 수 있다. 키·계좌번호 평문·요청 전문을 담지 않는다.</p>
 */
public class LedgerEx extends RuntimeException {

    private final LedgerCode code;

    /** 거절 사유의 기본 문구를 쓴다. */
    public LedgerEx(LedgerCode code) {

        this(code, code.getMessage());
    }

    /** 거절 사유에 업무별 안내 문구를 붙인다. */
    public LedgerEx(LedgerCode code, String message) {

        super(message);
        this.code = code;
    }

    public LedgerCode getCode() {

        return code;
    }
}
