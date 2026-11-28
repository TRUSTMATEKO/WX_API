package kr.tx24.wx.api.holder;

import kr.tx24.lib.inter.INet;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.hyphen.HyphenPisp;

/** 공통 기관코드와 전문 키로 HYPHEN 0600/400 예금주 조회 전문을 전송한다. */
final class HyphenHolder implements HolderProvider {

    private final boolean test;
    private final String org;
    private final String key;

    HyphenHolder(boolean test, String org, String key) {

        this.test = test;
        this.org  = org;
        this.key  = key;
    }

    @Override
    public SharedMap<String, Object> holder(String bank, String account) {

        INet request = new INet()
                .head("development", test)
                .data("hqId", "SYSTEM-HOLDER")
                .data("acqSid", org)
                .data("acqKey2", key)
                .data("recvBankCd", bank)
                .data("recvAccount", account);
        return new SharedMap<>(new HyphenPisp().holder(request));
    }
}
