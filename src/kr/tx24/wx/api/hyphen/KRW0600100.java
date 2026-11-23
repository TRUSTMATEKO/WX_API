package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 원화 이체 결과 조회 (KRW0600100) FB0600100Bean 규격 반영 및 Header(100) 이후 파싱 적용
 */
public class KRW0600100 {

    private String rootSpecNumber = ""; // 원거래전문번호 (6)
    private String mAccount = ""; // 모계좌번호 (15)
    private String receiveAccount = ""; // 입금계좌번호 (15)
    private String amount = ""; // 금액 (13)
    private String commission = ""; // 수수료 (9)
    private String payNumber = ""; // 납부번호 (15)
    private String trasferTime = ""; // 전송시간 (6)
    private String result = ""; // 결과 (4)
    private String receiveBankCode = ""; // 입금은행코드 (2)
    private String extra = ""; // 예비 (2)
    private String receiveNewBankCode = ""; // 입금은행코드(신규) (3)
    private String extraFiller = ""; // 예비필러 (110)

    public KRW0600100() {
    }

    /**
     * 생성자: 원본 FB0600100Bean의 Offset에 100을 더하여 파싱
     */
    public KRW0600100(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));

        this.rootSpecNumber = bb.getS(b, 100, 6); // 0 + 100
        this.mAccount = bb.getS(b, 106, 15); // 6 + 100
        this.receiveAccount = bb.getS(b, 121, 15); // 21 + 100
        this.amount = bb.getS(b, 136, 13); // 36 + 100
        this.commission = bb.getS(b, 149, 9); // 49 + 100
        this.payNumber = bb.getS(b, 158, 15); // 58 + 100
        this.trasferTime = bb.getS(b, 173, 6); // 73 + 100
        this.result = bb.getS(b, 179, 4); // 79 + 100
        this.receiveBankCode = bb.getS(b, 183, 2); // 83 + 100
        this.extra = bb.getS(b, 185, 2); // 85 + 100
        this.receiveNewBankCode = bb.getS(b, 187, 3); // 87 + 100

        if (b.length >= 200) {
            this.extraFiller = bb.getS(b, 190, 110); // 90 + 100
        }
    }

    /**
     * 전문 생성: 전체 200바이트 고정 규격 적용
     */
    public byte[] get() {
        return new ByteBufferUtils().create(200, Charset.forName("KSC5601")).zpad(rootSpecNumber, 6).zpad(mAccount, 15)
                .zpad(receiveAccount, 15).zpad(amount, 13).zpad(commission, 9).zpad(payNumber, 15).zpad(trasferTime, 6)
                .lpad(result, 4).lpad(receiveBankCode, 2).lpad(extra, 2).lpad(receiveNewBankCode, 3)
                .lpad(extraFiller, 110) // transaction.append(CommonUtil.byteFiller(this.extra, 110)) 반영
                .getArray();
    }

    // --- Fluent Setters & Getters ---

    public String getRootSpecNumber() {
        return rootSpecNumber;
    }

    public KRW0600100 rootSpecNumber(String rootSpecNumber) {
        this.rootSpecNumber = rootSpecNumber;
        return this;
    }

    public String getMAccount() {
        return mAccount;
    }

    public KRW0600100 mAccount(String mAccount) {
        this.mAccount = mAccount;
        return this;
    }

    public String getReceiveAccount() {
        return receiveAccount;
    }

    public KRW0600100 receiveAccount(String receiveAccount) {
        this.receiveAccount = receiveAccount;
        return this;
    }

    public String getAmount() {
        return amount;
    }

    public KRW0600100 amount(String amount) {
        this.amount = amount;
        return this;
    }

    public String getCommission() {
        return commission;
    }

    public KRW0600100 commission(String commission) {
        this.commission = commission;
        return this;
    }

    public String getPayNumber() {
        return payNumber;
    }

    public KRW0600100 payNumber(String payNumber) {
        this.payNumber = payNumber;
        return this;
    }

    public String getTrasferTime() {
        return trasferTime;
    }

    public KRW0600100 trasferTime(String trasferTime) {
        this.trasferTime = trasferTime;
        return this;
    }

    public String getResult() {
        return result;
    }

    public KRW0600100 result(String result) {
        this.result = result;
        return this;
    }

    public String getReceiveBankCode() {
        return receiveBankCode;
    }

    public KRW0600100 receiveBankCode(String receiveBankCode) {
        this.receiveBankCode = receiveBankCode;
        return this;
    }

    public String getExtra() {
        return extra;
    }

    public KRW0600100 extra(String extra) {
        this.extra = extra;
        return this;
    }

    public String getReceiveNewBankCode() {
        return receiveNewBankCode;
    }

    public KRW0600100 receiveNewBankCode(String receiveNewBankCode) {
        this.receiveNewBankCode = receiveNewBankCode;
        return this;
    }

    public String getExtraFiller() {
        return extraFiller;
    }

    public KRW0600100 extraFiller(String extraFiller) {
        this.extraFiller = extraFiller;
        return this;
    }
}