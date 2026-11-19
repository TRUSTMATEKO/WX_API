package kr.tx24.ext.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 입금 20  입금취소 51
    출금 30, 32  출금취소 52
    부도지급 31  부도입금취소 53
    입금이자 40  부도지급취소 54
    출금이자 41  수탁만기입금 21, 60
    기타 99      
 */
public class KRW0200300 {

    private String parentAccount   = "";   //계좌번호
    private String count           = "";   //조립건수
    private String trxType         = "";   //거래구분
    private String amount          = "";    //금액
    private String balance         = "";    //잔액
    private String giroCd          = "";
    private String sender          = "";   //성명
    private String checkNo         = "";   //수표번호
    private String cash            = "";    //현금(현금+당좌수표)
    private String otbCheckAmount  = "";    //타행수표금액
    private String otbCheck        = "";    //가계수표,기타
    private String account         = "";   //가상계좌번호
    private String trxDay          = "";   //거래일자
    private String trxTime          = "";   //거래일자
    private String bankTrxNo       = "";   //통장거래 일련번호
    private String bankCd          = "";   //은행코드 3자리
    private String branch          = "";   //거래지점코드 
    private String filler          = "";   //예비 

    public KRW0200300() {
    }


    public KRW0200300(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        this.parentAccount   = bb.getS(b, 100, 15);
        this.count           = bb.getS(b, 115, 2);
        this.trxType         = bb.getS(b, 117, 2);
        this.amount          = bb.getS(b, 121, 13);
        this.balance         = bb.getS(b, 134, 13);
        this.giroCd          = bb.getS(b, 147, 6);
        this.sender          = bb.getS(b, 153, 14);
        this.checkNo         = bb.getS(b, 167, 10);
        this.cash            = bb.getS(b, 177, 13);
        this.otbCheckAmount  = bb.getS(b, 190, 13);
        this.otbCheck        = bb.getS(b, 203, 13);
        this.account         = bb.getS(b, 216, 16);
        this.trxDay          = bb.getS(b, 232, 8);
        this.trxTime         = bb.getS(b, 240, 6);
        this.bankTrxNo       = bb.getS(b, 246, 6);
        this.bankCd          = bb.getS(b, 252, 3);
        this.branch          = bb.getS(b, 255, 7);
        this.filler          = bb.getS(b, 262, b.length - 262);
    }


    public String getParentAccount() { return parentAccount; }
    public String getCount() { return count; }
    public String getTrxType() { return trxType; }
    public String getAmount() { return amount; }
    public String getBalance() { return balance; }
    public String getGiroCd() { return giroCd; }
    public String getSender() { return sender; }
    public String getCheckNo() { return checkNo; }
    public String getCash() { return cash; }
    public String getOtbCheckAmount() { return otbCheckAmount; }
    public String getOtbCheck() { return otbCheck; }
    public String getAccount() { return account; }
    public String getTrxDay() { return trxDay; }
    public String getTrxTime() { return trxTime; }
    public String getBankTrxNo() { return bankTrxNo; }
    public String getBankCd() { return bankCd; }
    public String getBranch() { return branch; }
    public String getFiller() { return filler; }
}