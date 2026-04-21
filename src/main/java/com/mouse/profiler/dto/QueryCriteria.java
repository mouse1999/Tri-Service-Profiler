package com.mouse.profiler.dto;

import lombok.Data;

/**
 * Data Transfer Object representing all possible filterable fields.
 * Used as a bridge between the Controller/Interpreter and the Specification builder.
 */
@Data
public class QueryCriteria {
    private String gender;
    private String ageGroup;
    private Integer minAge;
    private Integer maxAge;
    private String countryId;
    private Double minGenderProbability;
    private Double minCountryProbability;
}
