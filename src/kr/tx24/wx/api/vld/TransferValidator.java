package kr.tx24.wx.api.vld;

import kr.tx24.lib.cipher.HMAC;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.ctx.WalletContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 송금 전용 검증이다.
 *
 * <p>OPL_API의 {@code PayoutValidator}와 같은 자리다. 잔액·한도·중복은 원장이 잠금 안에서
 * 판단하므로 여기서는 보지 않는다. 여기서 막는 것은 **차감 전에 막아야 하는 것**뿐이다.</p>
 */
public final class TransferValidator {

    private static final Logger logger = LoggerFactory.getLogger(TransferValidator.class);

    /**
     * 송금을 접수해도 되는 상태인지 확인한다.
     *
     * <p>요청 자체가 틀린 것을 먼저 알리고, 그다음 기관 전송 환경을 본다. 기관으로 전문을 보낼 수
     * 없는 상태에서 접수하면 잔액만 묶이므로 원장 접수를 호출하기 전에 막는다.
     * 모계좌의 기관 설정은 원장이 차감 전에 확인한다.</p>
     */
    public void isTransferable(WalletContext etx) {

        // 요청 자체가 틀린 것을 접수 중지보다 먼저 알린다.
        if (!isChecksumValid(etx.getInput(), etx.getAuthorization(), etx.getWalletId())) {
            throw new WalletException("W001", "송금 요청의 checksum이 일치하지 않습니다.");
        }
        if (!WalletConfig.upstreamReady()) {
            logger.warn("기관 전송 환경이 없어 송금을 접수하지 않는다: trackId={}, trace={}",
                    etx.getInput().getString("trackId"), etx.getTrace());
            throw new WalletException("W003", "기관 연동 준비 전으로 송금 접수가 중지되었습니다.");
        }
    }

    /** 송금 본문의 checksum을 인증된 Wallet과 Authorization 키로 확인한다. */
    private static boolean isChecksumValid(SharedMap<String, Object> input, String authorization, String walletId) {

        String message = String.join("\n", WalletPolicy.TRANSFER_SIGNATURE, walletId, input.getString("trackId"),
                input.getString("bankCd"), input.getString("accntNo"), Long.toString(input.getLong("amount")),
                input.getString("sender"), input.getString("holder"), input.getString("note"));
        String expected = HMAC.hmacSha256(authorization, message);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                input.getString("checksum").getBytes(StandardCharsets.US_ASCII));
    }
}
