package com.mouse.profiler.utils;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.entry;

/**
 * Registry of canonical mappings used for normalization and query interpretation.
 * Provides deterministic translation of natural language tokens into system-standard
 * values for caching and database querying.
 */
public final class QueryConstants {

    private QueryConstants() {}

    /**
     * Maps informal or plural gender terms to standard lowercase identifiers.
     */
    public static final Map<String, String> GENDER_MAP = Map.ofEntries(
            entry("male", "male"), entry("males", "male"),
            entry("man", "male"), entry("men", "male"),
            entry("boy", "male"), entry("boys", "male"),
            entry("female", "female"), entry("females", "female"),
            entry("woman", "female"), entry("women", "female"),
            entry("girl", "female"), entry("girls", "female")
    );

    /**
     * Defines age ranges for common demographic descriptors.
     */
    public static final Map<String, AgeRange> AGE_RANGE_MAP = Map.ofEntries(
            entry("child", new AgeRange(0, 12)),
            entry("children", new AgeRange(0, 12)),
            entry("kid", new AgeRange(0, 12)),
            entry("kids", new AgeRange(0, 12)),
            entry("teenager", new AgeRange(13, 19)),
            entry("teenagers", new AgeRange(13, 19)),
            entry("teen", new AgeRange(13, 19)),
            entry("teens", new AgeRange(13, 19)),
            entry("adolescent", new AgeRange(13, 19)),
            entry("young", new AgeRange(18, 35)),
            entry("youth", new AgeRange(15, 24)),
            entry("adult", new AgeRange(20, 59)),
            entry("adults", new AgeRange(20, 59)),
            entry("working age", new AgeRange(20, 65)),
            entry("middle aged", new AgeRange(35, 55)),
            entry("senior", new AgeRange(60, 120)),
            entry("seniors", new AgeRange(60, 120)),
            entry("elderly", new AgeRange(60, 120)),
            entry("old", new AgeRange(60, 120))
    );

    /**
     * Maps common country names and aliases to ISO 3166-1 alpha-2 codes.
     */
    public static final Map<String, String> COUNTRY_MAP = Stream.of(new String[][] {
            {"nigeria", "NG"}, {"angola", "AO"}, {"benin", "BJ"}, {"kenya", "KE"},
            {"ghana", "GH"}, {"south africa", "ZA"}, {"egypt", "EG"}, {"morocco", "MA"},
            {"ethiopia", "ET"}, {"tanzania", "TZ"}, {"uganda", "UG"}, {"algeria", "DZ"},
            {"rwanda", "RW"}, {"zimbabwe", "ZW"}, {"zambia", "ZM"}, {"senegal", "SN"},
            {"mali", "ML"}, {"somalia", "SO"}, {"sudan", "SD"}, {"united states", "US"},
            {"usa", "US"}, {"canada", "CA"}, {"brazil", "BR"}, {"mexico", "MX"},
            {"argentina", "AR"}, {"colombia", "CO"}, {"united kingdom", "GB"},
            {"uk", "GB"}, {"france", "FR"}, {"germany", "DE"}, {"italy", "IT"},
            {"spain", "ES"}, {"russia", "RU"}, {"netherlands", "NL"}, {"sweden", "SE"},
            {"china", "CN"}, {"india", "IN"}, {"japan", "JP"}, {"south korea", "KR"},
            {"australia", "AU"}, {"new zealand", "NZ"}, {"singapore", "SG"}
    }).collect(Collectors.toUnmodifiableMap(data -> data[0], data -> data[1]));

    /**
     * Bridges natural language sort requests to JPA entity field names.
     */
    public static final Map<String, String> SORT_FIELD_MAP = Map.ofEntries(
            entry("created_at", "createdAt"),
            entry("gender_probability", "genderProbability"),
            entry("genderprobability", "genderProbability"),
            entry("country_probability", "countryProbability"),
            entry("countryprobability", "countryProbability"),
            entry("age_group", "ageGroup"),
            entry("agegroup", "ageGroup"),
            entry("country_id", "countryId"),
            entry("countryid", "countryId")
    );

    /**
     * Canonicalizes sort direction terminology.
     */
    public static final Map<String, String> ORDER_MAP = Map.of(
            "ascending", "asc",
            "descending", "desc",
            "asc", "asc",
            "desc", "desc",
            "increasing", "asc",
            "decreasing", "desc"
    );

    public record AgeRange(int min, int max) {
        public boolean isValid() {
            return min >= 0 && max > min;
        }
    }
}