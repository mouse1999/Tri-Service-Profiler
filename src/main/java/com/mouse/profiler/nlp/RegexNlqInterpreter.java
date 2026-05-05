package com.mouse.profiler.nlp;

import com.mouse.profiler.dto.QueryCriteria;
import com.mouse.profiler.exception.InvalidQueryException;
import com.mouse.profiler.interfaces.NlqInterpreter;
import com.mouse.profiler.utils.QueryConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RegexNlqInterpreter implements NlqInterpreter {


    private static final Pattern MIN_AGE_PATTERN = Pattern.compile(
            "\\b(above|over|older than|min|at least|greater than)\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MAX_AGE_PATTERN = Pattern.compile(
            "\\b(under|below|younger than|max|at most|less than)\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern AGE_RANGE_PATTERN = Pattern.compile(
            "\\b(?:aged?|between|ages?)\\s+(\\d+)\\s*(?:to|and|-)\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SINGLE_AGE_PATTERN = Pattern.compile(
            "\\b(?:age|aged)\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );



    private static final Pattern SORT_BY_PATTERN = Pattern.compile(
            "\\b(?:sort by|order by|sorted by|arrange by)\\s+([a-z_]+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ORDER_PATTERN = Pattern.compile(
            "\\b(ascending|asc|descending|desc|highest first|lowest first|newest first|oldest first|youngest first)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public QueryCriteria interpret(String query) {
        if (query == null || query.isBlank()) {
            throw new InvalidQueryException("Unable to interpret query");
        }

        String input = query.toLowerCase().trim();
        QueryCriteria criteria = new QueryCriteria();
        boolean matchedAny = false;

        log.debug("Interpreting query: {}", input);


        for (Map.Entry<String, String> entry : QueryConstants.GENDER_MAP.entrySet()) {
            String pattern = ".*\\b" + Pattern.quote(entry.getKey()) + "\\b.*";
            if (input.matches(pattern)) {
                criteria.setGender(entry.getValue());
                matchedAny = true;
                log.debug("Gender matched: {} -> {}", entry.getKey(), entry.getValue());
                break;
            }
        }


        for (Map.Entry<String, QueryConstants.AgeRange> entry : QueryConstants.AGE_RANGE_MAP.entrySet()) {
            String pattern = ".*\\b" + Pattern.quote(entry.getKey()) + "\\b.*";
            if (input.matches(pattern)) {
                criteria.setMin_age(entry.getValue().min());
                criteria.setMax_age(entry.getValue().max());
                matchedAny = true;
                log.debug("Age group matched: {} -> {} to {}",
                        entry.getKey(), entry.getValue().min(), entry.getValue().max());
                break;
            }
        }

        if (parseAgeLogic(input, criteria)) {
            matchedAny = true;
        }


        if (parseLocationLogic(input, criteria)) {
            matchedAny = true;
            log.debug("Location matched: country_id={}", criteria.getCountry_id());
        }


        parseSortingLogic(input, criteria);

        if (!matchedAny && hasNoFilters(criteria)) {
            log.debug("Regex interpreter: no match for query [{}]", query);
            throw new InvalidQueryException("Unable to interpret query");
        }

        log.debug("Regex interpreter: parsed query [{}] -> gender={}, min_age={}, max_age={}, country={}, sort_by={}, order={}",
                query, criteria.getGender(), criteria.getMin_age(), criteria.getMax_age(),
                criteria.getCountry_id(), criteria.getSort_by(), criteria.getOrder());

        return criteria;
    }

    private boolean parseAgeLogic(String query, QueryCriteria criteria) {
        boolean matched = false;

        Matcher rangeMatcher = AGE_RANGE_PATTERN.matcher(query);
        if (rangeMatcher.find()) {
            int minAge = Integer.parseInt(rangeMatcher.group(1));
            int maxAge = Integer.parseInt(rangeMatcher.group(2));
            if (minAge <= maxAge) {
                criteria.setMin_age(minAge);
                criteria.setMax_age(maxAge);
                matched = true;
                log.debug("Age range matched: {} to {}", minAge, maxAge);
            }
        }

        Matcher singleMatcher = SINGLE_AGE_PATTERN.matcher(query);
        if (singleMatcher.find()) {
            int age = Integer.parseInt(singleMatcher.group(1));
            criteria.setMin_age(age);
            criteria.setMax_age(age);
            matched = true;
            log.debug("Single age matched: {}", age);
        }

        Matcher minMatcher = MIN_AGE_PATTERN.matcher(query);
        if (minMatcher.find()) {
            int minAge = Integer.parseInt(minMatcher.group(2));
            criteria.setMin_age(minAge);
            matched = true;
            log.debug("Min age matched: >={}", minAge);
        }

        Matcher maxMatcher = MAX_AGE_PATTERN.matcher(query);
        if (maxMatcher.find()) {
            int maxAge = Integer.parseInt(maxMatcher.group(2));
            criteria.setMax_age(maxAge);
            matched = true;
            log.debug("Max age matched: <={}", maxAge);
        }

        // Swap if reversed
        if (criteria.getMin_age() != null && criteria.getMax_age() != null
                && criteria.getMin_age() > criteria.getMax_age()) {
            int temp = criteria.getMin_age();
            criteria.setMin_age(criteria.getMax_age());
            criteria.setMax_age(temp);
            log.debug("Swapped reversed age range: min={}, max={}",
                    criteria.getMin_age(), criteria.getMax_age());
        }

        return matched;
    }

    private boolean parseLocationLogic(String query, QueryCriteria criteria) {
        for (Map.Entry<String, String> entry : QueryConstants.COUNTRY_MAP.entrySet()) {
            String pattern = ".*\\b" + Pattern.quote(entry.getKey()) + "\\b.*";
            if (query.matches(pattern)) {
                criteria.setCountry_id(entry.getValue());
                return true;
            }
        }
        return false;
    }

    private void parseSortingLogic(String query, QueryCriteria criteria) {
        // 1. Explicit "sort by X" pattern
        Matcher sortMatcher = SORT_BY_PATTERN.matcher(query);
        if (sortMatcher.find()) {
            String sortField = sortMatcher.group(1).toLowerCase();
            criteria.setSort_by(sortField);
            log.debug("Sort field matched: {}", sortField);
        }

        // 2. Determine order from keywords
        Matcher orderMatcher = ORDER_PATTERN.matcher(query);
        if (orderMatcher.find()) {
            String orderKeyword = orderMatcher.group().toLowerCase();
            criteria.setOrder(orderKeyword);
            log.debug("Order matched: {}", orderKeyword);
        }

        // 3. Implied sorting from phrases
        if (criteria.getSort_by() == null) {
            if (query.contains("youngest")) {
                criteria.setSort_by("age");
                if (criteria.getOrder() == null) criteria.setOrder("asc");
                log.debug("Implied sort: youngest -> sort_by=age, order=asc");
            } else if (query.contains("oldest")) {
                criteria.setSort_by("age");
                if (criteria.getOrder() == null) criteria.setOrder("desc");
                log.debug("Implied sort: oldest -> sort_by=age, order=desc");
            } else if (query.contains("newest")) {
                criteria.setSort_by("created_at");
                if (criteria.getOrder() == null) criteria.setOrder("desc");
                log.debug("Implied sort: newest -> sort_by=created_at, order=desc");
            }
        }
    }

    private boolean hasNoFilters(QueryCriteria criteria) {
        return criteria.getGender() == null && criteria.getMin_age() == null &&
                criteria.getMax_age() == null && criteria.getCountry_id() == null;
    }

    public boolean canInterpret(String query) {
        try {
            interpret(query);
            return true;
        } catch (InvalidQueryException e) {
            return false;
        }
    }
}