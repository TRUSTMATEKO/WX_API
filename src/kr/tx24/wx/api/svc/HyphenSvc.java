package kr.tx24.wx.api.svc;

import kr.tx24.ext.hyphen.HyphenPisp;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.map.SharedMap;

public final class HyphenSvc {

    private final SharedMap<String, Object> profile;

    /** 기관 연동용 보호 프로필을 준비한다. 시연 INet Route에서는 호출하지 않는다. */
    public HyphenSvc(SharedMap<String, Object> profile) {

        if (!profile.getBoolean("upstream.enabled")
                || !java.util.Set.of("test", "prod").contains(profile.getString("env"))
                || !profile.getString("acqSid").matches("[A-Za-z0-9_-]{1,32}")
                || !profile.getString("acqBankCd").matches("[0-9]{2,3}")
                || !profile.getString("acqAccount").matches("[0-9]{1,20}") || profile.getString("acqKey2").isBlank()) {
            throw new IllegalArgumentException("HYPHEN 보호 프로필을 확인하세요.");
        }
        this.profile = profile.copy();
    }

    /** 직접 송금: 호출자는 영속 예약·기관 거래 식별자·재조회 계약을 확보한 뒤 한 번만 호출한다. */
    public SharedMap<String, Object> transfer(SharedMap<String, Object> input) {

        String amount  = input.getString("amount");
        String bank    = input.getString("recvBankCd");
        String account = input.getString("recvAccount");
        if (!amount.matches("[1-9][0-9]{0,11}") || !bank.matches("[0-9]{2,3}") || !account.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("기관 지급 입력을 확인하세요.");
        }
        var command = request()
                .data("amount", amount)
                .data("sign", "+")
                .data("recvBankCd", bank)
                .data("recvAccount", account)
                .data("sender", input.getString("sender"))
                .data("description", input.getString("description"));
        return new SharedMap<>(new HyphenPisp().transfer(command));
    }

    /** 직접 잔액조회: 동일한 보호 프로필의 본사 계좌를 사용한다. */
    public SharedMap<String, Object> balance() {

        return new SharedMap<>(new HyphenPisp().balance(request()));
    }

    /** 기관 직접 호출에 공통 계좌·환경 설정을 지정한다. */
    private INet request() {

        return new INet()
                .head("development", profile.isEquals("env", "test"))
                .data("acqSid", profile.getString("acqSid"))
                .data("acqBankCd", profile.getString("acqBankCd"))
                .data("acqAccount", profile.getString("acqAccount"))
                .data("acqKey2", profile.getString("acqKey2"));
    }
}
