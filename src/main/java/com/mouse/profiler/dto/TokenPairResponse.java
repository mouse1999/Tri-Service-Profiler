package com.mouse.profiler.dto;

/** Unified token pair response — same shape for browser and CLI */
public record TokenPairResponse(
        String status,
        String accessToken,
        String refreshToken,
        String username,
        String avatarUrl
) {}
