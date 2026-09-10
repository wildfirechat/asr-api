package cn.wildfirechat.asr.stream;

import cn.wildfirechat.asr.multiport.InternalEndpointsFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;

@Configuration
@EnableWebSocket
public class AsrStreamConfig implements WebSocketConfigurer {
    @Autowired
    private AsrStreamHandler asrStreamHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrStreamHandler, "/api/stream")
                .addInterceptors(new UserIdHandshakeInterceptor())
                // 客户端通过 authCode 鉴权，不依赖 Cookie，允许跨域连接
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // Tomcat 默认单条二进制消息最大 8KB，超过会断开连接，旧版本 Android 客户端结束识别时会一次发送 16000 字节静音
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        // 客户端网络异常断开时服务端可能收不到关闭消息，关闭长时间没有收到数据的连接。录音时客户端持续发送音频，正常识别不会超时
        container.setMaxSessionIdleTimeout(90 * 1000L);
        return container;
    }

    /**
     * 把鉴权过滤器校验出的用户 ID 保存到 WebSocket 会话属性中
     */
    private static class UserIdHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest) {
                Object userId = ((ServletServerHttpRequest) request).getServletRequest().getAttribute(InternalEndpointsFilter.ATTR_USER_ID);
                if (userId != null) {
                    attributes.put(AsrStreamHandler.ATTR_USER_ID, userId);
                }
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
