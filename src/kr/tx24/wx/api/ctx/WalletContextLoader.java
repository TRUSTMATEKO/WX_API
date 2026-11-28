package kr.tx24.wx.api.ctx;

import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 요청 본문과 API 요청 이력을 컨텍스트에 적재한다.
 *
 * <p>접수 시각과 거래가 참조할 이력 ID를 준비한다. 이력은 응답 종료 뒤 한 번 삽입한다.</p>
 */
public final class WalletContextLoader {

    private static final Logger logger = LoggerFactory.getLogger(WalletContextLoader.class);

    private final WalletLedgerRps ledger;

    @Autowired
    public WalletContextLoader(WalletLedgerRps ledger) {

        this.ledger = ledger;
    }

    /**
     * 요청 본문을 읽고 나중에 저장할 접수 시각, 요청 해시와 이력 ID를 준비한다.
     */
    public void open(WalletContext etx, InputStream body) {

        try {
            LocalDateTime receivedAt = DateUtils.getDate();
            byte[] bytes = body.readNBytes(WalletPolicy.FRAME + 1);
            if (bytes.length > WalletPolicy.FRAME) {
                throw new WalletException("W001", "요청 본문이 8,192바이트를 초과했습니다.");
            }

            etx.setBody(bytes);
            etx.getIo()
                    .add("ioId", UUID.randomUUID().toString().replace("-", ""))
                    .add("regDate", Timestamp.valueOf(receivedAt))
                    .add("regDay", Integer.parseInt(DateUtils.toString(receivedAt.toLocalDate())))
                    .add("apiCode", etx.getAction().equals("payout") ? "transfer" : etx.getAction())
                    .add("requestHash", HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (WalletException refused) {
            throw refused;
        } catch (Exception failure) {
            logger.error("API 요청 이력 준비 실패: action={}, trace={}",
                    etx.getAction(), etx.getTrace(), failure);
            throw new WalletException("W999", "API 요청 이력을 확인해 주세요.", failure);
        }
    }

    /**
     * 응답 종료 뒤 저장할 이력 작업을 준비한다. 본문과 인증 키는 작업에 넘기지 않는다.
     */
    public Runnable complete(WalletContext etx, SharedMap<String, Object> replyMap) {

        SharedMap<String, Object> ioMap = etx.getIo();
        String                  trace = etx.getTrace();
        ioMap.put("resultCd", replyMap.getString("resultCd"));
        ioMap.put("resultMsg", message(replyMap));
        return () -> record(ioMap, trace);
    }

    /** 응답이 종료된 고객사 요청의 이력 한 건을 저장한다. */
    private void record(SharedMap<String, Object> ioMap, String trace) {

        try {
            ledger.recordIo(ioMap, trace);
        } catch (Exception failure) {
            logger.error("API 요청 완료 이력 저장 실패: action={}, ioId={}, resultCd={}, trace={}",
                    ioMap.getString("apiCode"), ioMap.getString("ioId"), ioMap.getString("resultCd"), trace, failure);
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
