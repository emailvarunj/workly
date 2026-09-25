package com.workly.chat.config;

import com.workly.chat.handler.ChatWebSocketHandler;
import com.workly.common.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Map;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@Slf4j
public class WebSocketConfig implements WebSocketConfigurer {

    @NonNull
    private final ChatWebSocketHandler chatWebSocketHandler;

    @NonNull
    private final JwtUtils jwtUtils;

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                            @NonNull ServerHttpResponse response,
                            @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
                        // The client identity is derived from a verified JWT, never trusted from a
                        // client-supplied userId — otherwise any caller can read/send as anyone.
                        String token = UriComponentsBuilder.fromUri(request.getURI())
                                .build().getQueryParams().getFirst("token");
                        if (token == null || token.isBlank()) {
                            log.debug("WebSocketConfig: beforeHandshake - no token param, handshake rejected");
                            return false;
                        }
                        try {
                            String userId = jwtUtils.extractMobileNumber(token);
                            if (userId == null || !jwtUtils.validateToken(token, userId)) {
                                log.debug("WebSocketConfig: beforeHandshake - invalid/expired token, handshake rejected");
                                return false;
                            }
                            attributes.put("userId", userId);
                            log.debug("WebSocketConfig: beforeHandshake - token verified for {}, handshake allowed", userId);
                            return true;
                        } catch (Exception e) {
                            log.debug("WebSocketConfig: beforeHandshake - token verification failed: {}", e.getMessage());
                            return false;
                        }
                    }

                    @Override
                    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                            @NonNull WebSocketHandler wsHandler, @Nullable Exception exception) {
                    }
                });
    }
}
