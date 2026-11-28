package kr.tx24.wx.api.svc;

import java.util.function.Consumer;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.Acquirer;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;

/** 모계좌의 기관 설정에 맞춰 송금과 조회를 수행한다. */
public interface UpstreamSvc {

    /** 송금·원거래·잔액 조회에 사용할 기관을 선택한다. */
    static UpstreamSvc open(SharedMap<String, Object> profileMap) {

        return switch (Acquirer.from(profileMap.getString("acq"))) {
            case HYPHEN -> new HyphenSvc(profileMap);
            case COOCON, DOZN -> throw new WalletException(WalletPolicy.INACTIVE_ACQUIRER, WalletPolicy.INACTIVE_ACQUIRER_MSG);
            case UNKNOWN -> throw new WalletException("W003", "모계좌 기관 설정을 확인해 주세요.");
        };
    }

    /** 송금 전문을 보내기 직전에 기관 거래번호를 기록한다. */
    SharedMap<String, Object> transfer(SharedMap<String, Object> inputMap, Consumer<String> recordBeforeSend);

    /** 기관 원거래번호로 미확인 송금의 결과를 조회한다. */
    SharedMap<String, Object> transferResult(String acqTrxId);

    /** 입금 반영 후 모계좌의 기관 잔액을 조회한다. */
    SharedMap<String, Object> balance();
}
