package com.mouse.profiler.dto;

import com.mouse.profiler.exception.InvalidQueryException;
import lombok.Data;

/**
 * Data Transfer Object representing all possible filterable fields.
 * Fields are named in snake_case to match incoming URL Query Parameters exactly.
 */
@Data
public class QueryCriteria {
    private String gender;
    private String age_group;
    private Integer min_age;
    private Integer max_age;
    private String country_id;

    private Float min_gender_probability;
    private Float min_country_probability;

    public void validate() {
        // 1. Gender Validation
        if (gender != null && !gender.isBlank()) {
            if (!gender.equalsIgnoreCase("male") && !gender.equalsIgnoreCase("female")) {
                throw new InvalidQueryException("Gender must be either 'male' or 'female'");
            }
        }

        // 2. Country ID Validation
        if (country_id != null && !country_id.isBlank()) {
            if (country_id.length() != 2) {
                throw new InvalidQueryException("country_id must be a 2-letter ISO code (e.g., NG)");
            }
            // Normalize to uppercase for consistent database searching
            this.country_id = country_id.toUpperCase();
        }

        // 3. Age Range Validation
        if (min_age != null && min_age < 0) {
            throw new InvalidQueryException("min_age cannot be negative");
        }
        if (max_age != null && max_age > 120) {
            throw new InvalidQueryException("max_age exceeds human limits");
        }
        if (min_age != null && max_age != null && min_age > max_age) {
            throw new InvalidQueryException("min_age cannot be greater than max_age");
        }

        // 4. Probability Validation (0.0 to 1.0)
        validateProbability(min_gender_probability, "min_gender_probability");
        validateProbability(min_country_probability, "min_country_probability");
    }

    private void validateProbability(Float val, String name) {
        if (val != null && (val < 0.0 || val > 1.0)) {
            throw new InvalidQueryException(name + " must be between 0.0 and 1.0");
        }
    }
}