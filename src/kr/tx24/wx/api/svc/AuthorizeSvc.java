package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.ctx.WalletContext;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.vld.WalletValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wallet 키 인증과 접속 IP 확인을 담당한다.
 *
 * <p>OPL_API의 {@code AuthorizeSvc}와 같은 자리다. 인증 판단은 원장이 하고 이 계층은
 * 키 형식 확인과 결과 적재만 한다. 키 원문은 컨텍스트 밖으로 내보내지 않는다.</p>
 */
public final class AuthorizeSvc {

    private static final Logger logger = LoggerFactory.getLogger(AuthorizeSvc.class);

    private final WalletValidator validator;
    private final WalletLedgerRps ledger;

    @Autowired
    public AuthorizeSvc(WalletValidator validator, WalletLedgerRps ledger) {

        this.validator = validator;
        this.ledger = ledger;
    }

    /**
     * 키 형식을 확인하고 원장에서 키에 연결된 Wallet을 확정한다.
     *
     * <p>키 원문은 저장하지 않고 SHA-256 해시만 원장에 보낸다. 접속 IP 대조는 키에 등록된
     * `ips` 규칙으로 원장이 수행한다.</p>
     */
    public void authorize(WalletContext etx) {

        validator.walletKey(etx);
        etx.setKeyHash(CommonUtils.hashSHA256(etx.getAuthorization()));

        SharedMap<String, Object> reply;
        try {
            reply = ledger.authorize(etx.getKeyHash(), etx.getClientIp(), etx.getTrace());
        } catch (Exception failure) {
            throw WalletLedgerRps.unreachable(failure);
        }
        if (!reply.getBoolean("result")) {
            SharedMap<String, Object> refused = WalletResult.ledger(etx.getAction(), reply, etx.getTrace());
            throw new WalletException(refused.getString("resultCd"), refused.getString("advanceMsg"));
        }
        SharedMap<String, Object> data = reply.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (data == null || data.isBlank("walletId")) {
            throw new WalletException("W999", "인증된 Wallet 응답을 확인해 주세요.");
        }
        etx.setWalletId(data.getString("walletId"));
        logger.debug("Wallet 인증 완료: action={}, walletId={}, trace={}",
                etx.getAction(), etx.getWalletId(), etx.getTrace());
    }
}
