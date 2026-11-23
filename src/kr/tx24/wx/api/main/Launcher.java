package kr.tx24.wx.api.main;

import kr.tx24.wx.api.ctl.WalletRestApi;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import kr.tx24.inet.conf.INetConfigLoader;
import kr.tx24.inet.route.Router;
import kr.tx24.inet.server.INetServer;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.ctl.MerchantApi;
import kr.tx24.wx.api.ctl.HqWalletApi;
import kr.tx24.wx.api.ctl.AdminLedgerApi;
import kr.tx24.wx.api.ctl.TransferApi;
import kr.tx24.wx.api.ctl.DepositApi;
import kr.tx24.wx.api.WalletConfig;
import kr.tx24.wx.api.WalletZone;
import kr.tx24.wx.api.rps.WalletLedgerRps;
import kr.tx24.wx.api.hyphen.HyphenPisp;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

public final class Launcher {

    /** WX_API 전용 INet 라우트를 PC 내부 주소로 시작한다. */
    public static void main(String[] args) {

        try {
            run(args);
        } catch (Exception failure) {
            System.err.println("WX_API 설정 또는 실행 오류: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    /** 런처 설정과 지정한 실행 모드를 적용한다. */
    private static void run(String[] args) throws Exception {

        boolean check = args.length == 1 && "--check".equals(args[0]);
        if (!check && args.length != 0) throw new IllegalArgumentException("지원되는 옵션은 --check입니다.");
        if (!Files.isRegularFile(SystemUtils.getINetConfigPath())) {
            throw new IllegalStateException("conf/inet.json 설정이 필요합니다.");
        }
        System.setProperty("PROC", "WX_API");
        SystemUtils.disableDeepView();
        // 기관 원본은 전문 본문을 기록하므로 이 로거를 끄고 호출부의 처리 결과 로그만 남긴다.
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(HyphenPisp.class.getName()).setLevel(Level.OFF);
        INetConfigLoader.start();
        SharedMap<String, Object> property = INetConfigLoader.getMap("property", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (property == null || !property.getBoolean("enabled")) {
            throw new IllegalStateException("WX_API가 비활성입니다.");
        }
        // 업무 계층은 요청마다 새로 만들어지므로 실행 설정을 여기서 한 번 확정한다.
        WalletConfig.configure(property);
        // 루프백 REST 는 격리 검증에서만 쓰는 진단 수신부다. 운영 설정에는 rest.port 를 두지 않아
        // 0 으로 읽히고 열리지 않는다. ai/tools/verify-isolated.ps1 이 임시 설정에 포트와 키를 넣는다.
        int restPort = property.getInt("rest.port");
        int port = INetConfigLoader.getPort();
        if (port < 1 || port > 65535 || restPort < 0 || restPort > 65535 || port == restPort) {
            throw new IllegalStateException("서로 다른 API 및 REST 포트를 확인하세요.");
        }
        if (check) {
            // 원장은 이 프로세스가 가진다. 자기 DB 연결과 업무 스키마를 확인한다.
            new WalletLedgerRps().health();
            WalletZone.warmUp();
            System.out.println("WX_API database configuration OK");
            System.exit(0);
            return;
        }
        String host = INetConfigLoader.getHost();
        String peers = property.getString("gateway.allowedIps","127.0.0.1");
        // 원장과 차단 목록을 먼저 확인한다. 설정이 잘못되었으면 열지 않고 멈춘다.
        new WalletLedgerRps().health();
        WalletZone.warmUp();
        MerchantApi.configure(peers);
        TransferApi.configure(property);
        HqWalletApi.configure(property);
        AdminLedgerApi.configure(property);
        DepositApi.configure(property);
        // 수신부는 공통 INet 서버 하나다. 업무 경로는 Router 가 endpoint 로 나누고, 허용 주소와 키는
        // 경로마다 다르다. 고객사 경로는 TX24_HTTP 주소(gateway.allowedIps)와 고객사 키를, 내부
        // 경로는 internal.allowedIps 와 internal_api.key 를 쓴다. Router 를 여기서 먼저 올려
        // 기동 설정이 끝난 뒤에만 경로가 열리게 한다.
        Router.start("kr.tx24.wx.api.ctl");

        try (WalletRestApi rest = property.getInt("rest.port") > 0 ? new WalletRestApi(property) : null) {
            System.out.println("WX_API INet " + host + ":" + port);
            // 공통 서버는 수신 채널이 닫힐 때까지 이 스레드를 잡고 있다가 스스로 정리한다.
            new INetServer().start();
        }
    }
}
