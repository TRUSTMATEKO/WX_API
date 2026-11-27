package kr.tx24.wx.api.ctx;

import kr.tx24.lib.map.SharedMap;

/**
 * Wallet 요청 한 건이 계층을 지나는 동안의 상태를 담는다.
 *
 * <p>OPL_API의 {@code EwalletContext}와 같은 자리다. 검증·인증·업무 계층이 메서드 인자를 길게
 * 주고받지 않고 이 객체 하나만 전달한다.</p>
 */
public final class WalletContext {

    private final String action;
    private final String authorization;
    private final String clientIp;
    private final String contentType;
    private final String trace;

    private String keyHash = "";
    private String walletId = "";
    private byte[] body;
    private SharedMap<String, Object> input;
    private SharedMap<String, Object> io;
    private SharedMap<String, Object> ledger;

    /** 요청 수신 값만으로 컨텍스트를 만든다. 나머지는 계층이 채운다. */
    public WalletContext(String action, String authorization, String clientIp, String contentType, String trace) {

        this.action = action;
        this.authorization = authorization;
        this.clientIp = clientIp;
        this.contentType = contentType;
        this.trace = trace;
    }

    /** 업무 구분. `balance`, `inquiry`, `payout`, `holder` 중 하나다. */
    public String getAction() {

        return action;
    }

    /** 송금 checksum 확인에만 쓴다. 로그와 응답에는 넣지 않는다. */
    public String getAuthorization() {

        return authorization;
    }

    public String getClientIp() {

        return clientIp;
    }

    public String getContentType() {

        return contentType;
    }

    public String getTrace() {

        return trace;
    }

    public String getKeyHash() {

        return keyHash;
    }

    public void setKeyHash(String keyHash) {

        this.keyHash = keyHash;
    }

    /** 인증으로 확정된 Wallet. 인증 전에는 빈 문자열이다. */
    public String getWalletId() {

        return walletId;
    }

    public void setWalletId(String walletId) {

        this.walletId = walletId;
    }

    public byte[] getBody() {

        return body;
    }

    public void setBody(byte[] body) {

        this.body = body;
    }

    /** 검증을 통과한 요청 필드. */
    public SharedMap<String, Object> getInput() {

        return input;
    }

    public void setInput(SharedMap<String, Object> input) {

        this.input = input;
    }

    /** API 요청 이력(`TRX_IO`) 기록 값. `ioId`와 `requestHash`를 담는다. */
    public SharedMap<String, Object> getIo() {

        return io;
    }

    public void setIo(SharedMap<String, Object> io) {

        this.io = io;
    }

    /** 송금 접수로 원장이 돌려준 값. 기관 전송 입력과 응답 작성에 쓴다. */
    public SharedMap<String, Object> getLedger() {

        return ledger;
    }

    public void setLedger(SharedMap<String, Object> ledger) {

        this.ledger = ledger;
    }

    /** 인증이 끝났는지. 끝난 뒤의 실패 응답에는 `walletId`를 함께 돌려준다. */
    public boolean isAuthorized() {

        return !walletId.isEmpty();
    }
}
