package kr.tx24.wx.api.hyphen;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.redis.RedisUtils;
import kr.tx24.wx.api.utils.ExtCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 시험 환경에서 기관 대신 응답하는 더미 전문이다.
 *
 * <p>{@code env}가 {@code test}이면 {@link kr.tx24.wx.api.svc.HyphenSvc}가 실제 소켓을 열지
 * 않고 이 클래스를 부른다. 기관 개발망에 붙지 않고도 접수부터 확정, 잔액 반영, 웹훅까지 전체 흐름을
 * 끝까지 돌려 보기 위한 것이다. {@code prod}에서는 한 줄도 실행되지 않는다.</p>
 *
 * <p>응답 모양은 {@link HyphenPisp}의 실제 응답과 같은 열쇠를 쓴다. 업무 계층이 시험과 운영에서
 * 다른 코드를 타지 않아야 시험의 뜻이 있다.</p>
 *
 * <h2>모계좌 잔액</h2>
 * <p>시험에서는 은행에 물어볼 잔액이 없으므로 Redis에 모계좌별 잔액을 하나 두고 송금한 만큼 줄인다.
 * 처음 쓰는 모계좌는 {@value #SEED_BALANCE}원으로 시작한다. 잔액이 모자라면 실패로 응답해
 * 선차감한 Wallet 잔액이 원복되는 경로까지 확인할 수 있다.</p>
 *
 * <p>이 값은 시험 전용이며 {@code HQ_ACCNT.balance}와 맞추지 않는다. 원장 잔액은 DB가 가진다.
 * 여기 잔액은 기관이 들고 있는 돈을 대승한 값이다.</p>
 */
public final class HyphenStub {

    private static final Logger logger = LoggerFactory.getLogger(HyphenStub.class);

    /** 시험 모계좌의 시작 잔액. 원화 정수다. */
    public static final long SEED_BALANCE = 100_000_000L;

    /** 시험 전용 Redis 키. 운영 일련번호 {@code WX:HYPHEN:<hqId>}와 겹치지 않게 나눈다. */
    private static final String BALANCE_KEY = "WX:HYPHEN:TEST:BALANCE:";
    private static final String SEQUENCE_KEY = "WX:HYPHEN:TEST:SEQ:";

    /**
     * 통신장애를 일부러 일으키는 시험 스위치.
     *
     * <p>이 키가 있으면 더미 송금이 기관 무응답을 돌린다. 원거래번호를 먼저 기록한 다음에
     * 무응답하므로 실제 통신장애와 같은 상황이 된다. 업무는 차감을 유지한 상태에서 미확정으로
     * 두고, 재조회와 운영자 종결 경로를 그대로 밟을 수 있다.</p>
     *
     * <p>키를 지우면 다시 정상 시험으로 돌아온다. prod 에서는 이 키를 보지 않는다.</p>
     */
    private static final String NO_RESPONSE_KEY = "WX:HYPHEN:TEST:NORESPONSE";

    /** 기관이 응답하지 않은 경우의 코드. 업무는 이 코드를 미확정으로 다룬다. */
    private static final String NO_RESPONSE = "X000";

    /** 시추 잔액을 넣는 동시 호출을 한 번으로 줄이는 짧은 잠금. */
    private static final String SEEDING_KEY = "WX:HYPHEN:TEST:SEEDING:";
    private static final long SEEDING_TTL = 10;

    /** 기관 일련번호 상한. 실제 전문과 같이 여섯 자리를 넘기면 1로 돌린다. */
    private static final long SEQUENCE_LIMIT = 999999L;

    /** 기관 정상 처리 코드. */
    private static final String SUCCEEDED = "0000";

    /** 모계좌 잔액이 모자랄 때 돌려줄 기관 코드. PISP의 송금이체 잔액부족이다. */
    private static final String NOT_ENOUGH = "KS11";

    /** 시험 예금주 조회가 돌려주는 이름. 실제 사람 이름을 쓰지 않는다. */
    private static final String TEST_HOLDER = "시험예금주";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private HyphenStub() {

    }

    /**
     * 더미 송금.
     *
     * <p>실제 전송과 순서를 맞춘다. 기관 원거래번호를 먼저 기록하고 그다음 잔액을 줄인다.
     * 원거래번호 기록은 통신장애 재조회의 유일한 열쇠여서, 시험에서도 같은 자리에서 불러야
     * 재조회 경로가 같은 전제로 돌아간다.</p>
     *
     * @param hqId             모계좌 ID. 잔액과 일련번호를 이 단위로 나눈다
     * @param amount           송금 원금
     * @param recordBeforeSend 전송 직전 기관 원거래번호 기록 콜백
     */
    public static SharedMap<String, Object> transfer(String hqId, long amount,
            Consumer<String> recordBeforeSend) {

        LocalDateTime now = LocalDateTime.now(SEOUL);
        SharedMap<String, Object> reply = head(hqId, now);
        recordBeforeSend.accept(reply.getString("acqTrxId"));

        if (RedisUtils.get(NO_RESPONSE_KEY) != null) {
            // 실제 통신장애와 같이 잔액은 줄이지 않는다. 기관이 처리했는지 알 수 없기 때문이다.
            logger.warn("[시험] 통신장애 스위치가 켜져 있어 무응답을 돌린다: hqId={}, amount={}, acqTrxId={}",
                    hqId, amount, reply.getString("acqTrxId"));
            return reply
                    .add("acqResultCd", NO_RESPONSE)
                    .add("acqResultMsg", "통신장애, 결과 확인 중")
                    .add("balance", 0L)
                    .add("fee", 0L);
        }

        String key = BALANCE_KEY + hqId;
        seed(key);

        Long after = RedisUtils.decrBy(key, amount);
        long remain = after == null ? 0L : after;
        if (remain < 0) {
            // 줄인 만큼 되돌린다. 시험이라도 잔액이 음수로 남으면 다음 시험이 어그러진다.
            RedisUtils.incrBy(key, amount);
            logger.warn("[시험] 모계좌 잔액 부족으로 송금을 실패로 응답한다: hqId={}, amount={}, 잔액={}",
                    hqId, amount, remain + amount);
            return reply
                    .add("acqResultCd", NOT_ENOUGH)
                    .add("acqResultMsg", ExtCode.getPISP(NOT_ENOUGH))
                    .add("balance", remain + amount)
                    .add("fee", 0L);
        }

        logger.info("[시험] 더미 송금 성공: hqId={}, amount={}, 남은 잔액={}, acqTrxId={}",
                hqId, amount, remain, reply.getString("acqTrxId"));
        return reply
                .add("acqResultCd", SUCCEEDED)
                .add("acqResultMsg", "정상이체")
                .add("balance", remain)
                .add("fee", 0L);
    }

    /** 더미 예금주 조회. 계좌 형식만 맞으면 늘 같은 시험 이름을 돌려준다. */
    public static SharedMap<String, Object> holder(String hqId, String bankCd, String accntNo) {

        logger.info("[시험] 더미 예금주 조회: bankCd={}, 계좌 자릿수={}", bankCd, accntNo.length());
        return head(hqId, LocalDateTime.now(SEOUL))
                .add("acqResultCd", SUCCEEDED)
                .add("acqResultMsg", "정상조회")
                .add("holder", TEST_HOLDER);
    }

    /**
     * 더미 송금 결과 조회.
     *
     * <p>시험에서는 송금이 늘 즉시 확정되므로 재조회가 생기지 않는다. 그래도 운영자가 통신장애 건을
     * 만들어 두고 재조회 경로를 밟아 볼 수 있어야 하므로 성공으로 답한다.</p>
     */
    public static SharedMap<String, Object> transferResult(String hqId, String acqTrxId) {

        logger.info("[시험] 더미 송금 결과 조회: acqTrxId={}", acqTrxId);
        SharedMap<String, Object> reply = head(hqId, LocalDateTime.now(SEOUL));
        // 조회는 원거래에 대해 답하는 것이다. 조회 전문 자신의 일련번호가 아니라 받은 원거래번호를 돌려주다.
        reply.put("acqTrxId", acqTrxId);
        return reply
                .add("acqResultCd", SUCCEEDED)
                .add("acqResultMsg", "정상조회")
                .add("resultCd", SUCCEEDED)
                .add("resultMsg", "정상이체")
                .add("fee", 0L);
    }

    /** 더미 모계좌 잔액 조회. Redis가 들고 있는 시험 잔액을 돌려준다. */
    public static SharedMap<String, Object> balance(String hqId) {

        String key = BALANCE_KEY + hqId;
        seed(key);

        Object stored = RedisUtils.get(key);
        long amount = stored == null ? 0L : Long.parseLong(String.valueOf(stored).trim());
        logger.info("[시험] 더미 모계좌 잔액 조회: hqId={}, 잔액={}", hqId, amount);
        return head(hqId, LocalDateTime.now(SEOUL))
                .add("acqResultCd", SUCCEEDED)
                .add("acqResultMsg", "정상조회")
                .add("balance", amount);
    }

    /**
     * 모든 더미 응답이 함께 쓰는 거래 식별부를 만든다.
     *
     * <p>{@code acqTrxId}는 실제 전문과 같이 거래일 여섯 자리와 일련번호 여섯 자리를 이어 붙인
     * 열두 자리다. 재조회가 이 형식을 요구하므로 시험에서도 같게 만든다.</p>
     */
    private static SharedMap<String, Object> head(String hqId, LocalDateTime now) {

        String day = now.format(DAY);
        String time = now.format(TIME);
        return SharedMap.<String, Object>of()
                .add("acqTrxId", day.substring(2) + sequence(hqId))
                .add("acqTrxDay", day)
                .add("acqTrxTime", time)
                .add("acqTrxDate", day + time);
    }

    /**
     * 시험 잔액을 없으면 만들어 둔다.
     *
     * <p>{@code set} 계열로 넣지 않는다. TX24_LIB 는 값을 JSON 으로 직렬화해 {@code "100000000"}
     * 으로 저장하고, 그러면 Redis 의 {@code DECRBY} 가 정수로 읽지 못해 거절한다. 그래서
     * {@code INCRBY} 로 잔액을 만들고, 여러 송금이 동시에 첫 잔액을 넣지 않게 짧은 잠금을 두었다.</p>
     *
     * <p>잔액 키를 지우면 다시 {@value #SEED_BALANCE}원으로 시작한다. 시험 잔액을 돌리는 방법이다.</p>
     */
    private static void seed(String key) {

        if (RedisUtils.get(key) != null) return;
        if (!RedisUtils.setNx(SEEDING_KEY + key, "1", SEEDING_TTL)) return;
        RedisUtils.incrBy(key, SEED_BALANCE);
        logger.info("[시험] 모계좌 시험 잔액을 {}원으로 시작한다: key={}", SEED_BALANCE, key);
    }

    /** 모계좌별 시험 일련번호. 운영과 같이 여섯 자리로 돌려 쓴다. */
    private static String sequence(String hqId) {

        String key = SEQUENCE_KEY + hqId;
        Long next = RedisUtils.incr(key);
        long value = next == null ? 1L : next;
        if (value >= SEQUENCE_LIMIT) {
            // set 으로 다시 넣으면 JSON 문자열로 저장되어 다음 INCR 가 거절된다. 지우고 다시 연다.
            RedisUtils.del(key);
            Long restarted = RedisUtils.incr(key);
            value = restarted == null ? 1L : restarted;
        }
        return String.format("%06d", value);
    }
}
