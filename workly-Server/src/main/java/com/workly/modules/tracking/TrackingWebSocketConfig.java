package com.workly.modules.tracking;

import com.workly.common.security.JwtUtils;
import com.workly.modules.job.Job;
import com.workly.modules.job.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@Slf4j
public class TrackingWebSocketConfig implements WebSocketConfigurer {

    private final TrackingWebSocketHandler trackingWebSocketHandler;
    private final JwtUtils jwtUtils;
    private final JobRepository jobRepository;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(trackingWebSocketHandler, "/ws/tracking")
                .setAllowedOrigins("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                            @NonNull ServerHttpResponse response,
                            @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
                        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                        String token = query.getFirst("token");
                        String jobId = query.getFirst("jobId");
                        String role = query.getFirst("role");

                        if (token == null || jobId == null || role == null) {
                            log.debug("TrackingWebSocketConfig: beforeHandshake - missing token/jobId/role, rejected");
                            return false;
                        }
                        try {
                            String mobileNumber = jwtUtils.extractMobileNumber(token);
                            if (mobileNumber == null || !jwtUtils.validateToken(token, mobileNumber)) {
                                log.debug("TrackingWebSocketConfig: beforeHandshake - invalid/expired token, rejected");
                                return false;
                            }
                            Optional<Job> job = jobRepository.findById(jobId);
                            if (job.isEmpty()) {
                                return false;
                            }
                            boolean owns = "SEEKER".equalsIgnoreCase(role)
                                    ? mobileNumber.equals(job.get().getSeekerMobileNumber())
                                    : "PROVIDER".equalsIgnoreCase(role)
                                            && mobileNumber.equals(job.get().getWorkerMobileNumber());
                            if (!owns) {
                                log.warn("TrackingWebSocketConfig: beforeHandshake - {} is not {} on job {}, rejected",
                                        mobileNumber, role, jobId);
                                return false;
                            }
                            return true;
                        } catch (Exception e) {
                            log.debug("TrackingWebSocketConfig: beforeHandshake - token verification failed: {}", e.getMessage());
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
