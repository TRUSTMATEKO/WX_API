package kr.tx24.wx.api.ctx;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 요청 본문과 API 요청 이력을 컨텍스트에 적재한다.
 *
 * <p>OPL_API의 {@code EwalletContextLoader}와 같은 자리다. 접수 이력을 남기지 못하면 업무를
 * 시작하지 않고, 완료 이력을 남기지 못해도 **이미 확정된 업무 결과는 바꾸지 않는다.**</p>
 */
public final class WalletContextLoader {

    private static final Logger logger = LoggerFactory.getLogger(WalletContextLoader.class);

    private final WalletLedgerRps ledger;

    @Autowired
    public WalletContextLoader(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /**
     * 요청 본문을 읽고 접수 이력을 남긴다.
     *
     * <p>본문 해시를 이력에 남기려면 본문을 먼저 읽어야 하므로 두 처리를 한 단계로 둔다.
     * 이력 저장이 실패하면 업무를 진행하지 않는다.</p>
     */
    public void open(WalletContext etx, InputStream body) {

        try {
            byte[] bytes = body.readNBytes(WalletPolicy.FRAME + 1);
            if (bytes.length > WalletPolicy.FRAME) {
                throw new WalletException("W001", "요청 본문이 8,192바이트를 초과했습니다.");
            }

            etx.setBody(bytes);
            etx.setIo(SharedMap.<String, Object>of()
                    .add("ioId", UUID.randomUUID().toString().replace("-", ""))
                    .add("walletId", etx.getWalletId())
                    .add("keyHash", etx.getKeyHash())
                    .add("phase", "received")
                    .add("apiCode", etx.getAction().equals("payout") ? "transfer" : etx.getAction())
                    .add("requestHash", HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes))));

            ledger.recordIo(etx.getIo(), etx.getTrace());
        } catch (WalletException refused) {
            throw refused;
        } catch (Exception failure) {
            logger.error("API 요청 접수 이력 저장 실패, 업무를 진행하지 않는다: action={}, trace={}",
                    etx.getAction(), etx.getTrace(), failure);
            throw new WalletException("W999", "API 요청 이력을 확인해 주세요.", failure);
        }
    }

    /**
     * 완료 이력을 남긴다.
     *
     * <p>저장이 실패해도 업무 결과를 바꾸지 않는다. 이력 행은 접수로 남아 운영자가 확인한다.
     * 종전에는 이 실패가 성공한 송금까지 {@code W999}로 바꿨다.</p>
     */
    public void close(WalletContext etx, SharedMap<String, Object> replyMap) {

        if (etx.getIo() == null) return;

        try {
            etx.getIo().put("phase", "completed");
            etx.getIo().put("resultCd", replyMap.getString("resultCd"));
            etx.getIo().put("resultMsg", message(replyMap));

            ledger.recordIo(etx.getIo(), etx.getTrace());
        } catch (Exception failure) {
            logger.error("API 요청 완료 이력 저장 실패, 업무 결과는 그대로 반환한다: action={}, ioId={}, resultCd={}, trace={}",
                    etx.getAction(), etx.getIo().getString("ioId"), replyMap.getString("resultCd"),
                    etx.getTrace(), failure);
        }
    }

    /**
     * 이력에 남길 결과 문구를 고른다.
     *
     * <p>응답은 문구를 둘 보낸다. {@code resultMsg}는 코드별 고정 문구(`요청 오류`)여서 응답코드에서
     * 그대로 나오고, {@code advanceMsg}가 거절 사유를 말한다. 운영자가 보려는 쪽은 사유이므로
     * 사유를 먼저 쓰고 비었을 때만 고정 문구를 쓴다. 길이는 원장이 열 길이로 자른다.</p>
     */
    private static String message(SharedMap<String, Object> replyMap) {

        String advance = replyMap.getString("advanceMsg").strip();
        return advance.isEmpty() ? replyMap.getString("resultMsg").strip() : advance;
    }
}
