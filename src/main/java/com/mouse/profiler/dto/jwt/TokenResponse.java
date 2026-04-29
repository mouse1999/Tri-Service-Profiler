package com.mouse.profiler.dto.jwt;

public record TokenResponse(String accessToken,
                            String refreshToken) {
}
