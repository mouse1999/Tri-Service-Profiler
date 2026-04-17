package com.mouse.profiler.dto;

public record ProfileResponseDto(
        String status,
        ProfileDto data
) {
}
