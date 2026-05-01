package com.mouse.profiler.dto.jwt;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(
        String status,
        @JsonProperty("access_token")
        String accessToken,
        @JsonProperty("refresh_token")
        String refreshToken
) {
}