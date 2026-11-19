package kr.tx24.wx.api.main;

import kr.tx24.inet.conf.INetConfigLoader;
import kr.tx24.inet.route.Router;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.ctl.PayoutApi;
import kr.tx24.wx.inet.InternalInetServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class Launcher {

    /** WX_API 전용 INet 라우트를 PC 내부 주소로 시작한다. */
    public static void main(String[] args) throws Exception {

        if (!Files.isRegularFile(Path.of("conf/inet.json"))) {
            throw new IllegalStateException("conf/inet.json 설정이 필요합니다.");
        }
        System.setProperty("PROC", "WX_API");
        SystemUtils.disableDeepView();
        INetConfigLoader.start();
        SharedMap<String, Object> property = INetConfigLoader.getMap("property", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (property == null || !property.getBoolean("enabled")) {
            throw new IllegalStateException("WX_API 시연 수신이 비활성입니다.");
        }
        PayoutApi.configure(property);
        Router.start("kr.tx24.wx.api.ctl");

        int port = INetConfigLoader.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("WX_API INet 포트를 확인하세요.");
        }
        try (var server = new InternalInetServer("127.0.0.1", port, "127.0.0.1")) {
            server.start();
            var released = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(released::countDown));
            System.out.println("WX_API INet 127.0.0.1:" + server.port());
            released.await();
        }
    }
}
