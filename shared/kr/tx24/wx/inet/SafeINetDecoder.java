package kr.tx24.wx.inet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import kr.tx24.lib.inter.INet;
import java.io.ByteArrayInputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Set;

/** 기존 INet 전문 계약을 유지하며 WX 게이트웨이의 평면 값만 제한된 역직렬화로 수신한다. */
public final class SafeINetDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final Set<Class<?>> ALLOWED = Set.of(INet.INMessage.class, String.class, Number.class,
            Integer.class, Long.class, Short.class, Byte.class, Double.class, Float.class, Boolean.class, Character.class);

    /** 길이 검증을 마친 프레임만 읽으며 임의 클래스의 readObject 실행을 허용하지 않는다. */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> output) throws Exception {

        int length = frame.readInt();
        if (length < 8 || length > 1048576 || frame.readableBytes() != length) { ctx.close(); return; }
        byte[] data = new byte[length];
        frame.readBytes(data);
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(data))) {
            input.setObjectInputFilter(info -> {
                if (info.depth() > 8 || info.references() > 1024 || info.streamBytes() > 1048576 || info.arrayLength() >= 0) {
                    return ObjectInputFilter.Status.REJECTED;
                }
                Class<?> type = info.serialClass();
                return type == null ? ObjectInputFilter.Status.UNDECIDED
                        : ALLOWED.contains(type) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
            });
            Object decoded = input.readObject();
            if (!(decoded instanceof INet.INMessage message) || message.head().size() > 64 || message.data().size() > 64) {
                throw new InvalidObjectException("INet 형식 오류");
            }
            if (input.read() != -1) throw new InvalidObjectException("INet 후행 자료 오류");
            output.add(new INet().head(message.head()).data(message.data()));
        } catch (java.io.IOException | ClassNotFoundException rejected) {
            ctx.close();
        } finally {
            java.util.Arrays.fill(data, (byte) 0);
        }
    }
}
