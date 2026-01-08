package com.example.edog.configurer;

import com.example.edog.service.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.standard.TomcatRequestUpgradeStrategy;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private WebSocketServer webSocketServer;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 使用自定义握手处理器，设置更大的缓冲区
        DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler(
            new TomcatRequestUpgradeStrategy()
        );
        
        registry.addHandler(webSocketServer, "/esp32")
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOrigins("*");
    }

    /**
     * 配置 WebSocket 容器参数
     * 增大缓冲区大小，延长超时时间，防止长连接被意外关闭
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 最大文本消息缓冲区大小 (128KB)
        container.setMaxTextMessageBufferSize(131072);
        // 最大二进制消息缓冲区大小 (128KB)
        container.setMaxBinaryMessageBufferSize(131072);
        // 会话空闲超时时间 (30分钟)
        container.setMaxSessionIdleTimeout(1800000L);
        // 异步发送超时时间 (60秒)
        container.setAsyncSendTimeout(60000L);
        return container;
    }
}

