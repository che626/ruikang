package com.ruikang.qc.controller.auth;

import com.ruikang.qc.common.ApiResponse;
import com.ruikang.qc.dto.auth.LoginRequest;
import com.ruikang.qc.dto.auth.LoginResponse;
import com.ruikang.qc.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        LoginResponse login = authService.login(request);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(login.token(), isSecure(httpRequest)).toString());
        return ApiResponse.success("登录成功", login);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @CookieValue(name = AuthService.SESSION_COOKIE_NAME, required = false) String token,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        authService.logout(token);
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, expiredSessionCookie(isSecure(httpRequest)).toString());
        return ApiResponse.success("已退出登录", null);
    }

    private ResponseCookie sessionCookie(String token, boolean secure) {
        return ResponseCookie.from(AuthService.SESSION_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(AuthService.SESSION_TTL)
                .build();
    }

    private ResponseCookie expiredSessionCookie(boolean secure) {
        return ResponseCookie.from(AuthService.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }

    private boolean isSecure(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
