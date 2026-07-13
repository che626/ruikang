package com.ruikang.qc.dto.auth;

public record LoginResponse(
        String token,
        String username,
        String displayName,
        String role
) {
}
