package com.flashchat.chatservice.websocket.handlers;




import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.extern.slf4j.Slf4j;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class HttpHeadersHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;

            // ===== 1. 提取 URL 参数 =====
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            Map<String, List<String>> params = decoder.parameters();

            String roomId = getParam(params, "roomId");
            String token = getParam(params, "token");
            String type = getParam(params, "type");  // "host" 或 null

            // ===== 2. 提取 IP =====
            HttpHeaders headers = request.headers();
            String ip = Optional.ofNullable(headers.get("X-Real-IP"))
                    .orElse(Optional.ofNullable(headers.get("X-Forwarded-For"))
                            .orElse(getRemoteIp(ctx)));

            // ===== 3. 存入 Channel 属性 =====
            ChannelAttrUtil.set(ctx.channel(), ChannelAttrUtil.TOKEN, token);
            ChannelAttrUtil.set(ctx.channel(), ChannelAttrUtil.IP, ip);
            ChannelAttrUtil.set(ctx.channel(), ChannelAttrUtil.ROOM_ID, roomId);

            // 如果 type=host，标记为房主连接
            if ("host".equals(type)) {
                ChannelAttrUtil.set(ctx.channel(), ChannelAttrUtil.IS_HOST, true);
            }

            log.info("提取连接参数: roomId={}, token={}, type={}, ip={}",
                    roomId, token, type, ip);

            // ===== 4. 重写 URI =====
            // WebSocketServerProtocolHandler 需要路径匹配 "/"
            // 但实际URL是 "/?roomId=xxx&token=xxx"
            // 必须去掉查询参数
            request.setUri(decoder.path());

            // ===== 5. 移除自身 =====
            ctx.pipeline().remove(this);
        }

        ctx.fireChannelRead(msg);
    }

    private String getParam(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    private String getRemoteIp(ChannelHandlerContext ctx) {
        InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
        return address.getAddress().getHostAddress();
    }
}