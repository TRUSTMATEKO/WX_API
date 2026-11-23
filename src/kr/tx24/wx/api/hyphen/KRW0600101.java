package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 원화 이체 결과 조회 
 */
public class KRW0600101 {



    private String rootSpecNumber = ""; // 1. 원거래 전문번호 (6)
    private String masterAccount = "";  // 2. 출금 계좌번호 (15) - mAccount 매핑
    private String recvAccount = "";    // 3. 입금 계좌번호 (15) - receiveAccount 매핑
    private String amount = "";         // 4. 금액 (13)
    private String fee = "";            // 5. 수수료 (9) - commission 매핑
    private String payNumber = "";      // 6. 지급번호 (15)
    private String transferTime = "";   // 7. 이체시각 (6)
    private String resultCd = "";       // 8. 처리결과 (4) - result 매핑
    private String bankId = "";         // 9. 납부자번호 (20)
    private String tradeType = "";      // 11. 거래구분 (2)
    private String recvBankCd = "";       // 12. 은행코드3 (3) - receiveNewBankCode 매핑
    private String zeroPayType = "";    // 13. 제로페이 구분 (2)
    private String extra = "";          // 14. 예비 (88)


    public KRW0600101() {
    }

    /**
     * 생성자: 원본 FB0600100Bean의 Offset에 100을 더하여 파싱
     */
    public KRW0600101(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));

        this.rootSpecNumber  = bb.getS(b, 100, 6);
        this.masterAccount   = bb.getS(b, 106, 15);
        this.recvAccount     = bb.getS(b, 121, 15);
        this.amount          = bb.getS(b, 136, 13);
        this.fee             = bb.getS(b, 149, 9);
        this.payNumber       = bb.getS(b, 158, 15);
        this.transferTime    = bb.getS(b, 173, 6);
        this.resultCd        = bb.getS(b, 179, 4);
        this.bankId          = bb.getS(b, 185, 20);
        this.tradeType       = bb.getS(b, 205, 2);
        this.recvBankCd      = bb.getS(b, 207, 3);
        this.zeroPayType     = bb.getS(b, 210, 2);
        this.extra           = bb.getS(b, 212, b.length - 212);
    }

    public byte[] get() {
        return new ByteBufferUtils()
                .create(200, Charset.forName("KSC5601"))
                .lpad(rootSpecNumber, 6)
                .lpad(masterAccount, 15)
                .lpad(recvAccount, 15)
                .zpad(amount, 13)
                .zpad(fee, 9)
                .lpad(payNumber, 15)
                .lpad(transferTime, 6)
                .lpad(resultCd, 4)
                .spad(2)
                .lpad(bankId, 20)
                .lpad(tradeType, 2)
                .lpad(recvBankCd, 3)
                .lpad(zeroPayType, 2)
                .lpad(extra, 88)
                .getArray();
    }


    public String getRootSpecNumber() { return rootSpecNumber; }
    public KRW0600101 rootSpecNumber(String rootSpecNumber) { this.rootSpecNumber = rootSpecNumber; return this; }

    public String getMasterAccount() { return masterAccount; }
    public KRW0600101 masterAccount(String masterAccount) { this.masterAccount = masterAccount; return this; }

    public String getRecvAccount() { return recvAccount; }
    public KRW0600101 recvAccount(String recvAccount) { this.recvAccount = recvAccount; return this; }

    public String getAmount() { return amount; }
    public KRW0600101 amount(String amount) { this.amount = amount; return this; }

    public String getFee() { return fee; }
    public KRW0600101 fee(String fee) { this.fee = fee; return this; }

    public String getPayNumber() { return payNumber; }
    public KRW0600101 payNumber(String payNumber) { this.payNumber = payNumber; return this; }

    public String getTransferTime() { return transferTime; }
    public KRW0600101 transferTime(String transferTime) { this.transferTime = transferTime; return this; }

    public String getResultCd() { return resultCd; }
    public KRW0600101 resultCd(String resultCd) { this.resultCd = resultCd; return this; }

    public String getBankId() { return bankId; }
    public KRW0600101 bankId(String bankId) { this.bankId = bankId; return this; }

    public String getTradeType() { return tradeType; }
    public KRW0600101 tradeType(String tradeType) { this.tradeType = tradeType; return this; }

    public String getRecvBankCd() { return recvBankCd; }
    public KRW0600101 recvBankCd(String recvBankCd) { this.recvBankCd = recvBankCd; return this; }

    public String getZeroPayType() { return zeroPayType; }
    public KRW0600101 zeroPayType(String zeroPayType) { this.zeroPayType = zeroPayType; return this; }

    public String getExtra() { return extra; }
    public KRW0600101 extra(String extra) { this.extra = extra; return this; }

    
}