package com.mouse.profiler.repository;

import com.mouse.profiler.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Profile entity.
 * Added JpaSpecificationExecutor to support dynamic Natural Language Queries.
 */
public interface ProfileManagerRepository extends
        JpaRepository<Profile, UUID>,
        JpaSpecificationExecutor<Profile> {

    /**
     * Used by Seeder to prevent duplicate entries.
     */
    boolean existsByName(String name);

    /**
     * Finding a profile by name for specific lookups.
     */
    Optional<Profile> findByName(String name);

    /**
     * Note: findWithFilters is still here for your old logic,
     * but the search() method in your service will now primarily use
     * the inherited .findAll(Specification, Pageable) from JpaSpecificationExecutor.
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