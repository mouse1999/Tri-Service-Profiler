package com.mouse.profiler.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.mouse.profiler.entity.Profile;
import com.mouse.profiler.enums.AgeCategory;
import com.mouse.profiler.repository.ProfileManagerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * DataSeeder handles the initial ingestion of profiles.
 * This version uses the "Drill-Down" approach to handle JSON files
 * wrapped in a root object like {"profiles": [...]}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder {

    @Value("${seeder.enabled:false}")
    private boolean seederEnabled;

    private final ProfileManagerRepository profileRepository;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedData() {
        if (!seederEnabled) {
            log.info("Data seeder is disabled (seeder.enabled=false). Skipping.");
            return;
        }

        log.info("Checking database state for initial seeding...");

        try {
            // Path: src/main/resources/seed_profiles.json
            Resource resource = resourceLoader.getResource("classpath:seed_profiles.json");

            if (!resource.exists()) {
                log.error("Seed file not found at classpath:static/seed_profiles.json");
                return;
            }

            try (InputStream inputStream = resource.getInputStream()) {
                // 1. Read the JSON as a Tree (JsonNode) to handle the root object
                JsonNode rootNode = objectMapper.readTree(inputStream);

                // 2. Navigate to the specific field containing the list
                // We assume the key is "profiles" based on common standards
                JsonNode profilesNode = rootNode.get("profiles");

                if (profilesNode == null || !profilesNode.isArray()) {
                    log.error("JSON structure invalid: Root object must contain a 'profiles' array.");
                    return;
                }

                // 3. Map only the array content to our List<Profile>
                List<Profile> profiles = objectMapper.convertValue(
                        profilesNode,
                        new TypeReference<List<Profile>>() {}
                );

                log.info("Found {} potential profiles. Checking for duplicates...", profiles.size());

                long newRecordsCount = profiles.stream()
                        .filter(this::shouldSave)
                        .peek(this::enrichProfile)
                        .map(profileRepository::save)
                        .count();

                log.info("Seeding complete. Added {} new records to the database.", newRecordsCount);
            }
        } catch (Exception e) {
            log.error("Critical failure during data seeding: {}", e.getMessage());

        }
    }

    private boolean shouldSave(Profile profile) {
        // Check if name is null
        if (profile.getName() == null) return false;

        if (profile.getCountryId() == null || profile.getCountryId().isBlank()) {
            log.warn("Skipping profile '{}' due to missing country_id", profile.getName());
            return false;
        }

        return !profileRepository.existsByName(profile.getName());
    }

    private void enrichProfile(Profile profile) {
        // Assign UUID v7
        profile.setId(Generators.timeBasedEpochGenerator().generate());

        // Calculate age group if missing or blank
        if (profile.getAgeGroup() == null || profile.getAgeGroup().isBlank()) {
            profile.setAgeGroup(AgeCategory.fromAge(profile.getAge()).getLabel());
        }

        // Ensure name is stored lowercase for consistent searching
        if (profile.getName() != null) {
            profile.setName(profile.getName().toLowerCase());
        }
    }
}