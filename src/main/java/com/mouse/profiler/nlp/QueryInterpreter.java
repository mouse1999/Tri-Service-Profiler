package com.mouse.profiler.nlp;

import com.mouse.profiler.dto.QueryCriteria;
import com.mouse.profiler.exception.InvalidQueryException;
import com.mouse.profiler.utils.QueryConstants;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for interpreting Natural Language Queries (NLQ).
 * Logic: Uses deterministic rule-based parsing (Regex and Keyword Mapping).
 */
@Component
public class QueryInterpreter {

    public QueryCriteria interpret(String query) {
        if (query == null || query.isBlank()) {
            throw new InvalidQueryException("Unable to interpret query");
        }

        String input = query.toLowerCase().trim();
        QueryCriteria criteria = new QueryCriteria();
        boolean matchedAny = false;

        // 1. Gender Parsing
        for (Map.Entry<String, String> entry : QueryConstants.GENDER_MAP.entrySet()) {
            if (input.matches(".*\\b" + entry.getKey() + "\\b.*")) {
                criteria.setGender(entry.getValue());
                matchedAny = true;
                break;
            }
        }

        // 2. Age Group Parsing
        for (Map.Entry<String, String> entry : QueryConstants.AGE_GROUP_MAP.entrySet()) {
            if (input.matches(".*\\b" + entry.getKey() + "\\b.*")) {
                criteria.setAge_group(entry.getValue());
                matchedAny = true;
                break;
            }
        }

        // 3. Location Parsing
        if (parseLocationLogic(input, criteria)) {
            matchedAny = true;
        }

        // 4. Special Rules & Numerical Logic
        if (input.contains("young")) {
            criteria.setMin_age(16);
            criteria.setMax_age(24);
            matchedAny = true;
        }

        if (parseAgeLogic(input, criteria)) {
            matchedAny = true;
        }

        if (!matchedAny) {
            throw new InvalidQueryException("Unable to interpret query");
        }

        return criteria;
    }

    /**
     * Extracts numerical age constraints using Regex.
     * Matches patterns like "above 30", "over 20", "under 18".
     */
    private boolean parseAgeLogic(String query, QueryCriteria criteria) {
        boolean matched = false;

        // Pattern for "above", "over", "older than" -> maps to minAge
        Pattern minAgePattern = Pattern.compile("\\b(above|over|older than|min|at least)\\b\\s+(\\d+)");
        Matcher minMatcher = minAgePattern.matcher(query);
        if (minMatcher.find()) {
            criteria.setMin_age(Integer.parseInt(minMatcher.group(2)));
            matched = true;
        }

        // Pattern for "under", "below", "younger than" -> maps to maxAge
        Pattern maxAgePattern = Pattern.compile("\\b(under|below|younger than|max|at most)\\b\\s+(\\d+)");
        Matcher maxMatcher = maxAgePattern.matcher(query);
        if (maxMatcher.find()) {
            criteria.setMax_age(Integer.parseInt(maxMatcher.group(2)));
            matched = true;
        }

        return matched;
    }

    /**
     * Scans the query for country names defined in QueryConstants.
     */
    private boolean parseLocationLogic(String query, QueryCriteria criteria) {
        for (Map.Entry<String, String> entry : QueryConstants.COUNTRY_MAP.entrySet()) {
            // Using \b for word boundaries to ensure we don't match substrings
            if (query.matches(".*\\b" + Pattern.quote(entry.getKey()) + "\\b.*")) {
                criteria.setCountry_id(entry.getValue());
                return true;
            }
        }
        return false;
    }
}