package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

public class KRW0700100 {

    private String masterAccount = "";            // 계좌번호 (15)
    private String reqCount = "";           // 당행이체 의뢰 건수 (5)
    private String reqAmount = "";          // 당행이체 의뢰 금액 (13)
    private String successCount = "";       // 당행이체 정상 건수 (5)
    private String successAmount = "";      // 당행이체 정상 금액 (13)
    private String failCount = "";          // 당행이체 불능 건수 (5)
    private String failAmount = "";         // 당행이체 불능 금액 (13)
    private String fee = "";                // 수수료 (9)
    private String otherReqCount = "";      // 타행이체 의뢰 건수 (5)
    private String otherReqAmount = "";     // 타행이체 의뢰 금액 (13)
    private String otherSuccessCount = "";  // 타행이체 정상 건수 (5)
    private String otherSuccessAmount = ""; // 타행이체 정상 금액 (13)
    private String otherFailCount = "";     // 타행이체 불능 건수 (5)
    private String otherFailAmount = "";    // 타행이체 불능 금액 (13)
    private String otherFee = "";           // 타행이체 수수료 (9)
    private String tradeGb = "";            // 처리 구분 (2)
    private String extra = "";              // 예비 (57)

    public KRW0700100() {}

    public KRW0700100(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        this.masterAccount      = bb.getS(b, 100, 15);
        this.reqCount           = bb.getS(b, 115, 5);
        this.reqAmount          = bb.getS(b, 120, 13);
        this.successCount       = bb.getS(b, 133, 5);
        this.successAmount      = bb.getS(b, 138, 13);
        this.failCount          = bb.getS(b, 151, 5);
        this.failAmount         = bb.getS(b, 156, 13);
        this.fee                = bb.getS(b, 169, 9);
        this.otherReqCount      = bb.getS(b, 178, 5);
        this.otherReqAmount     = bb.getS(b, 183, 13);
        this.otherSuccessCount  = bb.getS(b, 196, 5);
        this.otherSuccessAmount = bb.getS(b, 201, 13);
        this.otherFailCount     = bb.getS(b, 214, 5);
        this.otherFailAmount    = bb.getS(b, 219, 13);
        this.otherFee           = bb.getS(b, 232, 9);
        this.tradeGb            = bb.getS(b, 241, 2);
        this.extra              = bb.getS(b, 243, 57);
    }

    public byte[] get() {
        return new ByteBufferUtils()
            .create(200, Charset.forName("KSC5601"))
            .lpad(masterAccount, 15)
            .zpad(reqCount, 5)
            .zpad(reqAmount, 13)
            .zpad(successCount, 5)
            .zpad(successAmount, 13)
            .zpad(failCount, 5)
            .zpad(failAmount, 13)
            .zpad(fee, 9)
            .zpad(otherReqCount, 5)
            .zpad(otherReqAmount, 13)
            .zpad(otherSuccessCount, 5)
            .zpad(otherSuccessAmount, 13)
            .zpad(otherFailCount, 5)
            .zpad(otherFailAmount, 13)
            .zpad(otherFee, 9)
            .lpad(tradeGb, 2)
            .lpad(extra, 57)
            .getArray();
    }

    public String getMasterAccount() { return masterAccount; }
    public KRW0700100 masterAccount(String account) { this.masterAccount = account; return this; }

    public String getReqCount() { return reqCount; }
    public KRW0700100 reqCount(String reqCount) { this.reqCount = reqCount; return this; }

    public String getReqAmount() { return reqAmount; }
    public KRW0700100 reqAmount(String reqAmount) { this.reqAmount = reqAmount; return this; }

    public String getSuccessCount() { return successCount; }
    public KRW0700100 successCount(String successCount) { this.successCount = successCount; return this; }

    public String getSuccessAmount() { return successAmount; }
    public KRW0700100 successAmount(String successAmount) { this.successAmount = successAmount; return this; }

    public String getFailCount() { return failCount; }
    public KRW0700100 failCount(String failCount) { this.failCount = failCount; return this; }

    public String getFailAmount() { return failAmount; }
    public KRW0700100 failAmount(String failAmount) { this.failAmount = failAmount; return this; }

    public String getFee() { return fee; }
    public KRW0700100 fee(String fee) { this.fee = fee; return this; }

    public String getOtherReqCount() { return otherReqCount; }
    public KRW0700100 otherReqCount(String otherReqCount) { this.otherReqCount = otherReqCount; return this; }

    public String getOtherReqAmount() { return otherReqAmount; }
    public KRW0700100 otherReqAmount(String otherReqAmount) { this.otherReqAmount = otherReqAmount; return this; }

    public String getOtherSuccessCount() { return otherSuccessCount; }
    public KRW0700100 otherSuccessCount(String otherSuccessCount) { this.otherSuccessCount = otherSuccessCount; return this; }

    public String getOtherSuccessAmount() { return otherSuccessAmount; }
    public KRW0700100 otherSuccessAmount(String otherSuccessAmount) { this.otherSuccessAmount = otherSuccessAmount; return this; }

    public String getOtherFailCount() { return otherFailCount; }
    public KRW0700100 otherFailCount(String otherFailCount) { this.otherFailCount = otherFailCount; return this; }

    public String getOtherFailAmount() { return otherFailAmount; }
    public KRW0700100 otherFailAmount(String otherFailAmount) { this.otherFailAmount = otherFailAmount; return this; }

    public String getOtherFee() { return otherFee; }
    public KRW0700100 otherFee(String otherFee) { this.otherFee = otherFee; return this; }

    public String getTradeGb() { return tradeGb; }
    public KRW0700100 tradeGb(String tradeGb) { this.tradeGb = tradeGb; return this; }

    public String getExtra() { return extra; }
    public KRW0700100 extra(String extra) { this.extra = extra; return this; }
}