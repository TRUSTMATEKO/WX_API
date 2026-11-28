package kr.tx24.wx.api.vld;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;

/**
 * 본사 Wallet 이전 요청의 필수값과 모양을 검증한다.
 *
 * <p>OPL_API의 {@code /core/wallet/hq/*} 가 {@code validator.required(etx, ...)} 로 확인하는 것과
 * 같은 자리다. 잔액·중복·Wallet 귀속은 원장이 잠금 안에서 판단하므로 여기서는 보지 않는다.</p>
 */
public final class HqWalletValidator {

    /** 요청번호 상한. 원장의 {@code TRX_WALLET.refId} 가 varchar(40) 이다. */
    private static final int TRACK_MAX = 40;

    /** 이전 한 건의 금액 상한. OPL과 같은 13자리 정수 범위다. */
    private static final long AMOUNT_MAX = 9_999_999_999_999L;

    /**
     * 이전 요청을 검증해 원장에 보낼 입력으로 만든다.
     *
     * <p>허용한 필드만 담는다. 요청에 없는 값을 원장으로 흘려보내지 않는다.</p>
     */
    public SharedMap<String, Object> required(SharedMap<String, Object> inMap) {

        SharedMap<String, Object> inputMap = SharedMap.<String, Object>of()
                .add("trackId", text(inMap, "trackId", TRACK_MAX))
                .add("mchtId", text(inMap, "mchtId", 24))
                .add("walletId", text(inMap, "walletId", 32))
                .add("userId", text(inMap, "userId", 24))
                .add("note", text(inMap, "note", 128));

        long amount = inMap.getLong("amount");
        if (amount < 1 || amount > AMOUNT_MAX) {
            throw new WalletException("W001", "금액은 1원 이상 9,999,999,999,999원 이하 정수로 입력해 주세요.");
        }

        return inputMap.add("amount", amount);
    }

    /** 필수 문자열: 공백·길이·제어문자를 확인하고 원문을 유지한다. */
    private static String text(SharedMap<String, Object> inMap, String field, int max) {

        String value = inMap.getString(field).strip();
        if (value.isEmpty() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new WalletException("W001", field + " 값을 확인해 주세요.");
        }
        return value;
    }
}
