import kr.tx24.lib.map.SharedMap;
import kr.tx24.wx.inet.InternalInetUtils;

public final class VerifyPayout {

    public static void main(String[] args) throws Exception {

        try {
            verify(args);
            System.exit(0);
        } catch (Exception failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        }
    }

    private static void verify(String[] args) throws Exception {

        var input = SharedMap.<String, Object>of()
                .add("requestId", "WX-API-INTEGRATION")
                .add("walletId", "W001")
                .add("amount", "1000")
                .add("recipient", "가상 수취인")
                .add("outcome", "success");
        var first = send(args, input);
        var second = send(args, input);
        if (!first.getBoolean("result") || !second.getBoolean("result")
                || !second.get("data").toString().contains("replayed=true")) {
            throw new IllegalStateException("INet 지급 접수 또는 재접수 확인 실패: " + second);
        }
        input.put("requestId", "WX-API-OTHER-MERCHANT");
        input.put("walletId", "W003");
        if (send(args, input).getBoolean("result")) {
            throw new IllegalStateException("다른 고객사 Wallet의 지급이 접수됐습니다.");
        }
        System.out.println("PASS API INet payout, replay and merchant scope");
    }

    private static SharedMap<String, Object> send(String[] args, SharedMap<String, Object> input) throws Exception {

        return InternalInetUtils.call("127.0.0.1", Integer.parseInt(args[0]), "/api/internal/demo/payout",
                "x-wx-key", args[1], input, "WXAPI001", 5000, 8192);
    }
}
