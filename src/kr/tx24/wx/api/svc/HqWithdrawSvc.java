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
    public SharedMap<String, Object> submit(SharedMap<String, Object> in, String trace) throws Exception {

        if (!WalletConfig.upstreamReady()) throw new WalletException("W003","기관 송금 경로가 준비되지 않았습니다.");
        if (in.isEquals("kind", "manual")) in = approvedManual(in.getString("id"));
        boolean fee = in.isEquals("kind","fee");
        if ((!fee && !in.isEquals("kind","manual")) || !in.getString("id").matches("[A-Za-z0-9_-]{16,40}")
                || !in.getString("amount").matches("[1-9][0-9]{0,11}") || in.isBlank("userId") || in.isBlank("reason")
                || in.getString("reason").length()>128 || !in.getString("settingRev").matches("[0-9]{1,10}")
                || (fee && (in.isBlank("mchtId") || !in.getString("baseDay").matches("[0-9]{8}")))
                || (!fee && (!in.isBlank("mchtId") || in.getInt("baseDay") != 0))) {
            throw new WalletException("W001","출금 요청값을 확인해 주세요.");
        }
        String id = in.getString("id");
        SharedMap<String, Object> accepted = ledger.accept(in);
        String status = accepted.getString("status");
        if (accepted.getBoolean("send")) {
            AtomicBoolean marked = new AtomicBoolean();
            SharedMap<String, Object> bank;
            try {
                bank = channel(accepted).transfer(SharedMap.<String, Object>of()
                        .add("amount", in.getString("amount"))
                        .add("recvBankCd", accepted.getString("bankCd"))
                        .add("recvAccount", accepted.getString("destination"))
                        .add("sender", accepted.getString("sender"))
                        .add("description", accepted.getString("holder")), ref -> {
                            ledger.markSending(id, ref);
                            marked.set(true);
                        });
            } catch (RuntimeException failure) {
                bank = SharedMap.<String, Object>of()
                        .add("acqResultCd", marked.get() ? WalletPolicy.NO_RESPONSE : WalletPolicy.NOT_SENT)
                        .add("acqResultMsg", marked.get() ? "기관 결과 확인 중" : "전송 전 실패");
            }
            String code = bank.getString("acqResultCd");
            // 원거래번호를 기록한 뒤 불완전한 응답이 오면 예약을 유지한다.
            status = !marked.get() ? "실패" : code.isBlank() || code.startsWith("X") ? "미확인"
                    : WalletPolicy.SUCCEEDED.equals(code) ? "성공" : "실패";
            status = ledger.finish(id, status, code, bank.getString("acqResultMsg"),!marked.get());
            if ("미확인".equals(status)) {
                NotificationUtils.incident("TRX_HQ_WITHDRAW", id,
                        "[WX] 본사 모계좌 출금 기관 응답 미확인. 출금번호 " + id + ". 출금 이력에서 확인해 주세요.");
            }
        }
        return WalletResult.of("0000","모계좌 출금 "+status, trace).add("id", id).add("status", status);
    }

    /** 임의 출금은 WX_ADM에서 재인증을 마친 요청의 값만 사용하고 승인 정보를 한 번 소비한다. */
    @SuppressWarnings("unchecked")
    private static SharedMap<String, Object> approvedManual(String id) {

        if (!id.matches("[A-Za-z0-9_-]{16,40}")) throw new WalletException("W001", "출금 요청번호를 확인해 주세요.");
        Object saved = RedisUtils.getAndDelete(MANUAL_APPROVAL + id);
        if (!(saved instanceof SharedMap<?, ?> approval) || !approval.isEquals("id", id)
                || !approval.isEquals("kind", "manual")) {
            throw new WalletException("W002", "임의 출금에 대한 2차 인증이 필요합니다.");
        }
        return (SharedMap<String, Object>) approval;
    }

    /** TASK의 기존 송금 재조회 주기에서 출금 원거래번호를 확인한다. 재송금은 하지 않는다. */
    public SharedMap<String, Object> recover(int limit) {

        List<SharedMap<String, Object>> rows = ledger.pending(limit);
        int checked = 0;
        int settled = 0;
        for (SharedMap<String, Object> row : rows) {
            String id = row.getString("id");
            try {
                if (!ledger.claimRecovery(id, row.getString("status"))) continue;
                checked++;
                if (row.isEquals("status","접수")) {
                    if ("실패".equals(ledger.finish(id,"실패","W005","기관 전송 전 접수 만료", true))) settled++;
                    continue;
                }
                SharedMap<String, Object> result = channel(row).transferResult(row.getString("bankTrxId"));
                String code = result.getString("resultCd");
                if (!result.isEquals("acqResultCd", WalletPolicy.SUCCEEDED) || code.isBlank() || code.startsWith("X")) continue;
                ledger.finish(id, WalletPolicy.SUCCEEDED.equals(code) ? "성공" : "실패", code, result.getString("resultMsg"), false);
                settled++;
            } catch (RuntimeException | SQLException failure) {
                logger.warn("본사 출금 재조회 미완료: id={}, 오류={}", id, failure.getClass().getSimpleName());
            }
        }
        return SharedMap.<String, Object>of()
                .add("checked", checked)
                .add("settled", settled)
                .add("requeued", checked-settled);
    }

    /** 출금 접수 시 고정한 기관·계좌에 해당하는 송금 채널을 연다. */
    private static HyphenSvc channel(SharedMap<String, Object> row) {

        if (!row.isEquals("acq","HYPHEN")) throw new WalletException("W003","모계좌 기관 설정을 확인해 주세요.");
        return new HyphenSvc(row.copy().add("upstream.enabled", true).add("env", WalletConfig.upstreamEnv()));
    }
}
