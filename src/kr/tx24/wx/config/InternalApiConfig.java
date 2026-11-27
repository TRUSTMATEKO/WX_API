package kr.tx24.wx.config;

import kr.tx24.lib.db.RecordSet;
import kr.tx24.lib.db.Retrieve;
import kr.tx24.lib.kms.KMSUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisTextUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** WX.CONFIG가 원본이며 Redis에는 변경 버전만 둔다. 서비스마다 사용할 열만 읽는다. */
public final class InternalApiConfig {

    public static final String VERSION_KEY = "WX:CONFIG:API:VERSION";

    private final String columns;
    private SharedMap<String, Object> snapshot;
    private long nextCheck;
    private long nextReload;

    public InternalApiConfig(boolean receiver) {

        columns = receiver ? "apiConfigVersion,apiKeyEnc,apiPreviousKeyEnc,apiGatewayIps,apiInternalIps"
                : "apiConfigVersion,apiKeyEnc";
    }

    /** 변경 통지는 1초 단위로 확인하며 Redis 재시작이나 통지 누락은 DB 재조회로 복구한다. */
    public synchronized SharedMap<String, Object> read() {

        long now = System.nanoTime();
        if (snapshot != null && now - nextCheck < 0) return snapshot.copy();
        if (snapshot != null && now - nextReload < 0) {
            String version;
            try {
                version = RedisTextUtils.get(VERSION_KEY);
            } catch (RuntimeException unavailable) {
                version = null;
            }
            if (snapshot.getString("apiConfigVersion").equals(version)) {
                nextCheck = now + TimeUnit.SECONDS.toNanos(1);
                return snapshot.copy();
            }
        }
        return reload();
    }

    /** 버전 변경 또는 새 키 확인 시 DB에서 현재 설정을 읽는다. */
    public synchronized SharedMap<String, Object> reload() {

        long now = System.nanoTime();
        RecordSet records = new Retrieve("WX.CONFIG").columns(columns).where("id", 1).select();
        SharedMap<String, Object> row = records == null ? null : records.getRow(0);
        if (row == null || row.getLong("apiConfigVersion") < 1) {
            throw new IllegalStateException("WX API 연동 설정을 확인해 주세요.");
        }
        try {
            RedisTextUtils.setNx(VERSION_KEY, row.getString("apiConfigVersion"));
        } catch (RuntimeException unavailable) {
            // 다음 확인에서 DB를 사용한다.
        }
        snapshot   = row;
        nextCheck  = now + TimeUnit.SECONDS.toNanos(1);
        nextReload = now + TimeUnit.SECONDS.toNanos(30);
        return row.copy();
    }

    /** KMS가 원문을 반환한 경우 저장하지 않는다. */
    public static String encryptKey(String key) {

        if (!key.matches("[!-~]{32,128}")) throw new IllegalArgumentException("인증키 형식을 확인해 주세요.");
        byte[] plain = key.getBytes(StandardCharsets.US_ASCII);
        byte[] encrypted = KMSUtils.encrypt(plain);
        if (encrypted == null || Arrays.equals(plain, encrypted)) throw new IllegalStateException("인증키 암호화 실패");
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /** KMS가 오류 시 입력을 반환해도 그 값을 내부 인증키로 사용하지 않는다. */
    public static String key(SharedMap<String, Object> row, String column) {

        String value = row.getString(column);
        if (value.isBlank()) return "";
        byte[] encrypted = Base64.getDecoder().decode(value);
        byte[] plain = KMSUtils.decrypt(encrypted);
        if (plain == null || Arrays.equals(plain, encrypted)) throw new IllegalStateException("인증키 복호화 실패");
        String key = new String(plain, StandardCharsets.US_ASCII);
        if (!key.matches("[!-~]{32,128}")) throw new IllegalStateException("인증키 복호화 실패");
        return key;
    }
}
