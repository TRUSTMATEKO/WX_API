package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.ctx.WalletContext;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 고객사 잔액조회와 거래조회를 처리한다. */
public final class WalletSvc {

    private static final Logger logger = LoggerFactory.getLogger(WalletSvc.class);

    private final WalletLedgerRps ledger;

    /** 잔액과 거래내역을 조회할 원장을 연결한다. */
    @Autowired
    public WalletSvc(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /** 조회 업무: 원장 결과를 고객사 응답으로 바꾼다. */
    public SharedMap<String, Object> query(WalletContext etx) {

        String                    action   = etx.getAction();
        String                    trace    = etx.getTrace();
        SharedMap<String, Object> inputMap = etx.getInput();

        // 잔액과 거래내역은 API 키로 확인된 Wallet 범위에서 조회한다.
        inputMap.put("walletId", etx.getWalletId());
        SharedMap<String, Object> replyMap;
        try {
            replyMap = ledger.query(action, inputMap, etx.getKeyHash(), etx.getClientIp(), trace);
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }

        SharedMap<String, Object> resultMap = WalletResult.ledger(action, replyMap, trace);
        logger.info("Wallet 조회 처리: action={}, walletId={}, resultCd={}, trace={}",
                action, etx.getWalletId(), resultMap.getString("resultCd"), trace);
        return resultMap;
    }
}
