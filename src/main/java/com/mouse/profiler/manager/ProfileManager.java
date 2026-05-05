package com.mouse.profiler.manager;

import com.mouse.profiler.dto.*;
import com.mouse.profiler.entity.Profile;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.ProfileAlreadyExistsException;
import com.mouse.profiler.exception.ProfileNotFoundException;
import com.mouse.profiler.interfaces.NlqInterpreter;
import com.mouse.profiler.nlp.QueryNormalizer;
import com.mouse.profiler.repository.ProfileManagerRepository;
import com.mouse.profiler.service.*;
import com.mouse.profiler.utils.ProfileSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Service orchestrator for managing user profiles.
 * Handles profile lifecycle, external data enrichment via asynchronous API calls,
 * and multi-layered caching for Natural Language Queries (NLQ).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileManager {

    private final GenderService genderService;
    private final AgeService ageService;
    private final NationalizeService nationalizeService;
    private final ProfileManagerRepository repository;

    private final NlqInterpreter nlqInterpreter;
    private final QueryNormalizer normalizer;
    private final QueryCacheService queryCache;
    private final QueryInterpretationCacheService interpretationCache;

    /**
     * Creates a new profile with enriched data from external providers.
     * Data is fetched in parallel to minimize response latency.
     *
     * @param name Name to profile; normalized to lowercase.
     * @return DTO representation of the persisted profile.
     * @throws ProfileAlreadyExistsException if the name is already registered.
     */
    @Transactional
    public ProfileDto createProfile(String name) {
        String normalizedName = name.toLowerCase().trim();
        log.info("Attempting to create profile for: {}", normalizedName);

        repository.findByName(normalizedName).ifPresent(p -> {
            throw new ProfileAlreadyExistsException(ProfileDto.fromEntity(p));
        });

        Profile profile = fetchEnrichedData(normalizedName);
        Profile saved = repository.save(profile);

        // Invalidate query results as the dataset has changed
        queryCache.evictAll();

        log.info("Successfully created profile with ID: {}", saved.getId());
        return ProfileDto.fromEntity(saved);
    }

    /**
     * Orchestrates parallel calls to Agify, Genderize, and Nationalize.
     */
    private Profile fetchEnrichedData(String name) {
        var genderFuture = CompletableFuture.supplyAsync(() -> genderService.fetchGenderData(name));
        var ageFuture = CompletableFuture.supplyAsync(() -> ageService.fetchAgeData(name));
        var natFuture = CompletableFuture.supplyAsync(() -> nationalizeService.fetchNationalityData(name));

        try {
            CompletableFuture.allOf(genderFuture, ageFuture, natFuture).join();

            var gender = genderFuture.join();
            var age = ageFuture.join();
            var nat = natFuture.join();

            return Profile.builder()
                    .name(name)
                    .gender(gender.gender())
                    .genderProbability((float) gender.genderProbability())
                    .age(age.age())
                    .ageGroup(age.ageCategory().getLabel())
                    .countryId(nat.countryProbability().countryId())
                    .countryProbability((float) nat.countryProbability().probability())
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

        } catch (CompletionException e) {
            log.error("Failed to enrich profile data for {}: {}", name, e.getMessage());
            if (e.getCause() instanceof ApiException apiEx) throw apiEx;
            throw new ApiException("Critical error during external data enrichment");
        }
    }

    /**
     * Resolves a Natural Language Query into structured criteria.
     * Utilizes Layer-1 caching for query interpretations.
     */
    public NewProfileResponseDto<Profile> searchWithNLQ(String queryText, Pageable pageable) {
        log.debug("Processing NLQ search: '{}'", queryText);

        QueryCriteria criteria = interpretationCache.get(queryText)
                .orElseGet(() -> {
                    QueryCriteria interpreted = nlqInterpreter.interpret(queryText);
                    interpretationCache.put(queryText, interpreted);
                    return interpreted;
                });

        return executeSearch(criteria, pageable);
    }

    /**
     * Performs a paginated search using structured criteria.
     * Utilizes Layer-2 caching for result sets.
     */
    public NewProfileResponseDto<Profile> searchWithCriteria(QueryCriteria criteria, Pageable pageable) {
        return executeSearch(criteria, pageable);
    }

    private NewProfileResponseDto<Profile> executeSearch(QueryCriteria criteria, Pageable pageable) {
        String cacheKey = generateCacheKey(criteria, pageable);

        return queryCache.get(cacheKey).orElseGet(() -> {
            log.debug("Cache miss for query. Fetching from database...");

            Specification<Profile> spec = ProfileSpecification.build(criteria);
            Page<Profile> page = repository.findAll(spec, pageable);

            NewProfileResponseDto<Profile> response = NewProfileResponseDto.success(
                    page.getContent(),
                    page.getNumber() + 1,
                    page.getSize(),
                    page.getTotalElements(),
                    "/api/profiles"
            );

            queryCache.put(cacheKey, response);
            return response;
        });
    }

    private String generateCacheKey(QueryCriteria criteria, Pageable pageable) {
        String base = normalizer.toCacheKey(criteria);
        Sort.Order order = pageable.getSort().iterator().hasNext()
                ? pageable.getSort().iterator().next()
                : Sort.Order.desc("createdAt");

        return normalizer.withPagination(
                base,
                pageable.getPageNumber() + 1,
                pageable.getPageSize(),
                order.getProperty(),
                order.getDirection().name().toLowerCase()
        );
    }

    /**
     * Fetches all records matching criteria without pagination for export purposes.
     */
    public List<Profile> getAllProfilesForExport(QueryCriteria criteria, Sort sort) {
        if (criteria != null) criteria.validate();
        return repository.findAll(ProfileSpecification.build(criteria), sort);
    }

    @Transactional
    public void deleteProfile(UUID id) {
        if (!repository.existsById(id)) {
            throw new ProfileNotFoundException("No profile found for ID: " + id);
        }
        repository.deleteById(id);
        queryCache.evictAll();
        log.info("Profile {} deleted and result cache invalidated.", id);
    }

    public Profile getProfile(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ProfileNotFoundException("Profile not found: " + id));
    }

    public void evictAllCaches() {
        log.warn("Manual global cache eviction triggered.");
        queryCache.evictAll();
        interpretationCache.evictAll();
    }
}