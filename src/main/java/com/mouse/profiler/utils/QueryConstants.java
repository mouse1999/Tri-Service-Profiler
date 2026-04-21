package com.mouse.profiler.utils;


import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Utility class containing static mappings for NLP processing and Query Interpretation.
 */
public class QueryConstants {

    // Prevent instantiation
    private QueryConstants() {}

    public static final Map<String, String> GENDER_MAP = Map.ofEntries(
            entry("male", "male"),
            entry("males", "male"),
            entry("man", "male"),
            entry("men", "male"),
            entry("boy", "male"),
            entry("boys", "male"),
            entry("female", "female"),
            entry("females", "female"),
            entry("woman", "female"),
            entry("women", "female"),
            entry("girl", "female"),
            entry("girls", "female")
    );

    public static final Map<String, String> AGE_GROUP_MAP = Map.ofEntries(
            entry("child", "child"),
            entry("children", "child"),
            entry("kid", "child"),
            entry("kids", "child"),
            entry("teenager", "teenager"),
            entry("teenagers", "teenager"),
            entry("teen", "teenager"),
            entry("teens", "teenager"),
            entry("adult", "adult"),
            entry("adults", "adult"),
            entry("senior", "senior"),
            entry("seniors", "senior"),
            entry("elderly", "senior")
    );



    public static final Map<String, String> COUNTRY_MAP;

    static {
        Map<String, String> map = new HashMap<>();
        // African Countries (Major ones for your dataset)
        map.put("nigeria", "NG");
        map.put("angola", "AO");
        map.put("benin", "BJ");
        map.put("kenya", "KE");
        map.put("ghana", "GH");
        map.put("south africa", "ZA");
        map.put("egypt", "EG");
        map.put("morocco", "MA");
        map.put("ethiopia", "ET");
        map.put("tanzania", "TZ");
        map.put("uganda", "UG");
        map.put("algeria", "DZ");

        // Americas
        map.put("united states", "US");
        map.put("canada", "CA");
        map.put("brazil", "BR");
        map.put("mexico", "MX");
        map.put("argentina", "AR");
        map.put("colombia", "CO");

        // Europe
        map.put("united kingdom", "GB");
        map.put("france", "FR");
        map.put("germany", "DE");
        map.put("italy", "IT");
        map.put("spain", "ES");
        map.put("russia", "RU");
        map.put("netherlands", "NL");
        map.put("sweden", "SE");

        // Asia / Oceania
        map.put("china", "CN");
        map.put("india", "IN");
        map.put("japan", "JP");
        map.put("south korea", "KR");
        map.put("australia", "AU");
        map.put("new zealand", "NZ");
        map.put("singapore", "SG");

        // Additional synonyms
        map.put("usa", "US");
        map.put("uk", "GB");

        COUNTRY_MAP = Collections.unmodifiableMap(map);
    }
}