package com.ruikang.qc.service;

import com.ruikang.qc.config.QcProperties;
import com.ruikang.qc.dto.auth.LoginRequest;
import com.ruikang.qc.dto.auth.LoginResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    public static final String SESSION_COOKIE_NAME = "ruikang_session";
    public static final Duration SESSION_TTL = Duration.ofHours(8);

    private final QcProperties qcProperties;
    private final Map<String, AuthSession> activeSessions = new ConcurrentHashMap<>();
    public AuthService(QcProperties qcProperties) {
        this.qcProperties = qcProperties;
    }

    public LoginResponse login(LoginRequest request) {
        if (!qcProperties.getAdmin().getUsername().equals(request.username())
                || !qcProperties.getAdmin().getPassword().equals(request.password())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        Instant now = Instant.now();
        activeSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        LoginResponse response = new LoginResponse(
                UUID.randomUUID().toString().replace("-", ""),
                qcProperties.getAdmin().getUsername(),
                qcProperties.getAdmin().getDisplayName(),
                "ADMIN"
        );
        activeSessions.put(response.token(), new AuthSession(now.plus(SESSION_TTL)));
        return response;
    }

    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        AuthSession session = activeSessions.get(token);
        if (session == null) {
            return false;
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            activeSessions.remove(token);
            return false;
        }
        return true;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            activeSessions.remove(token);
        }
    }

    private record AuthSession(Instant expiresAt) {
    }
}
