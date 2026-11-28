package kr.tx24.wx.api.holder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import kr.tx24.lib.cipher.Seed;

/** DOZN의 SEED-CBC와 PKCS 패딩, Base64 전문을 처리한다. */
final class DoznSeed {

    private static final int BLOCK = 16;
    private final int[] roundKey = new int[32];
    private final byte[] iv;

    DoznSeed(String key, String initialVector) {

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        iv = initialVector.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != BLOCK || iv.length != BLOCK) throw HolderProvider.invalidConfig();
        Seed.SeedRoundKey(roundKey, keyBytes);
    }

    String encrypt(String text) {

        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        int padding  = BLOCK - plain.length % BLOCK;
        byte[] data  = Arrays.copyOf(plain, plain.length + padding);
        Arrays.fill(data, plain.length, data.length, (byte) padding);
        byte[] previous = iv;
        for (int offset = 0; offset < data.length; offset += BLOCK) {
            byte[] block = Arrays.copyOfRange(data, offset, offset + BLOCK);
            for (int i = 0; i < BLOCK; i++) block[i] ^= previous[i];
            previous = new byte[BLOCK];
            Seed.SeedEncrypt(block, roundKey, previous);
            System.arraycopy(previous, 0, data, offset, BLOCK);
        }
        return Base64.getEncoder().encodeToString(data);
    }

    String decrypt(String text) throws IOException {

        byte[] data = Base64.getDecoder().decode(text);
        if (data.length == 0 || data.length % BLOCK != 0) throw new IOException("DOZN 암호문 길이 오류");
        byte[] previous = iv;
        for (int offset = 0; offset < data.length; offset += BLOCK) {
            byte[] block = Arrays.copyOfRange(data, offset, offset + BLOCK);
            byte[] plain = new byte[BLOCK];
            Seed.SeedDecrypt(block, roundKey, plain);
            for (int i = 0; i < BLOCK; i++) data[offset + i] = (byte) (plain[i] ^ previous[i]);
            previous = block;
        }
        int padding = data[data.length - 1] & 255;
        if (padding < 1 || padding > BLOCK) throw new IOException("DOZN 암호문 패딩 오류");
        for (int i = data.length - padding; i < data.length; i++) {
            if (data[i] != padding) throw new IOException("DOZN 암호문 패딩 오류");
        }
        return new String(data, 0, data.length - padding, StandardCharsets.UTF_8);
    }
}
