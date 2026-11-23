package kr.tx24.wx.api.svc;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.ctx.WalletContext;
import kr.tx24.wx.api.ctx.WalletContextLoader;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.vld.TransferValidator;
import kr.tx24.wx.api.vld.WalletValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Wallet 네 업무의 공통 절차를 한곳에서 순서대로 수행한다.
 *
 * <p>진입 경로는 게이트웨이 INet({@code MerchantApi}) 하나다. 흐름을 컨트롤러에 두지 않고
 * 여기 한 번만 두어 시험 도구도 같은 절차를 지나게 한다.</p>
 *
 * <p>절차와 그 이유</p>
 * <ol>
 *   <li>접근 구역 — 국가 차단과 차단 목록. 막히면 원장도 이력도 건드리지 않는다</li>
 *   <li>인증 — 키 형식과 키에 연결된 Wallet, 접속 IP</li>
 *   <li>접수 이력 — 본문을 읽어 해시와 함께 남긴다. 실패하면 <b>업무를 시작하지 않는다</b></li>
 *   <li>형식 검증 — Content-Type과 JSON 필드</li>
 *   <li>업무 — 잔액·거래조회는 {@link WalletSvc}, 송금은 {@link TransferSvc},
 *       예금주 조회는 {@link HolderSvc}</li>
 *   <li>완료 이력 — 실패해도 <b>이미 확정된 업무 결과를 바꾸지 않는다</b></li>
 * </ol>
 */
public final class WalletFlowSvc {

    private static final Logger logger = LoggerFactory.getLogger(WalletFlowSvc.class);

    private final WalletValidator validator;
    private final TransferValidator transferValidator;
    private final AuthorizeSvc authorizeSvc;
    private final WalletContextLoader loader;
    private final WalletSvc walletSvc;
    private final TransferSvc transferSvc;
    private final HolderSvc holderSvc;

    @Autowired
    public WalletFlowSvc(WalletValidator validator, TransferValidator transferValidator, AuthorizeSvc authorizeSvc,
                          WalletContextLoader loader, WalletSvc walletSvc, TransferSvc transferSvc,
                          HolderSvc holderSvc) {

        this.validator = validator;
        this.transferValidator = transferValidator;
        this.authorizeSvc = authorizeSvc;
        this.loader = loader;
        this.walletSvc = walletSvc;
        this.transferSvc = transferSvc;
        this.holderSvc = holderSvc;
    }

    /**
     * 라우터 밖에서 쓰는 조립 지점이다.
     *
     * <p>{@code ai/tools} 의 시험 도구는 {@code @Controller}가 아니어서 생성자 주입을 받지
     * 못한다. 운영 경로와 같은 계층 구성을 쓰도록 조립을 이 한 곳에 둔다.</p>
     */
    public static WalletFlowSvc create() {

        WalletLedgerRps ledger = new WalletLedgerRps();
        WalletValidator validator = new WalletValidator();
        return new WalletFlowSvc(validator, new TransferValidator(), new AuthorizeSvc(validator, ledger),
                new WalletContextLoader(ledger), new WalletSvc(ledger), new TransferSvc(ledger),
                new HolderSvc(ledger));
    }

    /** Wallet 네 경로: 키와 등록 IP를 먼저 확인한 뒤 업무 본문을 읽고 처리한다. */
    public SharedMap<String, Object> wallet(String action, String authorization, String clientIp,
                                             String contentType, InputStream body, String trace) {

        WalletContext etx = new WalletContext(action, authorization, clientIp, contentType, trace);
        logger.debug("요청 수신: action={}, clientIp={}, contentType={}, trace={}",
                action, clientIp, contentType, trace);

        // 접근 구역과 인증, 접수 이력까지 실패하면 업무를 시작하지 않는다.
        try {
            validator.zone(etx);
            authorizeSvc.authorize(etx);
            loader.open(etx, body);
        } catch (WalletException refused) {
            // 업무를 시작하지 않은 거절이다. 어느 단계에서 막혔는지 남겨야 추적이 된다.
            logger.warn("전처리 거절: action={}, clientIp={}, resultCd={}, reason={}, trace={}",
                    action, clientIp, refused.getResultCd(), refused.getAdvanceMsg(), trace);
            return respond(etx, WalletResult.of(refused, trace));
        } catch (Exception failure) {
            logger.error("전처리 실패: action={}, clientIp={}, trace={}", action, clientIp, trace, failure);
            return respond(etx, WalletResult.of(failure, trace));
        }
        logger.info("전처리 완료: action={}, walletId={}, ioId={}, trace={}",
                action, etx.getWalletId(), etx.getIo().getString("ioId"), trace);

        // 업무 실패도 결과이므로 완료 이력을 남긴다.
        SharedMap<String, Object> reply;
        try {
            validator.required(etx);
            reply = execute(etx);
        } catch (WalletException refused) {
            logger.warn("업무 거절: action={}, walletId={}, resultCd={}, reason={}, trace={}",
                    action, etx.getWalletId(), refused.getResultCd(), refused.getAdvanceMsg(), trace);
            reply = WalletResult.of(refused, trace);
        } catch (Exception failure) {
            logger.error("업무 실패: action={}, walletId={}, trace={}", action, etx.getWalletId(), trace, failure);
            reply = WalletResult.of(failure, trace);
        }
        loader.close(etx, reply);
        logger.info("응답: action={}, walletId={}, resultCd={}, trace={}",
                action, etx.getWalletId(), reply.getString("resultCd"), trace);
        return respond(etx, reply);
    }

    /** 업무 분기. 송금만 자금이 움직이므로 전용 검증을 먼저 통과해야 한다. */
    private SharedMap<String, Object> execute(WalletContext etx) {

        if (etx.getAction().equals("payout")) {
            transferValidator.isTransferable(etx);
            return transferSvc.transfer(etx);
        }
        if (etx.getAction().equals("holder")) return holderSvc.holder(etx);
        return walletSvc.query(etx);
    }

    /**
     * 인증이 끝난 뒤의 응답에는 확정된 Wallet을 함께 돌려준다.
     *
     * <p>인증 실패({@code W002})와 Wallet 설정 오류({@code W004})에는 넣지 않는다.
     * 그 두 경우에는 돌려줄 Wallet이 확정되지 않았다.</p>
     */
    private static SharedMap<String, Object> respond(WalletContext etx, SharedMap<String, Object> reply) {

        if (etx.isAuthorized() && !reply.isEquals("resultCd", "W002") && !reply.isEquals("resultCd", "W004")) {
            reply.put("walletId", etx.getWalletId());
        }
        return reply;
    }
}
