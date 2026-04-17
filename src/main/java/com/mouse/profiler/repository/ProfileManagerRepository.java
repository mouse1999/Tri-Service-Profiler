package com.mouse.profiler.repository;

import com.mouse.profiler.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileManagerRepository extends JpaRepository<Profile, UUID> {
    /**
     *
     * Since name is stored lowercase, we just find by name.
     * The service ensures the input 'name' is also lowercased.
     */
    Optional<Profile> findByName(String name);

    /**
     *
     * Uses null-safe logic and direct comparisons.
     * We assume the Service layer lowercases the filter parameters before passing them here.
     */
    @Query("""
           SELECT p FROM Profile p
           WHERE (:gender IS NULL OR p.gender = :gender)
             AND (:countryId IS NULL OR p.countryId = :countryId)
             AND (:ageGroup IS NULL OR p.ageGroup = :ageGroup)
           """)
    List<Profile> findWithFilters(
            @Param("gender") String gender,
            @Param("countryId") String countryId,
            @Param("ageGroup") String ageGroup);


}
