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

/** 고객사 Wallet API의 접근 확인, 요청 이력, 업무 실행과 응답을 처리한다. */
public final class WalletFlowSvc {

    private static final Logger logger = LoggerFactory.getLogger(WalletFlowSvc.class);

    private final WalletValidator validator;
    private final TransferValidator transferValidator;
    private final AuthorizeSvc authorizeSvc;
    private final WalletContextLoader loader;
    private final WalletSvc walletSvc;
    private final TransferSvc transferSvc;
    private final HolderSvc holderSvc;

    /** 고객사 Wallet 네 경로의 검증, 이력 기록과 업무 서비스를 연결한다. */
    @Autowired
    public WalletFlowSvc(WalletValidator validator, TransferValidator transferValidator, AuthorizeSvc authorizeSvc,
                          WalletContextLoader loader, WalletSvc walletSvc, TransferSvc transferSvc,
                          HolderSvc holderSvc) {

        this.validator         = validator;
        this.transferValidator = transferValidator;
        this.authorizeSvc      = authorizeSvc;
        this.loader            = loader;
        this.walletSvc         = walletSvc;
        this.transferSvc       = transferSvc;
        this.holderSvc         = holderSvc;
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

        // 접근 구역과 인증, 접수 이력까지 실패하면 업무를 시작하지 않는다.
        try {
            validator.zone(etx);
            authorizeSvc.authorize(etx);
            loader.open(etx, body);
        } catch (WalletException refused) {
            // 업무를 시작하지 않은 거절이다. 어느 단계에서 막혔는지 남겨야 추적이 된다.
            logger.info("전처리 거절: action={}, clientIp={}, resultCd={}, reason={}, trace={}",
                    action, clientIp, refused.getResultCd(), refused.getAdvanceMsg(), trace);
            return respond(etx, WalletResult.of(refused, trace));
        } catch (Exception failure) {
            logger.debug("전처리 실패: action={}, clientIp={}, trace={}", action, clientIp, trace);
            return respond(etx, WalletResult.of(failure, trace));
        } finally {
            logger.info("Wallet API 접속: action={}, walletId={}, remoteIp={}, trace={}",
                    action, etx.isAuthorized() ? etx.getWalletId() : "미확인",
                    clientIp == null || clientIp.isBlank() ? "미확인" : clientIp, trace);
        }

        logger.debug("전처리 완료: action={}, walletId={}, ioId={}, trace={}",
                action, etx.getWalletId(), etx.getIo().getString("ioId"), trace);

        // 업무 실패도 결과이므로 완료 이력을 남긴다.
        SharedMap<String, Object> replyMap;
        try {
            validator.required(etx);

            // 송금은 인증된 Wallet과 지급 전문으로 서명을 확인한 뒤에만 선차감할 수 있다.
            switch (action) {
                case "payout" -> {
                    transferValidator.isTransferable(etx);
                    replyMap = transferSvc.transfer(etx);
                }
                case "holder" -> replyMap = holderSvc.holder(etx);
                default       -> replyMap = walletSvc.query(etx);
            }
        } catch (WalletException refused) {
            logger.info("업무 거절: action={}, walletId={}, resultCd={}, reason={}, trace={}",
                    action, etx.getWalletId(), refused.getResultCd(), refused.getAdvanceMsg(), trace);
            replyMap = WalletResult.of(refused, trace);
        } catch (Exception failure) {
            logger.debug("업무 실패: action={}, walletId={}, trace={}", action, etx.getWalletId(), trace);
            replyMap = WalletResult.of(failure, trace);
        }

        // 접수 이력이 있는 요청은 업무 거절도 완료 결과로 기록한다.
        loader.close(etx, replyMap);

        logger.info("응답: action={}, walletId={}, resultCd={}, trace={}",
                action, etx.getWalletId(), replyMap.getString("resultCd"), trace);
        return respond(etx, replyMap);
    }

    /**
     * 인증이 끝난 뒤의 응답에는 확정된 Wallet을 함께 돌려준다.
     *
     * <p>인증 실패({@code W002})와 설정·거래 거절({@code W004}) 응답에서는 Wallet ID를 생략한다.</p>
     */
    private static SharedMap<String, Object> respond(WalletContext etx, SharedMap<String, Object> replyMap) {

        if (etx.isAuthorized() && !replyMap.isEquals("resultCd", "W002") && !replyMap.isEquals("resultCd", "W004")) {
            replyMap.put("walletId", etx.getWalletId());
        }
        return replyMap;
    }
}
