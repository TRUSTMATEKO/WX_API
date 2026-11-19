package kr.tx24.ext.hyphen;

import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 11)	잔액 조회 
 */
public class KRW0600300 {
    private String masterAccount = "";        // 계좌번호 (15)
    private String sign = "";           // 부호 (1)
    private String currentAmount = "";  // 현재잔액 (13)
    private String cashierCheck = "";   // 자기앞수표 (13)
    private String personalCheck = "";  // 가계수표 (13)
    private String billCheck = "";      // 타행권수표 (13)
    private String payAmount = "";      // 지불가능잔액 (13)
    private String extra = "";          // 예비 (119)

    public KRW0600300() {
    }

    public KRW0600300(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        this.masterAccount  = bb.getS(b, 100, 15);
        this.sign           = bb.getS(b, 115, 1);
        this.currentAmount  = bb.getS(b, 116, 13);
        this.cashierCheck   = bb.getS(b, 129, 13);
        this.personalCheck  = bb.getS(b, 142, 13);
        this.billCheck      = bb.getS(b, 155, 13);
        this.payAmount      = bb.getS(b, 168, 13);
        this.extra          = bb.getS(b, 181, 119);
    }

    public byte[] get() {
        return new ByteBufferUtils()
                .create(200, Charset.forName("KSC5601"))
                .lpad(masterAccount, 15)
                .lpad(sign, 1)
                .zpad(currentAmount, 13)
                .zpad(cashierCheck, 13)
                .zpad(personalCheck, 13)
                .zpad(billCheck, 13)
                .zpad(payAmount, 13)
                .lpad(extra, 119)
                .getArray();
    }

    public String getMasterAccount() { return masterAccount; }
    public KRW0600300 masterAccount(String masterAccount) { this.masterAccount = masterAccount; return this; }

    public String getSign() { return sign; }
    public KRW0600300 sign(String sign) { this.sign = sign; return this; }

    public String getCurrentAmount() { return currentAmount; }
    public KRW0600300 currentAmount(String currentAmount) { this.currentAmount = currentAmount; return this; }

    public String getCashierCheck() { return cashierCheck; }
    public KRW0600300 cashierCheck(String cashierCheck) { this.cashierCheck = cashierCheck; return this; }

    public String getPersonalCheck() { return personalCheck; }
    public KRW0600300 personalCheck(String personalCheck) { this.personalCheck = personalCheck; return this; }

    public String getBillCheck() { return billCheck; }
    public KRW0600300 billCheck(String billCheck) { this.billCheck = billCheck; return this; }

    public String getPayAmount() { return payAmount; }
    public KRW0600300 payAmount(String payAmount) { this.payAmount = payAmount; return this; }

    public String getExtra() { return extra; }
    public KRW0600300 extra(String extra) { this.extra = extra; return this; }
}