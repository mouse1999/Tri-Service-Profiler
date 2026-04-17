package com.mouse.profiler.manager;

import com.mouse.profiler.dto.AgeResponseDto;
import com.mouse.profiler.dto.GenderResponseDto;
import com.mouse.profiler.dto.NationalityResponseDto;
import com.mouse.profiler.dto.ProfileDto;
import com.mouse.profiler.entity.Profile;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.ProfileAlreadyExistsException;
import com.mouse.profiler.exception.ProfileNotFoundException;
import com.mouse.profiler.repository.ProfileManagerRepository;
import com.mouse.profiler.service.AgeService;
import com.mouse.profiler.service.GenderService;
import com.mouse.profiler.service.NationalizeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // Lombok Logger
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j // 1. Add this annotation
@Service
@RequiredArgsConstructor
public class ProfileManager {

    private final GenderService genderService;
    private final AgeService ageService;
    private final NationalizeService nationalizeService;
    private final ProfileManagerRepository managerRepository;

    @Transactional
    public ProfileDto createProfile(String name) {
        String nameLowerCase = name.toLowerCase().trim();
        log.info("Manager: Starting profile creation workflow for name: [{}]", nameLowerCase);

        // 1. Idempotency Check
        Optional<Profile> existing = managerRepository.findByName(nameLowerCase);
        if (existing.isPresent()) {
            log.info("Manager: Profile already exists for name: [{}]. Returning cached data.", nameLowerCase);
            throw new ProfileAlreadyExistsException(ProfileDto.fromEntity(existing.get()));
        }

        log.debug("Manager: Initiating parallel API calls for [{}]", nameLowerCase);

        CompletableFuture<GenderResponseDto> genderFuture =
                CompletableFuture.supplyAsync(() -> genderService.fetchGenderData(nameLowerCase));

        CompletableFuture<AgeResponseDto> ageFuture =
                CompletableFuture.supplyAsync(() -> ageService.fetchAgeData(nameLowerCase));

        CompletableFuture<NationalityResponseDto> nationalizeFuture =
                CompletableFuture.supplyAsync(() -> nationalizeService.fetchNationalityData(nameLowerCase));

        try {
            // Wait for all tasks to complete
            CompletableFuture.allOf(genderFuture, ageFuture, nationalizeFuture).join();
            log.debug("Manager: All parallel API calls completed successfully for [{}]", nameLowerCase);

            // 3. Extract results
            GenderResponseDto genderData = genderFuture.join();
            AgeResponseDto ageData = ageFuture.join();
            NationalityResponseDto natData = nationalizeFuture.join();

            Profile profile = Profile.builder()
                    .name(nameLowerCase)
                    .gender(genderData.gender())
                    .genderProbability(genderData.genderProbability())
                    .sampleSize(genderData.sampleSize())
                    .age(ageData.age())
                    .ageGroup(ageData.ageCategory().getLabel())
                    .countryId(natData.countryProbability().countryId())
                    .countryProbability(natData.countryProbability().probability())
                    .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .build();

            Profile savedProfile = managerRepository.save(profile);
            log.info("Manager: Profile successfully created and saved with ID: {}", savedProfile.getId());

            return ProfileDto.fromEntity(savedProfile);

        } catch (CompletionException e) {
            log.error("Manager: One or more parallel tasks failed for name [{}]: {}", nameLowerCase, e.getMessage());
            Throwable cause = e.getCause();

            if (cause instanceof ApiException runtimeException) {
                throw runtimeException;
            }

            throw new ApiException(cause != null ? cause.getMessage() : "Unknown error");
        }
    }

    public List<Profile> getAllProfiles(String gender, String countryId, String ageGroup) {
        log.info("Manager: Fetching all profiles with filters - Gender: {}, Country: {}, AgeGroup: {}",
                gender, countryId, ageGroup);

        String g = (gender != null && !gender.isBlank()) ? gender.toLowerCase() : null;
        String c = (countryId != null && !countryId.isBlank()) ? countryId.toUpperCase() : null;
        String a = (ageGroup != null && !ageGroup.isBlank()) ? ageGroup.toLowerCase() : null;

        List<Profile> profiles = managerRepository.findWithFilters(g, c, a);
        log.debug("Manager: Found {} profiles matching filters", profiles.size());
        return profiles;
    }

    public Profile getProfile(UUID id) {
        log.info("Manager: Fetching profile with ID: {}", id);
        return managerRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Manager: Profile not found for ID: {}", id);
                    return new ProfileNotFoundException("Profile not found");
                });
    }

    @Transactional
    public void deleteProfile(UUID id) {
        log.info("Manager: Attempting to delete profile with ID: {}", id);
        if (!managerRepository.existsById(id)) {
            log.warn("Manager: Delete failed. Profile not found for ID: {}", id);
            throw new ProfileNotFoundException("Profile not found");
        }
        managerRepository.deleteById(id);
        log.info("Manager: Successfully deleted profile with ID: {}", id);
    }
}