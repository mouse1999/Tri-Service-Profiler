package com.mouse.profiler.dto;

public record ProfileExistDto(
        String status,
        String message,
        ProfileDto data
) {
}
