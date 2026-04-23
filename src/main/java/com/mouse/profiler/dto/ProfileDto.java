package com.mouse.profiler.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mouse.profiler.entity.Profile;

import java.util.UUID;

/**
 * Data Transfer Object representing the 'data' field in the API response.
 * Mirrors the Profile entity but formatted specifically for JSON output.
 */
public record ProfileDto(
        UUID id,
        String name,
        String gender,

        @JsonProperty("gender_probability")
        Float genderProbability,

        @JsonProperty("sample_size")
        Integer sampleSize,

        Integer age,

        @JsonProperty("age_group")
        String ageGroup,

        @JsonProperty("country_id")
        String countryId,

        @JsonProperty("country_probability")
        Float countryProbability,

        @JsonProperty("created_at")
        java.time.OffsetDateTime createdAt
) {
    /**
     * Static factory method to easily convert an Entity to this DTO.
     */
    public static ProfileDto fromEntity(Profile profile) {
        return new ProfileDto(
                profile.getId(),
                profile.getName(),
                profile.getGender(),
                profile.getGenderProbability(),
                profile.getSampleSize(),
                profile.getAge(),
                profile.getAgeGroup(),
                profile.getCountryId(),
                profile.getCountryProbability(),
                profile.getCreatedAt()
        );
    }
}
