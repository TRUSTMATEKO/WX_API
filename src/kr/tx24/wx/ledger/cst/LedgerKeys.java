package kr.tx24.wx.ledger.cst;

/**
 * 원장이 쓰는 Redis 키다.
 *
 * <p>WX_ADM의 {@code RedisKey}에서 송금 업무 키만 가져왔다. 세션·CSRF·2차 인증 키는 본사 웹 전용이므로
 * 담지 않는다. 키 문자열은 WX_ADM과 같아야 한다. 같은 큐를 두 프로세스가 본다.</p>
 */
public final class LedgerKeys {

    /** 송금 요청 멱등 키. 같은 고객사의 같은 요청번호를 보관 시간 동안 한 건만 접수한다. */
    public static final String TRANSFER_REQUEST_PREFIX = "WX:TRANSFER:REQ:";

    /**
     * 고객사 웹훅 발송 대기 큐. 적재한 TRX_WEBHOOK 의 id 만 넣는다.
     *
     * <p>발송은 WX_TASK 가 한다. OPL 의 {@code OPL:TASK:HOOK} 과 같은 역할이며, 키 문자열은
     * WX_TASK 의 {@code hook.HookConstants} 와 글자 그대로 같아야 한다. 같은 큐를 두 프로세스가 본다.</p>
     */
    public static final String WEBHOOK_QUEUE           = "WX:TASK:HOOK";

    /** 송금 복구 큐. 멤버는 거래번호이고 점수는 다음 조회 가능 시각이다. */
    public static final String TRANSFER_RECOVERY_QUEUE = "WX:TRANSFER:RECOVERY";

    /** 복구 큐 누락 확인을 여러 WX_API 인스턴스에서 동시에 실행하지 않도록 한다. */
    public static final String TRANSFER_RECOVERY_SCAN = "WX:TRANSFER:RECOVERY:SCAN";

    private LedgerKeys() {

    }

    public static String transferRequest(String merchant, String trackId) {

        return TRANSFER_REQUEST_PREFIX + merchant + ":" + trackId;
    }
}
