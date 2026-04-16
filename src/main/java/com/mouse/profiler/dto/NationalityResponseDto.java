package com.mouse.profiler.dto;

import com.mouse.profiler.model.NationalityResponse;

import java.util.List;

public record NationalityResponseDto(
        Integer count,
        String name,
        NationalityResponse.CountryProbability countryProbability
) {
}
