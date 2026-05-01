package com.mouse.profiler.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.mouse.profiler.entity.Profile;

import java.util.UUID;

@JsonPropertyOrder({
        "id", "name", "gender", "gender_probability",
        "age", "age_group", "country_id", "country_name",
        "country_probability", "created_at"
})
public record ProfileDto(
        UUID id,
        String name,
        String gender,

        @JsonProperty("gender_probability")
        Float genderProbability,

        Integer age,
        @JsonProperty("age_group")
        String ageGroup,

        @JsonProperty("country_id")
        String countryId,

        @JsonProperty("country_name")
        String countryName,

        @JsonProperty("country_probability")
        Float countryProbability,

        @JsonProperty("created_at")
        java.time.OffsetDateTime createdAt
) {
    public static ProfileDto fromEntity(Profile profile) {
        return new ProfileDto(
                profile.getId(),
                profile.getName(),
                profile.getGender(),
                profile.getGenderProbability(),
                profile.getAge(),
                profile.getAgeGroup(),
                profile.getCountryId(),
                profile.getCountryName(),
                profile.getCountryProbability(),
                profile.getCreatedAt()
        );
    }
}