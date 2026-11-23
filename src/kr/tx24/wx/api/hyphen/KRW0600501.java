package kr.tx24.wx.api.hyphen;


import kr.tx24.lib.lang.ByteBufferUtils;

import java.nio.charset.Charset;

public class KRW0600501 {

    private String trCd = "D";          // 식별코드 (C, 1) - Offset 100
    private String sequence = "";       // 처리순번 (N, 7) - Offset 101

    private String account = "";        // 계좌번호 (C, 16) - Offset 110
    private String applyType = "";      // 신청구분 (C, 1) - Offset 126
    private String transferDay = "";    // 자동납부일자 (D, 2) - Offset 127
    private String handCd = "";         // 취급점코드6 (C, 6) - Offset 129
    private String applyDate = "";      // 신청일자 (D, 8) - Offset 135
    private String applyYn = "";        // 처리여부 (C, 1) - Offset 143
    private String resultCd = "";       // 불능코드 (C, 4) - Offset 144
    private String idenYn = "";         // 신원확인번호 체크 (C, 1) - Offset 148
    private String idenDigit = "";      // 신원확인번호 (C, 13) - Offset 149
    private String bankId = "";         // 납부자번호 (C, 20) - Offset 162
    private String info = "";           // 업체/은행 사용정보 (C, 16) - Offset 182
    private String collectCd = "";      // 기관코드 (C, 10) - Offset 198
    private String bankCode = "";    // 은행코드3 (N, 3) - Offset 208 (필드 추가/조정)
    private String branchCode = "0000000";     // 취급점코드7 (C, 7) - Offset 211
    private String extra = "";          // 일부면제 업체구분 (C, 1) - Offset 218
    private String extra2 = "";         // 이용기관코드 (C, 20) - Offset 219
    private String extra3 = "";         // 예금주실명번호종류 (C, 1) - Offset 239
    private String extra4 = "";         // 동의자료구분 (C, 1) - Offset 240
    private String extra5 = "";         // 예비 (35) - Offset 241
    private String extra6 = "";         // ARS처리일련번호 (C, 12) - Offset 276
    private String extra7 = "";         // 예비2 (12) - Offset 288

    public KRW0600501() {}

    public KRW0600501(byte[] b) {
        ByteBufferUtils bb = new ByteBufferUtils(Charset.forName("KSC5601"));
        this.trCd        = bb.getS(b, 100, 1);
        this.sequence    = bb.getS(b, 101, 7);
        this.account     = bb.getS(b, 110, 16);
        this.applyType   = bb.getS(b, 126, 1);
        this.transferDay = bb.getS(b, 127, 2);
        this.handCd      = bb.getS(b, 129, 6);
        this.applyDate   = bb.getS(b, 135, 8);
        this.applyYn     = bb.getS(b, 143, 1);
        this.resultCd    = bb.getS(b, 144, 4);
        this.idenYn      = bb.getS(b, 148, 1);
        this.idenDigit   = bb.getS(b, 149, 13);
        this.bankId      = bb.getS(b, 162, 20);
        this.info        = bb.getS(b, 182, 16);
        this.collectCd   = bb.getS(b, 198, 10);
        this.bankCode    = bb.getS(b, 208, 3);
        this.branchCode  = bb.getS(b, 211, 7);
        this.extra       = bb.getS(b, 218, 1);
        this.extra2      = bb.getS(b, 219, 20);
        this.extra3      = bb.getS(b, 239, 1);
        this.extra4      = bb.getS(b, 240, 1);
        this.extra5      = bb.getS(b, 241, 35);
        this.extra6      = bb.getS(b, 276, 12);
        this.extra7      = bb.getS(b, 288, 12);
    }

    public byte[] get() {
        return new ByteBufferUtils()
            .create(200, Charset.forName("KSC5601"))
            .lpad(trCd, 1)
            .zpad(sequence, 7)
            .spad(2)
            .lpad(account, 16)
            .lpad(applyType, 1)
            .lpad(transferDay, 2)
            .lpad(handCd, 6)
            .lpad(applyDate, 8)
            .lpad(applyYn, 1)
            .lpad(resultCd, 4)
            .lpad(idenYn, 1)
            .lpad(idenDigit, 13)
            .lpad(bankId, 20)
            .lpad(info, 16)
            .lpad(collectCd, 10)
            .zpad(bankCode, 3)
            .lpad(branchCode, 7)
            .lpad(extra, 1)
            .lpad(extra2, 20)
            .lpad(extra3, 1)
            .lpad(extra4, 1)
            .lpad(extra5, 35)
            .lpad(extra6, 12)
            .lpad(extra7, 12)
            .getArray();
    }

   public String getTrCd() { return trCd; }
    public KRW0600501 trCd(String trCd) { this.trCd = trCd; return this; }

    public String getSequence() { return sequence; }
    public KRW0600501 sequence(String sequence) { this.sequence = sequence; return this; }

    public String getBankCode() { return bankCode; }
    public KRW0600501 bankCode(String bankCode) { this.bankCode = bankCode; return this; }

    public String getAccount() { return account; }
    public KRW0600501 account(String account) { this.account = account; return this; }

    public String getApplyType() { return applyType; }
    public KRW0600501 applyType(String applyType) { this.applyType = applyType; return this; }

    public String getTransferDay() { return transferDay; }
    public KRW0600501 transferDay(String transferDay) { this.transferDay = transferDay; return this; }

    public String getHandCd() { return handCd; }
    public KRW0600501 handCd(String handCd) { this.handCd = handCd; return this; }

    public String getApplyDate() { return applyDate; }
    public KRW0600501 applyDate(String applyDate) { this.applyDate = applyDate; return this; }

    public String getApplyYn() { return applyYn; }
    public KRW0600501 applyYn(String applyYn) { this.applyYn = applyYn; return this; }

    public String getResultCd() { return resultCd; }
    public KRW0600501 resultCd(String resultCd) { this.resultCd = resultCd; return this; }

    public String getIdenYn() { return idenYn; }
    public KRW0600501 idenYn(String idenYn) { this.idenYn = idenYn; return this; }

    public String getIdenDigit() { return idenDigit; }
    public KRW0600501 idenDigit(String idenDigit) { this.idenDigit = idenDigit; return this; }

    public String getBankId() { return bankId; }
    public KRW0600501 bankId(String bankId) { this.bankId = bankId; return this; }

    public String getInfo() { return info; }
    public KRW0600501 info(String info) { this.info = info; return this; }

    public String getCollectCd() { return collectCd; }
    public KRW0600501 collectCd(String collectCd) { this.collectCd = collectCd; return this; }

    

    public String getBranchCode() { return branchCode; }
    public KRW0600501 branchCode(String branchCode) { this.branchCode = branchCode; return this; }

    public String getExtra() { return extra; }
    public KRW0600501 extra(String extra) { this.extra = extra; return this; }

    public String getExtra2() { return extra2; }
    public KRW0600501 extra2(String extra2) { this.extra2 = extra2; return this; }

    public String getExtra3() { return extra3; }
    public KRW0600501 extra3(String extra3) { this.extra3 = extra3; return this; }

    public String getExtra4() { return extra4; }
    public KRW0600501 extra4(String extra4) { this.extra4 = extra4; return this; }

    public String getExtra5() { return extra5; }
    public KRW0600501 extra5(String extra5) { this.extra5 = extra5; return this; }

    public String getExtra6() { return extra6; }
    public KRW0600501 extra6(String extra6) { this.extra6 = extra6; return this; }

    public String getExtra7() { return extra7; }
    public KRW0600501 extra7(String extra7) { this.extra7 = extra7; return this; }
}