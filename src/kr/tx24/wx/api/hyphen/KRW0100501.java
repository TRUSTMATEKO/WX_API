package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;
import java.nio.charset.Charset;

/**
 * 출금이체, 집금이체 
 */
public class KRW0100501 {

    private String account = "";           // 1. 출금 계좌번호 (15)
    private String passwd = "";            // 2. 통장 비밀번호 (8)
    private String sign = "";              // 3. 복기부호 (6)
    private String amount = "";            // 4. 입금 금액 (13)
    private String balanceSign = "";       // 5. 입금 후 잔액부호 (1)
    private String balance = "";           // 6. 입금 후 잔액 (13)
    private String compAccount = "";       // 8. 입금 계좌번호 (15) - [7번 은행코드 2바이트 건너뜀]
    private String fee = "";               // 9. 수수료 (9)
    private String transferTime = "";      // 10. 이체시각 (6)
    private String collectName = "";       // 11. 출금 통장 적요 (20)
    private String idenDigit = "";         // 12. 신원확인번호 (13)
    private String transferType = "";      // 13. 자동이체구분 (2)
    private String outletName = "";        // 14. 입금 통장 적요 (20)
    private String bankId = "";            // 15. 납부자번호 (20)
    private String collectCd = "";         // 16. 기관코드 (10)
    private String bankCode = "";          // 17. 출금 은행코드3 (3)
    private String extra = "";             // 18. 제로페이 구분(2) + 19. 예비(22) = (24)

    public KRW0100501() {
    }


    public KRW0100501(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));

        this.account      = bb.getS(b, 100, 15);
        this.passwd       = bb.getS(b, 115, 8);
        this.sign         = bb.getS(b, 123, 6);
        this.amount       = bb.getS(b, 129, 13);
        this.balanceSign  = bb.getS(b, 142, 1);
        this.balance      = bb.getS(b, 143, 13);
        //출금은행코드 2자리는 사용하지 않음.
        this.compAccount  = bb.getS(b, 158, 15);
        this.fee          = bb.getS(b, 173, 9);
        this.transferTime = bb.getS(b, 182, 6);
        this.collectName  = bb.getS(b, 188, 20);
        this.idenDigit    = bb.getS(b, 208, 13);
        this.transferType = bb.getS(b, 221, 2);
        this.outletName   = bb.getS(b, 223, 20);
        this.bankId       = bb.getS(b, 243, 20); 
        this.collectCd    = bb.getS(b, 263, 10);
        this.bankCode     = bb.getS(b, 273, 3); 
        this.extra        = bb.getS(b, 276, 24); 
        
    }

    public byte[] get() {
        return new ByteBufferUtils()
            .create(200, Charset.forName("KSC5601"))
            .lpad(account, 15)
            .lpad(passwd, 8)
            .lpad(sign, 6)
            .zpad(amount, 13)
            .lpad(balanceSign, 1)
            .zpad(balance, 13)
            .spad(2)      // 7. 출금 은행코드2 자리 공백
            .lpad(compAccount, 15)
            .zpad(fee, 9)
            .lpad(transferTime, 6)
            .lpad(collectName, 20)
            .lpad(idenDigit, 13)
            .lpad(transferType, 2)
            .lpad(outletName, 20)
            .lpad(bankId, 20)
            .lpad(collectCd, 10)
            .zpad(bankCode, 3)
            .lpad(extra, 24)
            .getArray();
    }


    public String getAccount() { return account; }
    public KRW0100501 account(String account) { this.account = account; return this; }

    public String getPasswd() { return passwd; }
    public KRW0100501 passwd(String passwd) { this.passwd = passwd; return this; }

    public String getSign() { return sign; }
    public KRW0100501 sign(String sign) { this.sign = sign; return this; }

    public String getAmount() { return amount; }
    public KRW0100501 amount(String amount) { this.amount = amount; return this; }

    public String getBalanceSign() { return balanceSign; }
    public KRW0100501 balanceSign(String balanceSign) { this.balanceSign = balanceSign; return this; }

    public String getBalance() { return balance; }
    public KRW0100501 balance(String balance) { this.balance = balance; return this; }

    public String getCompAccount() { return compAccount; }
    public KRW0100501 compAccount(String compAccount) { this.compAccount = compAccount; return this; }

    public String getFee() { return fee; }
    public KRW0100501 fee(String fee) { this.fee = fee; return this; }

    public String getTransferTime() { return transferTime; }
    public KRW0100501 transferTime(String transferTime) { this.transferTime = transferTime; return this; }

    public String getCollectName() { return collectName; }
    public KRW0100501 collectName(String collectName) { this.collectName = collectName; return this; }

    public String getIdenDigit() { return idenDigit; }
    public KRW0100501 idenDigit(String idenDigit) { this.idenDigit = idenDigit; return this; }

    public String getTransferType() { return transferType; }
    public KRW0100501 transferType(String transferType) { this.transferType = transferType; return this; }

    public String getOutletName() { return outletName; }
    public KRW0100501 outletName(String outletName) { this.outletName = outletName; return this; }

    public String getBankId() { return bankId; }
    public KRW0100501 bankId(String bankId) { this.bankId = bankId; return this; }

    public String getCollectCd() { return collectCd; }
    public KRW0100501 collectCd(String collectCd) { this.collectCd = collectCd; return this; }

    public String getBankCode() { return bankCode; }
    public KRW0100501 bankCode(String bankCode) { this.bankCode = bankCode; return this; }

    public String getExtra() { return extra; }
    public KRW0100501 extra(String extra) { this.extra = extra; return this; }
}