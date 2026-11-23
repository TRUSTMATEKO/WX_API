package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

public class KRW0800800 {
    public KRW0800800() {}
    public KRW0800800(byte[] b) {}

    public byte[] get() {
        return new ByteBufferUtils()
            .create(200, Charset.forName("KSC5601"))
            .spad(200)
            .getArray();
    }
}