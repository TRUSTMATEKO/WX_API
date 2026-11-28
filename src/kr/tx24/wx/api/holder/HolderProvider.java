package kr.tx24.wx.api.holder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import kr.tx24.lib.kms.KMSUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.Acquirer;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;

/** WX.CONFIG의 시스템 계약 한 건으로 수취 계좌의 예금주를 조회한다. */
public interface HolderProvider {

    SharedMap<String, Object> holder(String bank, String account) throws Exception;

    static HolderProvider open(SharedMap<String, Object> configMap, String env) {

        if (configMap == null || !configMap.isEquals("apiEnv", env) || !("test".equals(env) || "prod".equals(env))) {
            throw invalidConfig();
        }
        String  org  = configMap.getString("holderOrgCode");
        boolean test = "test".equals(env);
        if (org.isBlank()) throw invalidConfig();

        return switch (Acquirer.from(configMap.getString("holderAcq"))) {
            case HYPHEN -> {
                // 0600/400 전문의 업체코드 자리는 KSC5601 기준 8바이트다.
                if (!WalletPolicy.isValidAcqSid(org) || org.getBytes(Charset.forName("KSC5601")).length > 8) {
                    throw invalidConfig();
                }
                yield new HyphenHolder(test, org, secret(configMap, "holderKey1Enc"));
            }
            case COOCON -> {
                String bankCd = configMap.getString("holderBankCd");
                if (!bankCd.matches("[0-9]{3}")) throw invalidConfig();
                yield new CooconHolder(test, org, secret(configMap, "holderKey1Enc"), bankCd);
            }
            case DOZN -> {
                boolean seed = switch (configMap.getString("holderSeed")) {
                    case "0", "false" -> false;
                    case "1", "true" -> true;
                    default -> throw invalidConfig();
                };
                DoznSeed cipher = seed ? new DoznSeed(secret(configMap, "holderKey2Enc"),
                        secret(configMap, "holderIvEnc")) : null;
                yield new DoznHolder(test, org, secret(configMap, "holderKey1Enc"), cipher);
            }
            case UNKNOWN -> throw invalidConfig();
        };
    }

    private static String secret(SharedMap<String, Object> configMap, String field) {

        if (configMap.isBlank(field)) throw invalidConfig();
        try {
            byte[] encrypted = Base64.getDecoder().decode(configMap.getString(field));
            byte[] plain     = KMSUtils.decrypt(encrypted);
            if (plain == null || Arrays.equals(encrypted, plain)) throw invalidConfig();
            String key = new String(plain, StandardCharsets.UTF_8);
            if (key.isBlank()) throw invalidConfig();
            return key;
        } catch (IllegalArgumentException invalid) {
            throw invalidConfig();
        }
    }

    static WalletException invalidConfig() {

        return new WalletException("W015", "예금주 조회 기관 설정을 확인해 주세요.");
    }
}
