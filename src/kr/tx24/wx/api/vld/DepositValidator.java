package kr.tx24.wx.api.vld;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;

/**
 * 외부 입금 통지의 입력을 검증한다.
 *
 * <p>통지를 보내는 쪽은 SMS 중계와 기관 가상계좌 통지다. 보내는 값은 네 개다.</p>
 *
 * <ul>
 *   <li>가상계좌 — {@code vaAccntNo}, {@code occurredAt}, {@code depositName}, {@code amount}</li>
 *   <li>SMS — {@code hqAccntNo}, {@code depositName}, {@code occurredAt}, {@code amount}</li>
 * </ul>
 *
 * <p>모계좌와 Wallet 은 WX 가 그 값으로 찾으므로 통지하는 쪽이 WX 내부 식별자를 알 필요가 없다.
 * 어느 경로인지도 계좌 항목으로 가린다. 두 계좌 항목을 함께 보내거나 둘 다 비우면 받지 않는다.</p>
 *
 * <p>여기서는 자료형과 길이만 본다. 모양과 업무 판단은 원장이 잠금 안에서 다시 확인한다.</p>
 */
public final class DepositValidator {

    /** 입금 통지 한 건을 검증한다. */
    public SharedMap<String, Object> receive(SharedMap<String, Object> inMap) {

        long amount = inMap.getLong("amount");
        if (amount <= 0) throw new WalletException("W001", "입금액을 확인해 주세요.");

        String virtual = optional(inMap, "vaAccntNo", 32);
        String master  = optional(inMap, "hqAccntNo", 32);
        if (virtual.isEmpty() == master.isEmpty()) {
            throw new WalletException("W001", "가상계좌번호 또는 모계좌번호 하나만 보내 주세요.");
        }

        String noticeSeq = optional(inMap, "noticeSeqNo", 6);
        String bankTrxNo = optional(inMap, "bankTrxNo", 6);
        if (!noticeSeq.isEmpty() && (virtual.isEmpty() || !noticeSeq.matches("[0-9]{6}")
                || !bankTrxNo.matches("[0-9]{6}"))) {
            throw new WalletException("W001", "가상계좌 통지의 기관 거래번호를 확인해 주세요.");
        }
        return SharedMap.<String, Object>of()
                .add("noticeSeqNo", noticeSeq)
                .add("bankTrxNo", bankTrxNo)
                .add("vaAccntNo", virtual)
                .add("hqAccntNo", master)
                .add("depositName", text(inMap, "depositName", 128))
                .add("occurredAt", text(inMap, "occurredAt", 14))
                .add("amount", amount)
                // 같은 번호의 모계좌가 둘 이상일 때만 필요하다. 통지가 알려 주면 함께 쓴다.
                .add("bankCd", optional(inMap, "bankCd", 12))
                // 통지 원문. 있으면 암호문으로 보관한다. 줄바꿈을 허용한다.
                .add("payload", raw(inMap, "payload", 4096));
    }

    /** 타행이체불능명세에서 원거래를 특정할 항목과 매입사 실패 사유를 확인한다. */
    public SharedMap<String, Object> transferFailure(SharedMap<String, Object> inMap) {

        String day     = inMap.getString("rootTrxDay", inMap.getString("trxDay"));
        if ("00000000".equals(day)) day = inMap.getString("trxDay");
        String seq     = inMap.getString("seqNo");
        String parent  = inMap.getString("parentAccount");
        String account = inMap.getString("recvAccount");
        String bank    = inMap.getString("bankCd");
        String recv    = inMap.getString("recvBankCd");
        String code    = inMap.getString("acqResultCd");
        if (!day.matches("[0-9]{8}") || !seq.matches("[0-9]{6}") || !parent.matches("[0-9]{1,15}")
                || !account.matches("[0-9]{1,15}") || !bank.matches("[0-9]{3}") || !recv.matches("[0-9]{3}")
                || !code.matches("[A-Za-z0-9]{3}") || code.equals("000")
                || !inMap.getString("amount").matches("[0-9]{1,13}") || inMap.getLong("amount") <= 0) {
            throw new WalletException("W001", "타행이체불능명세의 원거래 식별값과 실패코드를 확인해 주세요.");
        }
        try {
            java.time.LocalDate.parse(day, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (java.time.DateTimeException invalid) {
            throw new WalletException("W001", "원거래 일자를 확인해 주세요.");
        }
        String message = optional(inMap, "acqResultMsg", 128);
        if (message.isEmpty()) message = kr.tx24.wx.api.utils.ExtCode.getKTFC(code);
        return SharedMap.<String, Object>of()
                .add("acqTrxId", day.substring(2) + seq)
                .add("parentAccount", parent)
                .add("recvAccount", account)
                .add("bankCd", bank)
                .add("recvBankCd", recv)
                .add("amount", inMap.getLong("amount"))
                .add("acqResultCd", code)
                .add("acqResultMsg", message);
    }

    /** 필수 문자열: 공백·길이·제어문자를 확인하고 원문을 유지한다. */
    private static String text(SharedMap<String, Object> inMap, String field, int max) {

        String value = inMap.getString(field).strip();
        if (value.isEmpty() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new WalletException("W001", field + " 값을 확인해 주세요.");
        }
        return value;
    }

    /** 선택 문자열: 비어 있어도 되지만 길이와 제어문자는 같은 기준으로 본다. */
    private static String optional(SharedMap<String, Object> inMap, String field, int max) {

        String value = inMap.getString(field).strip();
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new WalletException("W001", field + " 값을 확인해 주세요.");
        }
        return value;
    }

    /** 통지 원문: 줄바꿈을 허용하고 길이와 널 문자만 본다. */
    private static String raw(SharedMap<String, Object> inMap, String field, int max) {

        String value = inMap.getString(field);
        if (value.length() > max || value.indexOf('\0') >= 0) {
            throw new WalletException("W001", field + " 값을 확인해 주세요.");
        }
        return value;
    }
}
