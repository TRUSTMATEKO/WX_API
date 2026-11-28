package kr.tx24.wx.api.vld;

import kr.tx24.lib.cipher.HMAC;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.ctx.WalletContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 송금 전용 검증이다.
 *
 * <p>OPL_API의 {@code PayoutValidator}와 같은 자리다. 잔액·한도·중복은 원장이 잠금 안에서
 * 판단하므로 여기서는 보지 않는다. 여기서 막는 것은 **차감 전에 막아야 하는 것**뿐이다.</p>
 */
public final class TransferValidator {

    /** 송금 접수와 잔액 차감 전에 요청 서명을 확인한다. */
    public void isTransferable(WalletContext etx) {

        if (!isChecksumValid(etx.getInput(), etx.getAuthorization(), etx.getWalletId())) {
            throw new WalletException("W001", "송금 요청의 checksum이 일치하지 않습니다.");
        }
    }

    /** 송금 본문의 checksum을 인증된 Wallet과 Authorization 키로 확인한다. */
    private static boolean isChecksumValid(SharedMap<String, Object> inputMap, String authorization, String walletId) {

        String message = String.join("\n", WalletPolicy.TRANSFER_SIGNATURE, walletId, inputMap.getString("trackId"),
                inputMap.getString("bankCd"), inputMap.getString("accntNo"), Long.toString(inputMap.getLong("amount")),
                inputMap.getString("sender"), inputMap.getString("holder"), inputMap.getString("note"));
        String expected = HMAC.hmacSha256(authorization, message);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                inputMap.getString("checksum").getBytes(StandardCharsets.US_ASCII));
    }
}
