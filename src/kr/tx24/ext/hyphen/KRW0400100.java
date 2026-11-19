package kr.tx24.ext.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

/**
 * 6)	타행 이체 불능 통지 
 */
public class KRW0400100 {

    private String seqNo          = "";   //원거래 전문번호
    private String parentAccount  = "";   //출금 계좌번호
    private String recvAccount    = "";   //입금 계좌번호
    private long amount           = 0;    //금액
    private long successAmount    = 0;    //정상 처리 금액
    private long failureAmount    = 0;    //처리 불능 금액
    private String divisionCount  = "";   //분할 처리 건수 
    private String divisionNumber = "";   //분할 처리 번호
    private String netSeqNo       = "";   //타행 전문 번호
    private long errorAmount      = 0;    //불능 통지 금액
    private String acqResultCd    = "";   //에러 코드
    private String recvBankCd     = "";   //수취 은행 

    public KRW0400100() {
    }


    public KRW0400100(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        this.seqNo          = bb.getS(b, 100, 6);
        this.parentAccount  = bb.getS(b, 106, 15);
        this.recvAccount    = bb.getS(b, 121, 15);
        this.amount         = bb.getN(b, 136, 13);
        this.successAmount  = bb.getN(b, 151, 13);
        this.failureAmount  = bb.getN(b, 164, 13);
        this.divisionCount  = bb.getS(b, 177, 2);
        this.divisionNumber = bb.getS(b, 179, 2);
        this.netSeqNo       = bb.getS(b, 181, 6);
        this.errorAmount    = bb.getN(b, 187, 9);
        this.acqResultCd    = bb.getS(b, 196, 3);
        this.recvBankCd     = bb.getS(b, 199, 3);
    }


    public String getSeqNo() { return seqNo; }
    public String getParentAccount() { return parentAccount; }
    public String getRecvAccount() { return recvAccount; }
    public long getAmount() { return amount; }
    public long getSuccessAmount() { return successAmount; }
    public long getFailureAmount() { return failureAmount; }
    public String getDivisionCount() { return divisionCount; }
    public String getDivisionNumber() { return divisionNumber; }
    public String getNetSeqNo() { return netSeqNo; }
    public long getErrorAmount() { return errorAmount; }
    public String getAcqResultCd() { return acqResultCd; }
    public String getRecvBankCd() { return recvBankCd; }
}