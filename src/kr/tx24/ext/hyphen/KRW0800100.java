package kr.tx24.ext.hyphen;

import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

public class KRW0800100 {
    public KRW0800100() {}
    public KRW0800100(byte[] b) {}

    public byte[] get() {
        return new ByteBufferUtils()
            .create(200, Charset.forName("KSC5601"))
            .spad(200)
            .getArray();
    }
}