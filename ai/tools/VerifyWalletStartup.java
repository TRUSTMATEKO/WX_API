import kr.tx24.inet.conf.INetConfigLoader;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletZone;
import kr.tx24.wx.api.rps.WalletLedgerRps;

/** 수신 포트를 열지 않고 WX_API 설정과 원장 연결을 확인한다. */
public final class VerifyWalletStartup {

    public static void main(String[] args) {

        try {
            INetConfigLoader.start();
            WalletConfig.initialize();

            new WalletLedgerRps().health();
            WalletZone.warmUp();
        } catch (ExceptionInInitializerError failure) {
            Throwable cause = failure.getCause();
            String message = cause instanceof IllegalStateException ? cause.getMessage() : "기동 설정 초기화 실패";
            System.err.println("WX_API 점검 실패. " + message);
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("WX_API 원장 또는 설정 점검 실패. " + failure.getClass().getSimpleName());
            System.exit(1);
        }

        System.out.println("WX_API database configuration OK");
        System.exit(0);
    }
}
