package com.mouse.profiler.dto;

import com.mouse.profiler.enums.AgeCategory;

public record AgeResponseDto(
        String name,
        Integer age,
        Integer count,
        AgeCategory ageCategory
) {
}
