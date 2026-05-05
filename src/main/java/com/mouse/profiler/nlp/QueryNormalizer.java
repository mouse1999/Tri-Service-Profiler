package com.mouse.profiler.nlp;

import com.mouse.profiler.dto.QueryCriteria;
import com.mouse.profiler.utils.QueryConstants;
import org.springframework.stereotype.Component;

import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Normalizes {@link QueryCriteria} into deterministic, canonical cache keys.
 * This ensures that semantically identical queries (e.g., varying by input order
 * or naming conventions) resolve to the same cache entry.
 */
@Component
public class QueryNormalizer {

    private static final String NAMESPACE = "profiles:query:";

    /**
     * Converts criteria to a canonical form by mapping gender, countries, and age groups
     * to standard system constants defined in {@link QueryConstants}.
     */
    public QueryCriteria normalize(QueryCriteria original) {
        if (original == null) return new QueryCriteria();

        QueryCriteria normalized = new QueryCriteria();

        // Normalize Gender
        if (isPresent(original.getGender())) {
            String val = original.getGender().toLowerCase().trim();
            normalized.setGender(QueryConstants.GENDER_MAP.getOrDefault(val, val));
        }

        // Normalize Country to ISO codes
        if (isPresent(original.getCountry_id())) {
            String val = original.getCountry_id().toLowerCase().trim();
            String mapped = QueryConstants.COUNTRY_MAP.get(val);
            normalized.setCountry_id(mapped != null ? mapped : val.toUpperCase());
        }

        // Canonicalize Age (Prefer explicit ranges over groups)
        if (isPresent(original.getAge_group())) {
            var range = QueryConstants.AGE_RANGE_MAP.get(original.getAge_group().toLowerCase().trim());
            if (range != null) {
                normalized.setMin_age(range.min());
                normalized.setMax_age(range.max());
            } else {
                normalized.setAge_group(original.getAge_group().toLowerCase().trim());
            }
        } else {
            normalized.setMin_age(original.getMin_age());
            normalized.setMax_age(original.getMax_age());
        }

        normalized.setMin_gender_probability(original.getMin_gender_probability());
        normalized.setMin_country_probability(original.getMin_country_probability());

        return normalized;
    }

    /**
     * Generates a stable, sorted string key based on normalized criteria.
     */
    public String toCacheKey(QueryCriteria criteria) {
        if (criteria == null)
            return "%sall".formatted(NAMESPACE);

        QueryCriteria normalized = normalize(criteria);
        TreeMap<String, String> fields = new TreeMap<>();

        if (isPresent(normalized.getGender()))
            fields.put("gender", normalized.getGender());
        if (normalized.getMin_age() != null)
            fields.put("min_age", String.valueOf(normalized.getMin_age()));
        if (normalized.getMax_age() != null)
            fields.put("max_age", String.valueOf(normalized.getMax_age()));
        if (isPresent(normalized.getCountry_id()))
            fields.put("country_id", normalized.getCountry_id());

        if (normalized.getMin_gender_probability() != null) {
            fields.put("min_gender_prob", String.format("%.2f", normalized.getMin_gender_probability()));
        }
        if (normalized.getMin_country_probability() != null) {
            fields.put("min_country_prob", String.format("%.2f", normalized.getMin_country_probability()));
        }

        if (fields.isEmpty()) return NAMESPACE + "all";

        StringJoiner joiner = new StringJoiner("|", NAMESPACE, "");
        fields.forEach((k, v) -> joiner.add(k + "=" + v));

        return joiner.toString();
    }

    /**
     * Composes a full cache key including pagination and sorting metadata.
     */
    public String withPagination(String baseKey, int page, int limit, String sortBy, String order) {
        String sort = sortBy != null ? sortBy : "createdAt";
        String dir = "asc".equalsIgnoreCase(order) ? "asc" : "desc";
        return String.format("%s:p=%d:l=%d:s=%s:o=%s", baseKey, page, limit, sort, dir);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}