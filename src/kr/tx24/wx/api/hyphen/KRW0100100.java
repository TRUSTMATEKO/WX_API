package kr.tx24.wx.api.hyphen;

import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 원화 이체 정보 (KRW0100100)
 */
public class KRW0100100 {

    private String masterAccount = "";      // 1. 출금 계좌번호 (15)
    private String masterPasswd = "";       // 2. 통장 비밀번호 (8)
    private String sign = "";               // 3. 복기부호 (6)
    private String amount = "";             // 4. 출금 금액 (13)
    private String balanceSign = "";        // 5. 출금 후 잔액부호 (1)
    private String balanceAmount = "";      // 6. 출금 후 잔액 (13)
    //private String recvBankCd = "";       // 7. 입금 은행코드2 (2)
    private String recvAccount = "";        // 8. 입금 계좌번호 (15)
    private String fee = "";                // 9. 수수료 (9)
    private String trasferTime = "";        // 10. 이체 시각 (6)
    private String sender = "";             // 11. 입금 계좌 적요 (20) - [기존 receiverName 유지]
    private String cmsCode = "";            // 12. CMS코드 (16)
    private String socialNumber = "";       // 13. 신원확인번호 (13)
    private String ATMCode = "";            // 14. 자동이체 구분 (2) - [기존 ATMCode 유지]
    private String description = "";        // 15. 출금 계좌 적요 (20) - [기존 senderName 유지]
    private String recvBankCd = "";         // 16. 입금 은행코드3 (3) - [기존 receiveNewBankCode 유지]
    private String salaryGubun = "";        // 17. 급여 구분 (1)
    private String feeExemptGubun = "";     // 18. 수수료면제구분 (1)
    private String extra = "";              // 19. 예비 (36)


    public KRW0100100() {
    }

    /**
     * 생성자: Offset 100부터 파싱 시작
     */
    public KRW0100100(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));

        this.masterAccount      = bb.getS(b, 100, 15);
        this.masterPasswd       = bb.getS(b, 115, 8);
        this.sign               = bb.getS(b, 123, 6);
        this.amount             = bb.getS(b, 129, 13);
        this.balanceSign        = bb.getS(b, 142, 1);
        this.balanceAmount      = bb.getS(b, 143, 13);
        //bb.getS(b, 156, 2);
        this.recvAccount        = bb.getS(b, 158, 15);
        this.fee               = bb.getS(b, 173, 9);
        this.trasferTime        = bb.getS(b, 182, 6);
        this.sender             = bb.getS(b, 188, 20);
        this.cmsCode            = bb.getS(b, 208, 16);
        this.socialNumber       = bb.getS(b, 224, 13);
        this.ATMCode            = bb.getS(b, 237, 2);
        this.description        = bb.getS(b, 239, 20);
        this.recvBankCd         = bb.getS(b, 259, 3);
        this.salaryGubun        = bb.getS(b, 262, 1);
        this.feeExemptGubun     = bb.getS(b, 263, 1);
        this.extra              = bb.getS(b, 264, 36);
    
    }

    public byte[] get() {
        
        ByteBufferUtils bb =  new ByteBufferUtils()
            .create(200, Charset.forName("KSC5601"))
            .lpad(masterAccount, 15)
            .lpad(masterPasswd, 8)
            .lpad(sign, 6)
            .zpad(amount, 13)
            .lpad(balanceSign, 1)
            .zpad(balanceAmount, 13)
            .spad(2)
            .lpad(recvAccount, 15)
            .zpad(fee, 9)
            .lpad(trasferTime, 6)
            .lpad(sender, 20)
            .lpad(cmsCode, 16)
            .lpad(socialNumber, 13)
            .lpad(ATMCode, 2);
            

        if(recvBankCd.equals("004")){
            bb.put((byte)14);
            bb.lpad(description,14);
            bb.put((byte)15);
            bb.spad(4);
        }else{
            bb.lpad(description, 20);
        }

        return 
            bb.zpad(recvBankCd, 3)
            .lpad(salaryGubun, 1)
            .lpad(feeExemptGubun, 1)
            .lpad(extra, 36)
            .getArray();
    }

    public String getMasterAccount() { return masterAccount; }
    public KRW0100100 masterAccount(String masterAccount) { this.masterAccount = masterAccount; return this; }

    public String getMasterPasswd() { return masterPasswd; }
    public KRW0100100 masterPasswd(String masterPasswd) { this.masterPasswd = masterPasswd; return this; }

    public String getSign() { return sign; }
    public KRW0100100 sign(String sign) { this.sign = sign; return this; }

    public String getAmount() { return amount; }
    public KRW0100100 amount(String amount) { this.amount = amount; return this; }

    public String getBalanceSign() { return balanceSign; }
    public KRW0100100 balanceSign(String balanceSign) { this.balanceSign = balanceSign; return this; }

    public String getBalanceAmount() { return balanceAmount; }
    public KRW0100100 balanceAmount(String balanceAmount) { this.balanceAmount = balanceAmount; return this; }


    public String getRecvAccount() { return recvAccount; }
    public KRW0100100 recvAccount(String recvAccount) { this.recvAccount = recvAccount; return this; }

    public String getFee() { return fee; }
    public KRW0100100 fee(String fee) { this.fee = fee; return this; }

    public String getTrasferTime() { return trasferTime; }
    public KRW0100100 trasferTime(String trasferTime) { this.trasferTime = trasferTime; return this; }

    public String getDescription() { return description; }
    public KRW0100100 description(String description, String recvBankCd) { this.description = description; this.recvBankCd = recvBankCd; return this; }

    public String getCmsCode() { return cmsCode; }
    public KRW0100100 cmsCode(String cmsCode) { this.cmsCode = cmsCode; return this; }

    public String getSocialNumber() { return socialNumber; }
    public KRW0100100 socialNumber(String socialNumber) { this.socialNumber = socialNumber; return this; }

    public String getATMCode() { return ATMCode; }
    public KRW0100100 ATMCode(String ATMCode) { this.ATMCode = ATMCode; return this; }

    public String getSender() { return sender; }
    public KRW0100100 sender(String sender) { this.sender = sender; return this; }

    public String getRecvBankCd() { return recvBankCd; }
    public KRW0100100 recvBankCd(String recvBankCd) { this.recvBankCd = recvBankCd; return this; }

    public String getExtra() { return extra; }
    public KRW0100100 extra(String extra) { this.extra = extra; return this; }
}