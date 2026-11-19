package kr.tx24.wx.inet;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tx24.inet.codec.INetEncoder;
import kr.tx24.inet.handler.INetHandler;
import kr.tx24.lib.inter.INet;

/** ADM·KYC 내부 INet 수신부의 제한된 네트워크 수명만 담당한다. 업무 Route는 실행 프로젝트가 등록한다. */
public final class InternalInetServer implements AutoCloseable {

    private final String host;
    private final int port;
    private final String allowedIps;
    private final ChannelGroup children = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicInteger pending = new AtomicInteger();
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private EventExecutorGroup business;
    private Channel listener;

    /** 내부 수신 설정: 테스트 포트0 외에 명시된 포트와 허용망만 사용한다. */
    public InternalInetServer(String host, int port, String allowedIps) {

        if (host == null || host.isBlank() || port < 0 || port > 65535 || allowedIps == null || allowedIps.isBlank()) {
            throw new IllegalArgumentException("내부 INet 수신 설정이 필요합니다.");
        }
        this.host = host;
        this.port = port;
        this.allowedIps = allowedIps;
    }

    /** 내부 프로세스 시작: 허용 피어를 역직렬화 전에 검사하고 두 번 기동하지 않는다. */
    public synchronized void start() throws InterruptedException {

        if (listener != null) throw new IllegalStateException("내부 수신부가 이미 시작되었습니다.");
        boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        business = new DefaultEventExecutorGroup(8);
        try {
            listener = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 64).childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        /** 연결 시작: 허용망·연결 상한·프레임 제한을 적용한다. */
                        protected void initChannel(SocketChannel channel) {

                            if (children.size() >= 128 || !InternalInetUtils.isAllowed(channel.remoteAddress().getAddress().getHostAddress(), allowedIps)) {
                                channel.close();
                                return;
                            }
                            children.add(channel);
                            var pipeline = channel.pipeline();
                            pipeline.addLast(new ReadTimeoutHandler(30));
                            pipeline.addLast(new io.netty.handler.timeout.WriteTimeoutHandler(15));
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(1048580, 0, 4, 0, 0));
                            pipeline.addLast(new SafeINetDecoder());
                            pipeline.addLast(new INetEncoder());
                            pipeline.addLast(new ChannelInboundHandlerAdapter() {
                                /** 제한된 업무 실행 대기열만 허용한다. */
                                public void channelRead(ChannelHandlerContext context, Object value) {

                                    if (pending.incrementAndGet() > 64) {
                                        pending.decrementAndGet();
                                        io.netty.util.ReferenceCountUtil.release(value);
                                        context.close();
                                        return;
                                    }
                                    context.fireChannelRead(value);
                                }
                            });
                            pipeline.addLast(business, new INetHandler() {
                                /** 실제 Route 실행 뒤 대기 슬롯을 반환한다. */
                                protected void channelRead0(ChannelHandlerContext context, INet inet) throws Exception {

                                    try { super.channelRead0(context, inet); }
                                    finally { pending.decrementAndGet(); }
                                }
                            });
                            pipeline.addLast(new ChannelInboundHandlerAdapter() {
                                /** 통신/역직렬화 실패는 원문을 로깅하지 않고 연결을 닫는다. */
                                public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {

                                    context.close();
                                }
                            });
                        }
                    }).bind(host, port).sync().channel();
        } catch (InterruptedException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    /** 격리 검증 및 상태 확인용으로 실제 바인딩 포트를 반환한다. */
    public int port() {

        if (listener == null) throw new IllegalStateException("내부 수신부가 시작되지 않았습니다.");
        return ((java.net.InetSocketAddress) listener.localAddress()).getPort();
    }

    /** 프로세스 종료: 신규 수신을 닫고 연결과 업무 실행기를 정리한다. */
    public synchronized void close() {

        if (listener != null) listener.close().awaitUninterruptibly();
        children.close().awaitUninterruptibly();
        if (business != null) business.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS).awaitUninterruptibly();
        if (workers != null) workers.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS).awaitUninterruptibly();
        if (boss != null) boss.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS).awaitUninterruptibly();
        listener = null;
    }
}
