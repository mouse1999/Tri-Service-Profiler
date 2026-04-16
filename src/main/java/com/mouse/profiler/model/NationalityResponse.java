package com.mouse.profiler.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents the raw response from the Nationalize API.
 * Maps the top-level name and count, and the list of country predictions.
 */
public record NationalityResponse(
        Integer count,
        String name,
        List<CountryProbability> country
) {
    /**
     * Nested record representing the individual country predictions
     * within the 'country' array.
     */
    public record CountryProbability(
            @JsonProperty("country_id")
            String countryId,
            double probability
    ) {}
}