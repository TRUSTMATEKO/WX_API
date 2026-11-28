package kr.tx24.wx.ledger.utils;

import io.netty.util.NetUtil;

/**
 * 원장이 고객사 허용 IP 규칙을 이름 조회 없이 판정한다.
 *
 * <p>WX_ADM의 {@code ClientIpUtils}에서 주소 변환과 규칙 비교만 가져왔다. 본사 웹 전용인 신뢰 프록시
 * 판정과 관리자 기본 허용망은 원장이 쓰지 않으므로 담지 않는다.</p>
 */
public final class LedgerIp {

    private LedgerIp() {

    }

    /** 문자열 주소를 바이트로 바꾼다. 모양이 아니면 null 이다. */
    public static byte[] address(String text) {

        return text != null && text.matches("[0-9A-Fa-f:.]{2,45}")
                ? NetUtil.createByteArrayFromIpAddressString(text) : null;
    }

    /** 서로 다른 주소 계열이나 잘못된 범위는 일치하지 않는다. */
    public static boolean matches(byte[] address, String value) {

        String[] parts = value.strip().split("/", 2);
        byte[] network = address(parts[0]);
        if (network == null || network.length != address.length) return false;
        int bits = address.length * 8;
        if (parts.length == 2) {
            if (!parts[1].matches("[0-9]{1,3}")) return false;
            bits = Integer.parseInt(parts[1]);
            if (bits > address.length * 8) return false;
        }
        int whole = bits / 8;
        for (int i = 0; i < whole; i++) {
            if (address[i] != network[i]) return false;
        }
        int remainder = bits % 8;
        return remainder == 0 || (((address[whole] ^ network[whole]) & 0xff) >>> (8 - remainder)) == 0;
    }
}
