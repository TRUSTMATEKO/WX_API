package kr.tx24.ext.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * @author juseop
 *
 */
public class KRWHead {

    private long idx            = 0L;    
    private String trCd         = ""; // 식별코드
    private String company      = ""; // 업체코드
    private String bankCd       = ""; // 은행코드
    private String trxType      = ""; // 전문코드
    private String trxCd        = ""; // 구분코드
    private String cnt          = "1"; // 전문송신횟수('1' or 'Y')
    private String seqNo        = ""; // 전문일련번호
    private String trxDay       = ""; // 거래일자
    private String trxTime      = ""; // 거래시간
    private String ksResultCd   = ""; // KSNET 응답코드
    private String acqResultCd  = ""; // 은행응답코드
    private String rootTrxDay   = ""; // 조회시 조회일자
    private String rootSeqNo    = ""; // 원거래전문번호
    private String bankSeqNo    = ""; // 은행전문번호
    private String filler       = "";
    private byte[] data         = null;

    public KRWHead() {
    }

    public KRWHead(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        trCd        = bb.getS(b, 0, 9);
        company     = bb.getS(b, 9, 8);
        trxType     = bb.getS(b, 19, 4);
        trxCd       = bb.getS(b, 23, 3);
        cnt         = bb.getS(b, 26, 1);
        seqNo       = bb.getS(b, 27, 6);
        trxDay      = bb.getS(b, 33, 8);
        trxTime     = bb.getS(b, 41, 6);
        ksResultCd  = bb.getS(b, 47, 4);
        acqResultCd = bb.getS(b, 51, 4);
        rootTrxDay  = bb.getS(b, 55, 8);
        rootSeqNo   = bb.getS(b, 63, 6);
        bankSeqNo   = bb.getS(b, 69, 15);
        bankCd      = bb.getS(b, 84, 3);
        filler      = bb.getS(b, 87, 13);

    }

    public byte[] get(byte[] data) {
        ByteBufferUtils bb = get().put(data);
        return bb.getArray();
    }

    public byte[] get(ByteBuffer data){
        ByteBufferUtils bb = get().put(data);
        return bb.getArray();
	}

    private ByteBufferUtils get() {
        return new ByteBufferUtils()
       .create(300, Charset.forName("KSC5601"))
       .lpad(trCd       , 9)
       .lpad(company    , 8)
       .lpad(bankCd.substring(1), 2)
       .lpad(trxType    , 4)
       .zpad(trxCd      , 3)
       .zpad(cnt        , 1)
       .zpad(seqNo      , 6)
       .lpad(trxDay     , 8)
       .lpad(trxTime    , 6)
       .lpad(ksResultCd , 4)
       .lpad(acqResultCd, 4)
       .lpad(rootTrxDay , 8)
       .zpad(rootSeqNo  , 6)
       .lpad(bankSeqNo  , 15)
       .zpad(bankCd     , 3)
       .lpad(filler     , 13);

    }


    public long getIdx() { return idx; }
    public KRWHead idx(long idx) { this.idx = idx; return this; }

    public String getTrCd() { return trCd; }
    public KRWHead trCd(String trCd) { this.trCd = trCd; return this; }

    public String getCompany() { return company; }
    public KRWHead company(String company) { this.company = company; return this; }

    public String getBankCd() { return bankCd; }
    public KRWHead bankCd(String bankCd) { this.bankCd = bankCd; return this; }

    public String getTrxType() { return trxType; }
    public KRWHead trxType(String trxType) { this.trxType = trxType; return this; }

    public String getTrxCd() { return trxCd; }
    public KRWHead trxCd(String trxCd) { this.trxCd = trxCd; return this; }

    public String getCnt() { return cnt; }
    public KRWHead cnt(String cnt) { this.cnt = cnt; return this; }

    public String getSeqNo() { return seqNo; }
    public KRWHead seqNo(String seqNo) { this.seqNo = seqNo; return this; }

    public String getTrxDay() { return trxDay; }
    public KRWHead trxDay(String trxDay) { this.trxDay = trxDay; return this; }

    public String getTrxTime() { return trxTime; }
    public KRWHead trxTime(String trxTime) { this.trxTime = trxTime; return this; }

    public String getKsResultCd() { return ksResultCd; }
    public KRWHead ksResultCd(String ksResultCd) { this.ksResultCd = ksResultCd; return this; }

    public String getAcqResultCd() { return acqResultCd; }
    public KRWHead acqResultCd(String acqResultCd) { this.acqResultCd = acqResultCd; return this; }

    public String getRootTrxDay() { return rootTrxDay; }
    public KRWHead rootTrxDay(String rootTrxDay) { this.rootTrxDay = rootTrxDay; return this; }

    public String getRootSeqNo() { return rootSeqNo; }
    public KRWHead rootSeqNo(String rootSeqNo) { this.rootSeqNo = rootSeqNo; return this; }

    public String getBankSeqNo() { return bankSeqNo; }
    public KRWHead bankSeqNo(String bankSeqNo) { this.bankSeqNo = bankSeqNo; return this; }

    public String getFiller() { return filler; }
    public KRWHead filler(String filler) { this.filler = filler; return this; }

    public byte[] getData() { return data; }
    public KRWHead data(byte[] data) { this.data = data; return this; }

}
