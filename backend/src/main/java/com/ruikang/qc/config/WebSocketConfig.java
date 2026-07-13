package com.ruikang.qc.config;

import com.ruikang.qc.websocket.RealtimeStatusWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final RealtimeStatusWebSocketHandler realtimeStatusWebSocketHandler;

    public WebSocketConfig(RealtimeStatusWebSocketHandler realtimeStatusWebSocketHandler) {
        this.realtimeStatusWebSocketHandler = realtimeStatusWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeStatusWebSocketHandler, "/ws/realtime/status")
                .setAllowedOrigins("*");
    }

    /**
     * Increase WebSocket message buffer sizes.
     * Default 8KB is too small for base64-encoded frames (typically 30-80KB).
     */
    @Bean
    public ServletServerContainerFactoryBean servletServerContainerFactoryBean() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);   // 512 KB for base64 JPEG frames
        container.setMaxBinaryMessageBufferSize(512 * 1024); // 512 KB
        container.setMaxSessionIdleTimeout(600_000L);        // 10 min idle timeout
        log.info("WebSocket container configured: maxTextBuffer={}, maxBinaryBuffer={}",
                container.getMaxTextMessageBufferSize(), container.getMaxBinaryMessageBufferSize());
        return container;
    }
}
