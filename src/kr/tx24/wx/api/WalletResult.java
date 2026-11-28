package kr.tx24.wx.api;

import com.fasterxml.jackson.databind.node.NullNode;
import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.map.TypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Wallet 업무 결과와 예외를 고객사 응답으로 바꾸는 단일 지점이다.
 *
 * <p>OPL_API의 {@code EwalletResult}와 같은 자리다. 응답 코드와 문구를 여기 말고
 * 다른 곳에서 만들지 않는다.</p>
 */
public final class WalletResult {

    private static final Logger logger = LoggerFactory.getLogger(WalletResult.class);

    private WalletResult() {

    }

    /**
     * Wallet 업무 응답은 코드, 간략·상세 메시지와 응답 시각을 공통 제공한다.
     *
     * <p>{@code trace}는 응답에 넣지 않는다. 로그를 엮는 데만 쓴다. 고객사는 주문번호({@code trackId})와
     * WX 거래번호({@code trxId})로 거래를 가리키므로 별도 추적번호가 필요 없다.</p>
     */
    public static SharedMap<String, Object> of(String code, String advanceMsg, String trace) {

        String resultMsg = switch (code) {
            case "0000" -> "정상";
            case "W001" -> "요청값 또는 형식 오류";
            case "W002" -> "인증실패";
            case "W003" -> "운영중지시간";
            case "W004" -> "거래 조건 미충족";
            case "W005" -> "기관 전송 전 오류";
            case "W009" -> "조회 대상 거래 없음";
            case "W010" -> "거래 처리 실패";
            case "W011" -> "중복 요청";
            case "W012" -> "출금 한도 초과";
            case "W013" -> "API 키 연결 오류";
            case "W014" -> "Wallet 사용 중지";
            case "W015" -> "출금가능잔액 부족";
            case "W016" -> "거래 접수 불가";
            case "W00X" -> "통신장애";
            case "W999" -> "시스템 내부 오류";
            case "X000" -> "통신장애";
            default -> "기관 요청 처리 실패";
        };

        return SharedMap.<String, Object>of()
                .add("resultCd", code)
                .add("resultMsg", resultMsg)
                .add("advanceMsg", advanceMsg)
                .add("createAt", DateUtils.toString(DateUtils.getZonedDate(WalletPolicy.SEOUL)));
    }

    /** 업무 예외는 지정한 코드로 그대로 응답한다. */
    public static SharedMap<String, Object> of(WalletException failure, String trace) {

        logger.info("Wallet 업무 실패: resultCd={}, advanceMsg={}, trace={}",
                failure.getResultCd(), failure.getAdvanceMsg(), trace);
        return of(failure.getResultCd(), failure.getAdvanceMsg(), trace);
    }

    /** 코드를 모르는 실패는 원문 없이 시스템 오류로만 응답한다. */
    public static SharedMap<String, Object> of(Exception failure, String trace) {

        logger.info("Wallet 처리 오류: type={}, trace={}", failure.getClass().getSimpleName(), trace);
        logger.debug("Wallet 처리 예외 상세: trace={}", trace, failure);
        return of("W999", "요청을 처리하지 못했습니다.", trace);
    }

    /** 내부 원장 결과를 TX24 Wallet의 업무 응답으로 변환한다. */
    public static SharedMap<String, Object> ledger(String action, SharedMap<String, Object> internalMap, String trace) {

        if (internalMap.getBoolean("result")) {
            SharedMap<String, Object> dataMap  = internalMap.getMap("data", TypeRegistry.MAP_SHAREDMAP_OBJECT);
            SharedMap<String, Object> replyMap = of("0000", "정상조회", trace);
            if (dataMap != null) {
                if (action.equals("balance")) {
                    copyFields(dataMap, replyMap, Set.of("balance", "availableBalance"));
                } else if (action.equals("holder")) {
                    copyFields(dataMap, replyMap, Set.of("trackId", "bankCd", "accntNo", "holder"));
                } else if (!dataMap.isBlank("trxId")) {
                    if (action.equals("inquiry")) {
                        copyFields(dataMap, replyMap, Set.of("rootResultCd", "rootResultMsg"));
                    } else if (action.equals("payout")) {
                        replyMap = of(dataMap.getString("rootResultCd", "W00X"),
                                dataMap.getString("rootResultMsg", "결과 확인 중"), trace);
                    }
                    for (String name : WalletPolicy.TRANSFER_RESULT_FIELDS) {
                        replyMap.put(name, dataMap.getOrDefault(name, NullNode.getInstance()));
                    }
                    addTransferFee(replyMap, dataMap.getLong("feeAmt"));
                }
            }

            return replyMap;
        }

        // 원장이 돌린 문구를 그대로 전한다. LedgerEx 의 문구는 고객사에 보여 주려고 쓴 공개 문구다.
        //
        // 전에는 여기서 모두 버리고 코드별 고정 문구로 바꿔, 중복 요청번호와 한도 초과가 동일하게
        // "API 키에 연결된 Wallet 설정을 확인해 주세요." 로 나갔다. 고객사가 원인을 가릴 수 없었다.
        //
        // 인증과 접속 IP 실패만 가린다. 어떤 조건에서 막혔는지 알려 주지 않는다.
        String reason = internalMap.getString("message");
        return switch (internalMap.getString("code")) {
            case "TX400" -> of("W001", reason.isBlank() ? "요청값을 확인해 주세요." : reason, trace);
            case "TX401", "TX403" -> of("W002", "Wallet 인증 또는 접속 IP 확인에 실패했습니다.", trace);
            case "TX404" -> of("W009", reason.isBlank() ? "거래를 찾을 수 없습니다." : reason, trace);
            case "TX409" -> of(internalMap.getString("walletCode", "W004"),
                    reason.isBlank() ? "요청을 처리하지 못했습니다." : reason, trace);
            case "TX503" -> of("W003", reason.isBlank() ? "요청 접수가 중지되었습니다." : reason, trace);
            default -> of("W999", "원장 응답을 확인해 주세요.", trace);
        };
    }

    /** 송금 및 결과 조회에 건별 공급가액과 WX_TASK의 원 단위 반올림을 적용한 부가세, 합계를 담는다. */
    public static void addTransferFee(SharedMap<String, Object> replyMap, long feeAmt) {

        long feeVat = feeAmt / 10 + (feeAmt % 10 >= 5 ? 1 : 0);
        replyMap.add("feeAmt", feeAmt)
                .add("feeVat", feeVat)
                .add("fee", Math.addExact(feeAmt, feeVat));
    }

    /** Wallet 업무 응답은 엔드포인트에 허용된 원장 필드만 복사한다. */
    private static void copyFields(SharedMap<String, Object> dataMap, SharedMap<String, Object> replyMap,
                                    Set<String> names) {

        for (String name : names) {
            if (dataMap.containsKey(name)) replyMap.put(name, dataMap.get(name));
        }
    }
}
