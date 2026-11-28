package kr.tx24.wx.api.svc;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.tx24.inet.mapper.Autowired;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisUtils;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletException;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.wx.api.WalletResult;
import kr.tx24.wx.api.rps.HqWithdrawRps;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.utils.NotificationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HqWithdrawSvc {

    private static final Logger logger = LoggerFactory.getLogger(HqWithdrawSvc.class);
    private static final String MANUAL_APPROVAL = "WX:HQ_WITHDRAW:APPROVAL:";
    private final HqWithdrawRps ledger;

    /** 본사 외부 출금 접수 및 TASK 원거래 재조회에 쓰는 원장을 연결한다. */
    @Autowired
    public HqWithdrawSvc(WalletLedgerRps ledger) {

        this.ledger = ledger.withdrawals();
    }

    /** 내부 출금: 한 번 접수한 요청만 기관에 보내고 결과를 원장에 기록한다. */
    public SharedMap<String, Object> submit(SharedMap<String, Object> inMap, String trace) throws Exception {

        // 임의 출금의 금액과 대상은 본사에서 2차 인증한 요청을 한 번 꺼내 사용한다.
        if (inMap.isEquals("kind", "manual")) {
            inMap = approvedManual(inMap.getString("id"));
        }

        boolean fee = inMap.isEquals("kind", "fee");
        if ((!fee && !inMap.isEquals("kind", "manual")) || !inMap.getString("id").matches("[A-Za-z0-9_-]{16,40}")
                || !inMap.getString("amount").matches("[1-9][0-9]{0,11}") || inMap.isBlank("userId") || inMap.isBlank("reason")
                || inMap.getString("reason").length() > 128 || !inMap.getString("settingRev").matches("[0-9]{1,10}")
                || (fee && (inMap.isBlank("mchtId") || !inMap.getString("baseDay").matches("[0-9]{8}")))
                || (!fee && (!inMap.isBlank("mchtId") || inMap.getInt("baseDay") != 0))) {
            throw new WalletException("W001", "출금 요청값을 확인해 주세요.");
        }

        // 신규 접수만 기관으로 전송한다. 같은 요청번호의 재접수에는 저장된 상태를 돌려준다.
        String                    id          = inMap.getString("id");
        SharedMap<String, Object> acceptedMap = ledger.accept(inMap);
        String                    status      = acceptedMap.getString("status");
        if (acceptedMap.getBoolean("send")) {
            status = send(id, inMap.getString("amount"), acceptedMap);
            if ("미확인".equals(status)) {
                NotificationUtils.incident("TRX_HQ_WITHDRAW", id,
                        "[WX] 모계좌 출금 응답 미확인. 출금번호 " + id);
            }
        }

        return WalletResult.of("0000", "모계좌 출금 " + status, trace)
                .add("id", id)
                .add("status", status);
    }

    /** 본사 출금 전문을 보내고 전송 시점과 기관 응답으로 출금 상태를 확정한다. */
    private String send(String id, String amount, SharedMap<String, Object> acceptedMap) throws SQLException {

        AtomicBoolean referenceRecorded = new AtomicBoolean();
        SharedMap<String, Object> bankMap;
        try {
            HyphenSvc                 channel    = channel(acceptedMap);
            SharedMap<String, Object> requestMap = SharedMap.<String, Object>of()
                    .add("amount", amount)
                    .add("recvBankCd", acceptedMap.getString("bankCd"))
                    .add("recvAccount", acceptedMap.getString("destination"))
                    .add("sender", acceptedMap.getString("sender"))
                    .add("description", acceptedMap.getString("holder"));

            // 접수에서 처리중으로 바꾼 뒤에만 은행에 전송한다. 재조회는 이때 저장한 번호를 쓴다.
            bankMap = channel.transfer(requestMap, reference -> {
                ledger.markSending(id, reference);
                referenceRecorded.set(true);
            });
        } catch (RuntimeException failure) {
            bankMap = SharedMap.<String, Object>of()
                    .add("acqResultCd", referenceRecorded.get() ? WalletPolicy.NO_RESPONSE : WalletPolicy.NOT_SENT)
                    .add("acqResultMsg", referenceRecorded.get() ? "기관 결과 확인 중" : "전송 전 실패");
        }

        // 전송 전 실패는 접수 상태에서만 취소한다. 전송 후 응답이 불명확하면 모계좌 예약을 유지한다.
        String code = bankMap.getString("acqResultCd");
        String status;
        if (!referenceRecorded.get()) {
            status = "실패";
        } else if (code.isBlank() || code.startsWith("X")) {
            status = "미확인";
        } else {
            status = WalletPolicy.SUCCEEDED.equals(code) ? "성공" : "실패";
        }

        return ledger.finish(id, status, code, bankMap.getString("acqResultMsg"), !referenceRecorded.get());
    }

    /** 임의 출금은 WX_ADM에서 재인증을 마친 요청의 값만 사용하고 승인 정보를 한 번 소비한다. */
    @SuppressWarnings("unchecked")
    private static SharedMap<String, Object> approvedManual(String id) {

        if (!id.matches("[A-Za-z0-9_-]{16,40}")) {
            throw new WalletException("W001", "출금 요청번호를 확인해 주세요.");
        }
        Object saved = RedisUtils.getAndDelete(MANUAL_APPROVAL + id);
        if (!(saved instanceof SharedMap<?, ?> approvalMap) || !approvalMap.isEquals("id", id)
                || !approvalMap.isEquals("kind", "manual")) {
            throw new WalletException("W002", "임의 출금에 대한 2차 인증이 필요합니다.");
        }
        return (SharedMap<String, Object>) approvalMap;
    }

    /** TASK의 기존 송금 재조회 주기에서 출금 원거래번호를 확인한다. 재송금은 하지 않는다. */
    public SharedMap<String, Object> recover(int limit) {

        List<SharedMap<String, Object>> rowsList = ledger.pending(limit);
        int                             checked  = 0;
        int                             settled  = 0;
        for (SharedMap<String, Object> rowMap : rowsList) {
            String id = rowMap.getString("id");
            try {
                if (!ledger.claimRecovery(id, rowMap.getString("status"))) {
                    continue;
                }
                checked++;
                // 접수 만료는 전송 전인 경우에만 원복하고, 처리중인 건은 은행 원거래를 조회한다.
                if (rowMap.isEquals("status", "접수")) {
                    String status = ledger.finish(id, "실패", "W005", "기관 전송 전 접수 만료", true);
                    if ("실패".equals(status)) {
                        settled++;
                    }
                    continue;
                }
                SharedMap<String, Object> resultMap = channel(rowMap).transferResult(rowMap.getString("bankTrxId"));
                String                    code      = resultMap.getString("resultCd");
                if (!resultMap.isEquals("acqResultCd", WalletPolicy.SUCCEEDED) || code.isBlank() || code.startsWith("X")) {
                    continue;
                }
                String status = WalletPolicy.SUCCEEDED.equals(code) ? "성공" : "실패";
                ledger.finish(id, status, code, resultMap.getString("resultMsg"), false);
                settled++;
            } catch (RuntimeException | SQLException failure) {
                logger.info("본사 출금 재조회 미완료: id={}, 오류={}", id, failure.getClass().getSimpleName());
            }
        }

        return SharedMap.<String, Object>of()
                .add("checked", checked)
                .add("settled", settled)
                .add("requeued", checked - settled);
    }

    /** 출금 접수 시 고정한 기관·계좌에 해당하는 송금 채널을 연다. */
    private static HyphenSvc channel(SharedMap<String, Object> rowMap) {

        if (!rowMap.isEquals("acq", "HYPHEN")) {
            throw new WalletException("W003", "모계좌 기관 설정을 확인해 주세요.");
        }
        SharedMap<String, Object> profileMap = rowMap.copy()
                .add("upstream.enabled", true)
                .add("env", WalletConfig.upstreamEnv());
        return new HyphenSvc(profileMap);
    }
}
