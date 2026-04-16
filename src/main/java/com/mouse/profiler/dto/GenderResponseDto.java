package com.mouse.profiler.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GenderResponseDto(
        String name,
        String gender,
        double probability,
        @JsonProperty("sample_size")
        int sampleSize
) {
}
