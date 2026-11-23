package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 계좌 조회 , 예금주 조회 
 */
public class KRW0600400 {

    private String transactionDay = ""; // 거래일자    

    private String recvAccount    = ""; // 계좌번호
    private String holder          = ""; // 예금주명
    private String socialNumber   = ""; // 주민번호    
    private String socialCheck    = ""; // 주민체크값  
    private String masterAccount  = ""; // 모계좌번호
    private String recvBankCd     = ""; // 신은행코드
    private String amount         = "";  // 금액
    private String dotComBank     = ""; // 닷컴통장 조회(우리)마이너스 통장여부, 우리은행,케이뱅크
    private String extra          = ""; // 예비/기타   


    public KRW0600400() {
    }

    public KRW0600400(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        this.transactionDay = bb.getS(b, 100, 4);
        //bb.getS(b, 104, 2);
        this.recvAccount    = bb.getS(b, 106, 16);
        this.holder         = bb.getS(b, 122, 22);
        this.socialNumber   = bb.getS(b, 144, 13);
        this.socialCheck    = bb.getS(b, 157, 2);
        this.masterAccount  = bb.getS(b, 159, 20);
        this.recvBankCd     = bb.getS(b, 179, 3);
        this.amount         = bb.getS(b, 182, 13);
        this.dotComBank     = bb.getS(b, 195, 1);
        this.extra          = bb.getS(b, 196, b.length-196);
    }

    public byte[] get() {
        return new ByteBufferUtils()
                .create(200, Charset.forName("KSC5601"))
                .lpad(transactionDay, 4)
                .spad(2)
                .lpad(recvAccount, 16)
                .lpad(holder, 22)
                .lpad(socialNumber, 13)
                .lpad(socialCheck, 2)
                .lpad(masterAccount, 20)
                .lpad(recvBankCd, 3)
                .zpad(amount, 13)
                .lpad(dotComBank, 1)
                .lpad(extra, 104)
                .getArray();
    }

    public String getTransactionDay() { return transactionDay; }
    public KRW0600400 transactionDay(String transactionDay) { this.transactionDay = transactionDay; return this; }


    public String getRecvAccount() { return recvAccount; }
    public KRW0600400 recvAccount(String recvAccount) { this.recvAccount = recvAccount; return this; }

    public String getHolder() { return holder; }
    public KRW0600400 holder(String holder) { this.holder = holder; return this; }

    public String getSocialNumber() { return socialNumber; }
    public KRW0600400 socialNumber(String socialNumber) { this.socialNumber = socialNumber; return this; }

    public String getSocialCheck() { return socialCheck; }
    public KRW0600400 socialCheck(String socialCheck) { this.socialCheck = socialCheck; return this; }

    public String getMasterAccount() { return masterAccount; }
    public KRW0600400 masterAccount(String masterAccount) { this.masterAccount = masterAccount; return this; }

    public String getRecvBankCd() { return recvBankCd; }
    public KRW0600400 recvBankCd(String recvBankCd) { this.recvBankCd = recvBankCd; return this; }

    public String getExtra() { return extra; }
    public KRW0600400 extra(String extra) { this.extra = extra; return this; }
}