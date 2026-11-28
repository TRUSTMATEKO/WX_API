package kr.tx24.wx.ledger.rps;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.lang.IDUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.lib.redis.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.tx24.wx.ledger.cst.LedgerKeys;

import static kr.tx24.wx.ledger.rps.WxDatabaseRps.rows;
import static kr.tx24.wx.ledger.rps.WxDatabaseRps.update;

/**
 * 고객사 웹훅을 발송 대기로 적재하고 발송 큐에 올린다.
 *
 * <p><b>적재는 업무 확정과 같은 트랜잭션에서 한다.</b> 송금 결과를 확정하면서 발송 대기 행을 함께
 * 쓰므로, 확정은 되었는데 통지가 누락되는 상태가 생기지 않는다. 반대로 확정이 롤백되면 통지도 사라진다.
 * 적재한 id 는 {@link LedgerKeys#WEBHOOK_QUEUE} 에 올리고, 실제 발송은 WX_TASK 가 한다.</p>
 *
 * <p><b>이 프로세스는 고객사 수신처로 직접 보내지 않는다.</b> 외부 수신처가 느리거나 죽어 있으면
 * 원장 프로세스의 작업 스레드가 묶이고, 고객사 API 응답까지 느려진다. 그래서 OPL_API·OPL_TASK 와
 * 같이 적재(DB + Redis)와 발송(작업기)을 나눴다. 재시도 간격과 상한, 발송 결과 기록은 WX_TASK 의
 * {@code WebhookSendTask}·{@code WebhookRps} 가 가진다.</p>
 *
 * <p>중복은 {@code UK_TRX_WEBHOOK_1(hookIdx, dedupKey)}이 막는다. 같은 거래를 두 번 확정하거나
 * 운영자가 이미 확정된 건을 다시 종결해도 수신처별로 한 건만 남는다.</p>
 *
 * <p>전문 본문은 {@code payloadEnc}에 암호문으로 보관한다. 계좌번호와 예금주가 담기므로 평문으로
 * 두지 않는다. 발송 직전에만 복호화한다.</p>
 */
public final class WxWebhookRps {

    private static final Logger logger = LoggerFactory.getLogger(WxWebhookRps.class);

    /** 적재한 id 를 커밋 뒤 큐에 올리기 전까지 들고 있는 자리다. 운영 상 의미는 없다. */
    private static final String QUEUED = "__webhookQueued";

    /** 송금 결과 확정 이벤트. 고객사가 구독하는 유형 이름이다. */
    public static final String TRANSFER_SETTLED = "transfer.settled";

    /**
     * 입금 반영 이벤트.
     *
     * <p>입금이 고객사 Wallet 잔액에 반영된 순간 보낸다. 통지로 받아 자동 매칭한 건과 운영자가
     * 미매칭에서 지정한 건이 같은 이벤트를 쓴다. 고객사는 돈이 들어왔다는 사실만 알면 되고,
     * 그것이 자동이었는지 사람이 지정한 것인지는 알 필요가 없다.</p>
     *
     * <p>미매칭으로 남은 입금은 보내지 않는다. 아직 어느 Wallet 의 돈인지 정해지지 않았다.</p>
     */
    public static final String DEPOSIT_CREDITED = "deposit.credited";

    private WxWebhookRps() {

    }

    /**
     * 확정 결과를 Wallet의 사용 중인 수신처마다 발송 대기로 적재한다.
     *
     * <p>호출자는 업무 트랜잭션을 연 상태여야 한다. 수신처가 없으면 아무것도 적재하지 않는다.
     * 그것은 오류가 아니다. 웹훅을 쓰지 않는 Wallet이다.</p>
     *
     * @param transferMap 확정 결과. {@code walletId}·{@code trxId}가 있어야 한다
     * @return 적재한 수신처 건수
     */
    static int enqueue(Connection db, SharedMap<String, Object> transferMap, String eventType,
            LocalDateTime postedAt) throws SQLException {

        if (db.getAutoCommit()) throw new SQLException("웹훅 적재는 업무 트랜잭션에서 수행합니다.");

        String walletId = transferMap.getString("walletId");
        String trxId    = transferMap.getString("trxId");
        if (walletId.isBlank() || trxId.isBlank()) {
            throw new SQLException("웹훅 적재 대상의 Wallet 과 거래를 확인하세요.");
        }

        List<SharedMap<String, Object>> hooksList = rows(db, """
                SELECT idx,endpoint FROM WX.WALLET_HOOK
                 WHERE walletId=? AND status='사용'
                 ORDER BY idx
                """, walletId);
        if (hooksList.isEmpty()) {
            logger.debug("웹훅 수신처가 없어 적재하지 않는다: walletId={}, trxId={}", walletId, trxId);
            return 0;
        }

        String       dedupKey   = trxId + ":" + eventType;
        String       payload    = WxPostingRps.encryptForLedger(JacksonUtils.get().toJson(body(transferMap, eventType, postedAt)));
        int          day        = WxPostingRps.businessDay(postedAt);
        int          stored     = 0;
        List<String> queuedList = new java.util.ArrayList<>();

        for (SharedMap<String, Object> hookMap : hooksList) {
            String hookId = IDUtils.getUnique("W");
            // 같은 수신처에 같은 이벤트를 두 번 쌓지 않는다. 재확정은 조용히 넘어간다.
            int changed = update(db, """
                    INSERT INTO WX.TRX_WEBHOOK(id,mchtId,walletId,hookIdx,trxId,eventType,dedupKey,endpoint,
                                               payloadEnc,status,generation,attemptCnt,regId,regDay,regDate)
                    VALUES(?,?,?,?,?,?,?,?,?,'대기',0,0,'WX_API',?,?)
                    ON DUPLICATE KEY UPDATE id=id
                    """,
                    hookId, transferMap.get("mchtId"), walletId, hookMap.getLong("idx"),
                    trxId, eventType, dedupKey, hookMap.getString("endpoint"),
                    payload, day, postedAt);
            stored += changed;
            // 새로 적재한 건만 발송 큐에 올린다. 중복(ON DUPLICATE)은 이미 큐를 지나간 건이다.
            if (changed == 1) queuedList.add(hookId);
        }
        // 큐 등록은 커밋 뒤에 한다. 커밋 전에 올리면 작업기가 아직 보이지 않는 행을 집어 버린다.
        // 그래서 적재한 id 만 결과에 담아 두고, 호출자가 커밋한 다음 publish 로 올린다.
        if (!queuedList.isEmpty()) transferMap.put(QUEUED, List.copyOf(queuedList));
        logger.info("웹훅 발송 대기 적재: trxId={}, eventType={}, 수신처={}건, 적재={}건",
                trxId, eventType, hooksList.size(), stored);
        return stored;
    }

    /**
     * 커밋 뒤 발송 큐 등록.
     *
     * <p>{@link #enqueue} 가 담아 둔 id 를 {@link LedgerKeys#WEBHOOK_QUEUE} 에 올린다. 커밋이
     * 끝난 뒤에 불러야 한다. OPL 의 생산자 계약과 같다.</p>
     *
     * <p>큐 등록이 실패해도 확정을 되돌리지 않는다. 적재한 행은 WX_TASK 의 복구 작업이 30초 안에
     * 찾아 큐에 올린다. 큐는 빠른 길이고 원장이 기준이다.</p>
     */
    public static void publish(SharedMap<String, Object> resultMap) {

        Object value = resultMap == null ? null : resultMap.remove(QUEUED);
        if (!(value instanceof List<?> idsList) || idsList.isEmpty()) return;

        try {
            RedisUtils.rpush(LedgerKeys.WEBHOOK_QUEUE, idsList.toArray());
            logger.info("웹훅 발송 큐 등록: count={}", idsList.size());
        } catch (RuntimeException failure) {
            logger.info("웹훅 발송 큐 등록 실패. 복구 작업이 다시 찾는다: count={}, type={}",
                    idsList.size(), failure.getClass().getSimpleName());
        }
    }

    /**
     * 대기 상태를 제외한 발송을 다시 대기로 돌린다.
     *
     * <p>운영자 재전송은 시도 횟수를 0으로 되돌리고 <b>세대를 올린다.</b> 세대는 전송 작업기가
     * 늦게 도착한 옛 시도의 결과로 이 원장을 덮어쓰지 못하게 하는 표식이다.</p>
     *
     * <p><b>Redis 큐에 직접 넣지 않는다.</b> WX_TASK 의 복구 작업이 30초 안에 대기 행을 찾아 큐에
     * 올린다. OPL 의 재전송과 같은 계약이며, 본사 화면이 발송 큐를 직접 다루지 않게 한다.</p>
     *
     * <p>적재 행 자체는 바꾸지 않는다. 전문 본문과 수신처는 확정 당시의 값을 그대로 보낸다.
     * 그 사이에 수신처 주소가 바뀌었다면 운영자가 새 수신처를 등록하고 다시 확정해야 한다.</p>
     *
     * @return 다시 대기로 돌린 건수. 대기 상태이거나 이력이 없으면 0이다
     */
    public static int retry(String id, String actor) throws SQLException {

        try (Connection db = WxDatabaseRps.open()) {
            int changed = update(db, """
                    UPDATE WX.TRX_WEBHOOK
                       SET status='대기',generation=generation+1,attemptCnt=0,sentAt=NULL,
                           httpStatus=NULL,resultMsg=NULL,elapsedMs=0
                     WHERE id=? AND status<>'대기'
                    """, id);
            if (changed == 1) {
                logger.info("웹훅 재발송 예약. 발송 복구 작업이 집어 간다: id={}, actor={}", id, actor);
            } else {
                logger.info("웹훅이 이미 대기 중이거나 이력이 없다: id={}", id);
            }
            return changed;
        }
    }

    /** 고객사 포털의 관리자와 수신처 소속을 확인하고 화면에서 본 발송 세대만 재접수한다. */
    public static boolean retryMerchant(String merchant, String actor, String id, int generation) throws SQLException {

        try (Connection db = WxDatabaseRps.open()) {
            int changed = update(db, """
                    UPDATE WX.TRX_WEBHOOK T
                      JOIN WX.USER U ON U.mchtId=T.mchtId AND U.id=? AND U.unit='mcht' AND U.role=0 AND U.status='사용'
                      JOIN WX.MCHT M ON M.mchtId=T.mchtId AND M.status='사용'
                      JOIN WX.WALLET W ON W.id=T.walletId AND W.mchtId=T.mchtId AND W.ownerType='MCHT'
                      JOIN WX.WALLET_HOOK H ON H.idx=T.hookIdx AND H.walletId=W.id
                       AND H.status='사용' AND BINARY H.endpoint=BINARY T.endpoint
                       SET T.status='대기',T.generation=T.generation+1,T.attemptCnt=0,T.sentAt=NULL,
                           T.httpStatus=NULL,T.resultMsg=NULL,T.elapsedMs=0
                     WHERE T.mchtId=? AND T.id=? AND T.status<>'대기' AND T.generation=?
                    """, actor, merchant, id, generation);
            if (!db.getAutoCommit()) db.commit();
            if (changed == 1) logger.info("고객사 웹훅 재전송 접수: id={}, actor={}", id, actor);
            return changed == 1;
        }
    }

    /** 고객사에 보내는 본문. 계좌번호 평문과 보호 키는 담지 않는다. */
    private static SharedMap<String, Object> body(SharedMap<String, Object> transferMap, String eventType,
            LocalDateTime postedAt) {

        SharedMap<String, Object> outMap = SharedMap.<String, Object>of()
                .add("eventType", eventType)
                .add("trxId", transferMap.getString("trxId"))
                .add("status", transferMap.getString("status"))
                .add("confirmedAt", DateUtils.toString(postedAt));
        for (String name : List.of("trackId", "walletId", "amount", "acqTrxId", "depositName", "sourceType")) {
            if (transferMap.containsKey(name)) outMap.put(name, transferMap.get(name));
        }

        return outMap;
    }
}
