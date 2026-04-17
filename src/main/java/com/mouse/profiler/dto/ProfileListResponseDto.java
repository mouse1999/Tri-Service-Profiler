package com.mouse.profiler.dto;

import java.util.List;

public record ProfileListResponseDto(
        String status,
        Integer count,
        List<ProfileDto> data
) {
}
