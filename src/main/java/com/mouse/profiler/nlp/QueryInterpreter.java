package com.mouse.profiler.nlp;

import com.mouse.profiler.dto.QueryCriteria;
import com.mouse.profiler.exception.InvalidQueryException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Service responsible for interpreting Natural Language Queries (NLQ).
 * * Logic: Uses deterministic rule-based parsing (Regex and Keyword Mapping)
 * to transform raw search strings into structured filter criteria.
 * * Example: "young males from nigeria" -> {gender: male, minAge: 16, maxAge: 24, countryId: NG}
 */
@Component
public class QueryInterpreter {

    /**
     * Interprets a plain English query string and maps it to filter parameters.
     * * @param query The raw search string (e.g., "females above 30").
     * @return A QueryCriteria object containing the interpreted filters.
     * @throws InvalidQueryException If the query is empty or cannot be interpreted.
     */
    public QueryCriteria interpret(String query) {
        // TODO: Tokenize query string
        // TODO: Apply rules for "young" (16-24)
        // TODO: Extract gender keywords (male/female)
        // TODO: Map country names to ISO alpha-2 codes
        // TODO: Handle age conditions ("above", "under", "between")
        return null;
    }

    /**
     * Private helper to match age-related keywords and map them to numeric ranges.
     */
    private void parseAgeLogic(String query, QueryCriteria criteria) {
        // Method signature only
    }

    /**
     * Private helper to map geographic keywords to country_id.
     */
    private void parseLocationLogic(String query, QueryCriteria criteria) {
        // Method signature only
    }
}