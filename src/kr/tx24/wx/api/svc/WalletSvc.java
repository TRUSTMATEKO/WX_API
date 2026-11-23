package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.ctx.WalletContext;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 조회 업무를 처리한다. 잔액조회, 거래조회, 예금주조회가 여기에 있다.
 *
 * <p>세 업무 모두 원장에 물어 그 결과를 응답으로 바꾼다. 자금이 움직이지 않으므로
 * 원장은 조회 전용 트랜잭션을 쓴다.</p>
 */
public final class WalletSvc {

    private static final Logger logger = LoggerFactory.getLogger(WalletSvc.class);

    private final WalletLedgerRps ledger;

    @Autowired
    public WalletSvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 조회 업무: 원장 결과를 고객사 응답으로 바꾼다. */
    public SharedMap<String, Object> query(WalletContext etx) {

        etx.getInput().put("authorizedWalletId", etx.getWalletId());
        SharedMap<String, Object> reply;
        try {
            reply = ledger.query(etx.getAction(), etx.getInput(), etx.getKeyHash(), etx.getClientIp(),
                    etx.getTrace());
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }
        SharedMap<String, Object> result = WalletResult.ledger(etx.getAction(), reply, etx.getTrace());
        logger.info("Wallet 조회 처리: action={}, walletId={}, resultCd={}, trace={}",
                etx.getAction(), etx.getWalletId(), result.getString("resultCd"), etx.getTrace());
        return result;
    }
}
