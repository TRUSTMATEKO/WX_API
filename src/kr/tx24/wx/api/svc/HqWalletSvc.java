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

/**
 * 고객사 Wallet과 본사 Wallet 사이의 금액 이전을 처리한다.
 *
 * <p>기준은 OPL_API의 {@code EwalletHqSvc.collect}·{@code payout}이다. 본사 차감은 고객사 Wallet에서
 * 빼고 본사 Wallet에 넣으며, 본사 지급은 그 반대다. 실제 은행 자금 이동이 없으므로 모계좌 잔액은
 * 바뀌지 않는다.</p>
 *
 * <p>분개는 원장 소유 프로세스인 WX_ADM이 한 트랜잭션에 기록한다. 이 계층은 접근 권한과 요청값을
 * 확인하고 결과를 응답으로 바꾼다. 원장 쓰기를 두 프로세스가 나눠 가지면 잠금 순서와 트리거 계약을
 * 두 곳에서 지켜야 하므로 그렇게 하지 않는다.</p>
 */
public final class HqWalletSvc {

    private static final Logger logger = LoggerFactory.getLogger(HqWalletSvc.class);

    private final HqWalletValidator validator;
    private final WalletLedgerRps ledger;

    @Autowired
    public HqWalletSvc(HqWalletValidator validator, WalletLedgerRps ledger) {

        this.validator = validator;
        this.ledger = ledger;
    }

    /** 본사 차감: 고객사 Wallet에서 본사 Wallet으로 옮긴다. */
    public SharedMap<String, Object> collect(SharedMap<String, Object> in, String clientIp, String trace) {

        return move("collect", in, clientIp, trace);
    }

    /** 본사 지급: 본사 Wallet에서 고객사 Wallet으로 옮긴다. */
    public SharedMap<String, Object> payout(SharedMap<String, Object> in, String clientIp, String trace) {

        return move("payout", in, clientIp, trace);
    }

    /** 이전 공통: 접근 구역과 요청값을 확인하고 원장에 분개를 요청한다. */
    private SharedMap<String, Object> move(String direction, SharedMap<String, Object> in, String clientIp,
                                            String trace) {

        // 내부망에서만 호출한다. 본사 조작이므로 고객사 키 인증 경로와 분리되어 있다.
        if (!WalletZone.isInternalRequest(clientIp)) {
            logger.warn("허용하지 않는 주소에서 Wallet 이전을 요청했다: direction={}, clientIp={}, trace={}",
                    direction, clientIp, trace);
            throw new WalletException("W002", "인가되지 않은 접근입니다.");
        }
        WalletZone.verify(clientIp);

        SharedMap<String, Object> input = validator.required(in).add("clientIp", clientIp);
        logger.info("Wallet 이전 접수: direction={}, trackId={}, mchtId={}, walletId={}, amount={}, userId={}, trace={}",
                direction, input.getString("trackId"), input.getString("mchtId"), input.getString("walletId"),
                input.getLong("amount"), input.getString("userId"), trace);

        SharedMap<String, Object> reply;
        try {
            reply = ledger.move(direction, input, trace);
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }
        if (!reply.getBoolean("result")) {
            SharedMap<String, Object> refused = WalletResult.ledger("move", reply, trace);
            logger.warn("Wallet 이전 거절: direction={}, trackId={}, resultCd={}, trace={}",
                    direction, input.getString("trackId"), refused.getString("resultCd"), trace);
            throw new WalletException(refused.getString("resultCd"), refused.getString("advanceMsg"));
        }

        SharedMap<String, Object> data = reply.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (data == null || data.isBlank("walletTrxId")) {
            throw new WalletException("W999", "Wallet 이전 응답을 확인해 주세요.");
        }
        logger.info("Wallet 이전 완료: direction={}, trackId={}, walletTrxId={}, hqWalletTrxId={}, balance={}, trace={}",
                direction, data.getString("trackId"), data.getString("walletTrxId"),
                data.getString("hqWalletTrxId"), data.getLong("balance"), trace);

        SharedMap<String, Object> result = WalletResult.of("0000", "collect".equals(direction) ? "본사 차감이 정상 처리되었습니다."
                : "본사 지급이 정상 처리되었습니다.", trace);
        for (String name : new String[]{"trackId", "mchtId", "walletId", "hqId", "amount",
                "walletTrxId", "hqWalletTrxId", "balance", "availableBalance"}) {
            if (data.containsKey(name)) result.put(name, data.get(name));
        }
        return result;
    }
}
