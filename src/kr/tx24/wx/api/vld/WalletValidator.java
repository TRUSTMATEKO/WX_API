package kr.tx24.wx.api.vld;

import java.util.Iterator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletZone;
import kr.tx24.wx.api.ctx.WalletContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Wallet 요청의 형식을 검증한다. 업무 규칙은 보지 않는다.
 *
 * <p>OPL_API의 {@code EwalletValidator}와 같은 자리다. 인증 키 형식, Content-Type,
 * JSON 필드와 자료형까지만 확인하고 잔액·한도 같은 업무 판단은 원장이 한다.</p>
 */
public final class WalletValidator {

    private static final ObjectMapper READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * 고객사 요청의 접속 IP를 인증 전에 차단 목록과 대조한다.
     *
     * <p>여기서 막힌 요청은 원장 호출도, API 요청 이력도 남기지 않는다. 차단 사유는
     * 응답에 드러나지 않고 인증·접속 IP 실패와 같은 코드로만 나간다.</p>
     */
    public void zone(WalletContext etx) {

        WalletZone.verify(etx.getClientIp());
    }

    /** Authorization은 Bearer 없이 전달된 Wallet 키 한 개만 사용한다. */
    public void walletKey(WalletContext etx) {

        String value = etx.getAuthorization();
        boolean valid = value != null && value.startsWith("wk_") && value.length() > 3 && value.length() <= 256
                && value.chars().allMatch(c -> c >= 33 && c <= 126 && c != ',');
        if (!valid) throw new WalletException("W002", "Wallet 인증 실패");
    }

    /** 요청 본문의 Content-Type과 JSON 필드를 확인해 컨텍스트에 적재한다. */
    public void required(WalletContext etx) {

        String contentType = etx.getContentType();
        if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            throw new WalletException("W001", "application/json으로 요청해 주세요.");
        }
        try {
            SharedMap<String, Object> inputMap = payload(etx.getAction(), etx.getBody());
            if (!inputMap.isBlank("trackId") && etx.getIo() != null) {
                etx.getIo().put("trackId", inputMap.getString("trackId"));
            }
            etx.setInput(inputMap);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new WalletException("W001", "요청 JSON의 필드와 형식을 확인해 주세요.", invalid);
        }
    }

    /** Wallet 네 경로의 JSON 필드를 검증한다. 운영 원장 계약 하나만 쓴다. */
    public static SharedMap<String, Object> payload(String action, byte[] bytes) throws IOException {

        JsonNode payload = READER.readTree(bytes);
        if (payload == null || !payload.isObject()) throw new IllegalArgumentException();

        if (action.equals("balance")) return SharedMap.of();

        // 거래조회는 두 가지 중 하나를 받는다. WX 거래번호 하나로 찾거나, 고객사 주문번호와 거래일로 찾는다.
        if (action.equals("inquiry")) return inquiry(payload);

        if (!Set.of("payout", "holder").contains(action)) throw new IllegalArgumentException();

        String track   = requiredText(payload, "trackId", 64);
        String bankCd  = requiredText(payload, "bankCd", 3);
        String accntNo = requiredText(payload, "accntNo", 20);
        if (!bankCd.matches("[0-9]{3}") || !accntNo.matches("[0-9]+")) {
            throw new IllegalArgumentException();
        }

        // 수취 계좌는 송금과 예금주 조회 모두가 쓴다. 전에는 송금 분기에서만 담아 예금주 조회가
        // 은행코드와 계좌번호 없이 원장에 닿아 항상 W001 로 거절됐다.
        SharedMap<String, Object> inputMap = SharedMap.<String, Object>of()
                .add("trackId", track)
                .add("bankCd", bankCd)
                .add("accntNo", accntNo);

        if (action.equals("payout")) {
            Iterator<String> fields = payload.fieldNames();
            while (fields.hasNext()) {
                if (!WalletPolicy.TRANSFER_FIELDS.contains(fields.next())) throw new IllegalArgumentException();
            }

            JsonNode amount = payload.path("amount");
            if (!amount.isIntegralNumber() || !amount.canConvertToLong()
                    || amount.longValue() < 1 || amount.longValue() > WalletPolicy.AMOUNT_MAX) {
                throw new IllegalArgumentException();
            }

            String checksum = requiredText(payload, "checksum", 64);
            if (!checksum.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();

            return inputMap
                    .add("amount", amount.longValue())
                    .add("sender", signingText(payload, "sender", 20))
                    .add("holder", signingText(payload, "holder", 64))
                    .add("note", signingText(payload, "note", 128))
                    .add("checksum", checksum);
        }

        return inputMap;
    }

    /**
     * 거래조회 입력. {@code trxId} 하나이거나 {@code trackId}와 {@code trxDay} 한 쌍이어야 한다.
     *
     * <p>둘 다 주면 {@code trxId}를 쓴다. WX가 발급한 거래번호가 더 정확하기 때문이다.
     * 섞어서 일부만 주면 받지 않는다. 어느 쪽으로 찾을지 분명해야 한다.</p>
     */
    private static SharedMap<String, Object> inquiry(JsonNode payload) {

        String trxId = payload.hasNonNull("trxId") ? requiredText(payload, "trxId", 32) : "";
        if (!trxId.isEmpty()) {
            return SharedMap.<String, Object>of()
                    .add("trxId", trxId);
        }

        String track = requiredText(payload, "trackId", 64);
        String day   = requiredText(payload, "trxDay", 8);
        if (!day.matches("[0-9]{8}")) throw new IllegalArgumentException();
        try {
            if (LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE).getYear() < 1) {
                throw new IllegalArgumentException();
            }
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException();
        }

        return SharedMap.<String, Object>of()
                .add("trackId", track)
                .add("trxDay", day);
    }

    /**
     * 송금 선택값은 원문으로 서명하고 구분자 충돌이나 잘못된 유니코드가 있는 입력은 거절한다.
     *
     * <p>길이도 여기서 막는다. 전에는 상한이 없어 20자를 넘는 {@code sender}가 서명을 통과한 뒤
     * 원장에서야 거절됐다. 상한은 공개 문서와 저장 컴럼 길이를 따른다.</p>
     */
    private static String signingText(JsonNode payload, String field, int maxLength) {

        JsonNode node = payload.path(field);
        if (node.isMissingNode()) return "";
        if (!node.isTextual()) throw new IllegalArgumentException();

        String value = node.textValue();
        if (value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    /** Wallet 요청 필드의 자료형과 길이를 확인하고 원문 값을 유지한다. */
    private static String requiredText(JsonNode payload, String field, int maxLength) {

        JsonNode node = payload.path(field);
        if (!node.isTextual()) throw new IllegalArgumentException();
        String value = node.textValue();
        if (value.isBlank() || value.length() > maxLength || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}
