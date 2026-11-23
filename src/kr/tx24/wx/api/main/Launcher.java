package kr.tx24.wx.api.main;

import kr.tx24.inet.conf.INetConfigLoader;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletZone;
import kr.tx24.wx.api.rps.WalletLedgerRps;

import java.nio.file.Files;

/**
 * 설정과 원장 연결을 확인하는 진단 도구다.
 *
 * <p>운영 기동에는 쓰지 않는다. WX_API 는 OPL_API 와 같이 범용
 * {@code kr.tx24.inet.server.INetServer} 로 뜨고, 업무 설정은 {@link WalletConfig} 가
 * 컨트롤러 적재 시점에 읽는다. 이 클래스는 배포 전후에 사람이 직접 부르는 점검 경로다.</p>
 *
 * <pre>java -DPROC=WX_API -DCONF=&lt;conf&gt; -cp WX_API.jar kr.tx24.wx.api.main.Launcher --check</pre>
 */
public final class Launcher {

    /** 설정·원장·업무 스키마를 확인한다. 수신 포트는 열지 않는다. */
    public static void main(String[] args) {

        try {
            run(args);
        } catch (Exception failure) {
            System.err.println("WX_API 설정 또는 실행 오류: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    /** 지정한 점검을 수행한다. */
    private static void run(String[] args) throws Exception {

        if (args.length != 1 || !"--check".equals(args[0])) {
            throw new IllegalArgumentException("사용법: Launcher --check");
        }
        if (!Files.isRegularFile(SystemUtils.getINetConfigPath())) {
            throw new IllegalStateException("conf/inet.json 설정이 필요합니다.");
        }
        System.setProperty("PROC", "WX_API");
        INetConfigLoader.start();

        // 설정을 읽고(정적 초기화) 기동 로그와 같은 경고를 그대로 남긴다.
        if (!WalletConfig.enabled()) throw new IllegalStateException("WX_API가 비활성입니다.");
        int port = INetConfigLoader.getPort();
        if (port < 1 || port > 65535) throw new IllegalStateException("WX_API INet 포트를 확인하세요.");

        // 원장은 이 프로세스가 가진다. 자기 DB 연결과 업무 스키마를 확인한다.
        new WalletLedgerRps().health();
        WalletZone.warmUp();
        System.out.println("WX_API database configuration OK");
        System.exit(0);
    }
}
