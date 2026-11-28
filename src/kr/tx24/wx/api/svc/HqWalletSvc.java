package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.WalletZone;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.vld.HqWalletValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 고객사와 본사 Wallet 사이의 차감·지급을 WX_API 원장에서 처리한다. */
public final class HqWalletSvc {

    private static final Logger logger = LoggerFactory.getLogger(HqWalletSvc.class);

    private final HqWalletValidator validator;
    private final WalletLedgerRps ledger;

    /** 본사 차감과 지급의 입력 검증 및 원장 분개를 연결한다. */
    @Autowired
    public HqWalletSvc(HqWalletValidator validator, WalletLedgerRps ledger) {

        this.validator = validator;
        this.ledger    = ledger;
    }

    /** 본사 차감: 고객사 Wallet에서 본사 Wallet으로 옮긴다. */
    public SharedMap<String, Object> collect(SharedMap<String, Object> inMap, String clientIp, String trace) {

        return move("collect", inMap, clientIp, trace);
    }

    /** 본사 지급: 본사 Wallet에서 고객사 Wallet으로 옮긴다. */
    public SharedMap<String, Object> payout(SharedMap<String, Object> inMap, String clientIp, String trace) {

        return move("payout", inMap, clientIp, trace);
    }

    /** 이전 공통: 접근 구역과 요청값을 확인하고 원장에 분개를 요청한다. */
    private SharedMap<String, Object> move(String direction, SharedMap<String, Object> inMap, String clientIp,
                                            String trace) {

        // 내부망에서만 호출한다. 본사 조작이므로 고객사 키 인증 경로와 분리되어 있다.
        if (!WalletZone.isInternalRequest(clientIp)) {
            logger.info("허용하지 않는 주소에서 Wallet 이전을 요청했다: direction={}, clientIp={}, trace={}",
                    direction, clientIp, trace);
            throw new WalletException("W002", "인가되지 않은 접근입니다.");
        }
        WalletZone.verify(clientIp);

        SharedMap<String, Object> inputMap = validator.required(inMap).add("clientIp", clientIp);
        logger.info("Wallet 이전 접수: direction={}, trackId={}, mchtId={}, walletId={}, amount={}, userId={}, trace={}",
                direction, inputMap.getString("trackId"), inputMap.getString("mchtId"), inputMap.getString("walletId"),
                inputMap.getLong("amount"), inputMap.getString("userId"), trace);

        // 두 Wallet의 분개를 한 트랜잭션에 기록한 뒤 확정된 거래번호와 잔액을 반환한다.
        SharedMap<String, Object> replyMap;
        try {
            replyMap = ledger.move(direction, inputMap, trace);
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }

        if (!replyMap.getBoolean("result")) {
            SharedMap<String, Object> refusedMap = WalletResult.ledger("move", replyMap, trace);
            logger.info("Wallet 이전 거절: direction={}, trackId={}, resultCd={}, trace={}",
                    direction, inputMap.getString("trackId"), refusedMap.getString("resultCd"), trace);
            throw new WalletException(refusedMap.getString("resultCd"), refusedMap.getString("advanceMsg"));
        }

        SharedMap<String, Object> dataMap = replyMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (dataMap == null || dataMap.isBlank("walletTrxId")) {
            throw new WalletException("W999", "Wallet 이전 응답을 확인해 주세요.");
        }

        logger.info("Wallet 이전 완료: direction={}, trackId={}, walletTrxId={}, hqWalletTrxId={}, balance={}, trace={}",
                direction, dataMap.getString("trackId"), dataMap.getString("walletTrxId"),
                dataMap.getString("hqWalletTrxId"), dataMap.getLong("balance"), trace);

        String                    message   = "collect".equals(direction) ? "본사 차감이 정상 처리되었습니다." : "본사 지급이 정상 처리되었습니다.";
        SharedMap<String, Object> resultMap = WalletResult.of("0000", message, trace);
        for (String name : new String[]{"trackId", "mchtId", "walletId", "hqId", "amount",
                "walletTrxId", "hqWalletTrxId", "balance", "availableBalance"}) {
            if (dataMap.containsKey(name)) {
                resultMap.put(name, dataMap.get(name));
            }
        }
        return resultMap;
    }
}
