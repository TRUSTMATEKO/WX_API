package kr.tx24.wx.api.main;

import kr.tx24.inet.conf.INetConfigLoader;
import kr.tx24.inet.route.Router;
import kr.tx24.lib.map.SharedMap;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.wx.api.ctl.PayoutApi;
import kr.tx24.wx.api.ctl.MerchantApi;
import kr.tx24.wx.inet.InternalInetServer;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

public final class Launcher {

    /** WX_API 전용 INet 라우트를 PC 내부 주소로 시작한다. */
    public static void main(String[] args) throws Exception {

        if (!Files.isRegularFile(SystemUtils.getINetConfigPath())) {
            throw new IllegalStateException("conf/inet.json 설정이 필요합니다.");
        }
        System.setProperty("PROC", "WX_API");
        SystemUtils.disableDeepView();
        INetConfigLoader.start();
        SharedMap<String, Object> property = INetConfigLoader.getMap("property", TypeRegistry.MAP_SHAREDMAP_OBJECT);
        if (property == null || !property.getBoolean("enabled")) {
            throw new IllegalStateException("WX_API가 비활성입니다.");
        }
        String profile = property.getString("profile","database");
        if (!java.util.Set.of("database","demo").contains(profile))
            throw new IllegalStateException("profile은 database 또는 demo여야 합니다.");
        property.put("profile",profile);
        boolean database = property.isEquals("profile","database");
        String host = "127.0.0.1";
        String peers = "127.0.0.1";
        if (database) {
            if (property.getString("adm.key").length()<32) throw new IllegalStateException("WX_ADM 내부 키가 필요합니다.");
            host = INetConfigLoader.getHost();
            peers = property.getString("gateway.allowedIps","127.0.0.1");
            MerchantApi.configure(property,peers);
        } else {
            PayoutApi.configure(property);
        }
        Router.start("kr.tx24.wx.api.ctl");

        int port = INetConfigLoader.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("WX_API INet 포트를 확인하세요.");
        }
        try (var server = new InternalInetServer(host, port, peers);
             var rest = property.getInt("rest.port") > 0 ? new kr.tx24.wx.api.ctl.WalletRestApi(property) : null) {
            server.start();
            var released = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(released::countDown));
            System.out.println("WX_API INet " + host + ":" + server.port());
            released.await();
        }
    }
}
