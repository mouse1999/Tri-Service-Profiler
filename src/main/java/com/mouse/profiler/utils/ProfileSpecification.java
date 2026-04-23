package com.mouse.profiler.utils;

import com.mouse.profiler.dto.QueryCriteria;
import com.mouse.profiler.entity.Profile;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to build dynamic JPA Specifications for the Profile entity.
 * Logic: Construct complex AND predicates based on available filters.
 */
public class ProfileSpecification {

    /**
     * Generates a combined Specification based on the provided criteria.
     * @param criteria The structured filters interpreted from the query.
     * @return A Specification object to be used with ProfileRepository.
     */
    public static Specification<Profile> build(QueryCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Filter by Gender
            if (criteria.getGender() != null && !criteria.getGender().isBlank()) {
                predicates.add(cb.equal(root.get("gender"), criteria.getGender()));
            }

            // 2. Filter by Age Group (matches 'ageGroup' in Entity)
            if (criteria.getAge_group() != null && !criteria.getAge_group().isBlank()) {
                predicates.add(cb.equal(root.get("ageGroup"), criteria.getAge_group()));
            }

            // 3. Filter by Country ID (matches 'countryId' in Entity)
            if (criteria.getCountry_id() != null && !criteria.getCountry_id().isBlank()) {
                predicates.add(cb.equal(root.get("countryId"), criteria.getCountry_id()));
            }

            // 4. Filter by Minimum Age (matches 'age' in Entity)
            if (criteria.getMin_age() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("age"), criteria.getMin_age()));
            }

            // 5. Filter by Maximum Age
            if (criteria.getMax_age() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("age"), criteria.getMax_age()));
            }

            // 6. Filter by Minimum Gender Probability (matches 'genderProbability' in Entity)
            if (criteria.getMin_gender_probability() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("genderProbability"), criteria.getMin_gender_probability()));
            }

            // 7. Filter by Minimum Country Probability (matches 'countryProbability' in Entity)
            if (criteria.getMin_country_probability() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("countryProbability"), criteria.getMin_country_probability()));
            }


            // Combine all active predicates with AND logic
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
