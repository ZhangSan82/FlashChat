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
/**
 * HTTP 握手阶段提取连接参数
 *
 * 连接URL：ws://host:8090/?token=xxx
 * 只提取 token 和 IP，不再提取 roomId
 * 因为一个连接对应多个房间，roomId 不在连接时确定
 */
@Slf4j
public class HttpHeadersHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;

            // ===== 1. 提取 URL 参数 =====
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            Map<String, List<String>> params = decoder.parameters();

            String token = getParam(params, "token");

            //提取账号ID
            String accoundId = getParam(params, "accoundId");

            // ===== 2. 提取 IP =====
            HttpHeaders headers = request.headers();
            String ip = headers.get("X-Real-IP");
            if (ip == null) {
                String xff = headers.get("X-Forwarded-For");
                if (xff != null) {
                    ip = xff.split(",")[0].trim();  // 取第一个IP
                }
            }
            if (ip == null) {
                ip = getRemoteIp(ctx);
            }

            // ===== 3. 存入 Channel 属性（仅用户级信息） =====
            ChannelAttrUtil.set(ctx.channel(), ChannelAttrUtil.TOKEN, token);
            ChannelAttrUtil.set(ctx.channel(), ChannelAttrUtil.IP, ip);
            ChannelAttrUtil.set(ctx.channel(),ChannelAttrUtil.ACCOUNT_ID, accoundId);

            log.info("[WS握手] 提取连接参数: token={}, ip={}", token, ip);

            // ===== 4. 重写 URI =====
            // WebSocketServerProtocolHandler 需要路径匹配 "/"
            // 但实际URL是 "/?token=xxx"
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