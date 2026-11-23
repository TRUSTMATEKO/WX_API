package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 가상계좌 수취확인 
 */
public class KRW0900100 {

    private String account       = "";      //가상계좌번호
    private String beneficiary   = "";      //계좌주명(업체명)
    private String bankCd        = "";      //은행코드
    private String startDay      = "";      //시작일자 (SPACE)
    private String endTime       = "";      //종료시간 (YYYYMMDDHHMMSS)
    private long amount          = 0;       //입금금액
    private String vaType        = "10";    //구분코드 (등록:10, 해제:40)
    private String trxType       = "";      //거래종류 (10:수취확인, 20:입금, 51:취소)
    private String sender        = "";      //의뢰인명
    private String filler        = "";      //예비 

    public KRW0900100() {
    }


    public KRW0900100(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        this.account      = bb.getS(b, 100, 16);
        this.beneficiary  = bb.getS(b, 116, 30);
        this.startDay     = bb.getS(b, 148, 8);
        this.endTime      = bb.getS(b, 156, 14);
        this.amount       = bb.getN(b, 170, 13);
        this.vaType       = bb.getS(b, 183, 2);
        this.trxType      = bb.getS(b, 185, 2);
        this.sender       = bb.getS(b, 187, 20);
        this.bankCd       = bb.getS(b, 207, 3);
        this.filler       = bb.getS(b, 210, b.length - 210);
    }

    

    public String getAccount() { return account; }
    public String getBeneficiary() { return beneficiary; }
    public String getBankCd() { return bankCd; }
    public String getStartDay() { return startDay; }
    public String getEndTime() { return endTime; }
    public long getAmount() { return amount; }
    public String getVaType() { return vaType; }
    public String getTrxType() { return trxType; }
    public String getSender() { return sender; }
    public String getFiller() { return filler; }
}