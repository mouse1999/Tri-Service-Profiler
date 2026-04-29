package com.mouse.profiler.controller;

import com.mouse.profiler.dto.*;
import com.mouse.profiler.entity.Profile;
import com.mouse.profiler.exception.InvalidInputException;
import com.mouse.profiler.exception.InvalidQueryException;
import com.mouse.profiler.manager.ProfileManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS}
) // Critical Requirement: Access-Control-Allow-Origin: *
public class ProfileController {

    private final ProfileManager profileManager;

    @PostMapping
    public ResponseEntity<ProfileResponseDto> createProfile(@RequestBody Map<String, String> request) {
        String name = request.get("name");

        // 1. Basic Null/Empty check (Matches your Manager logic)
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Missing or empty name");
        }

        // 2. Regex to enforce ONLY letters (A-Z, a-z)
        if (!name.matches("^[a-zA-Z]+$")) {
            throw new InvalidInputException("Invalid type");
        }


        ProfileDto profile = profileManager.createProfile(name);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .header("Access-Control-Allow-Origin", "*")
                .body(new ProfileResponseDto("success", profile));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProfileResponseDto> getProfile(@PathVariable UUID id) {
        Profile profile = profileManager.getProfile(id);
        return ResponseEntity.
                ok()
                .header("Access-Control-Allow-Origin", "*")
                .body(new ProfileResponseDto("success", ProfileDto.fromEntity(profile)));
    }

    @GetMapping("/all")
    public ResponseEntity<ProfileListResponseDto> getAllProfiles(
            @RequestParam(required = false) String gender,
            @RequestParam(required = false, name = "country_id") String countryId,
            @RequestParam(required = false, name = "age_group") String ageGroup) {

        List<Profile> profiles = profileManager.getAllProfiles(gender, countryId, ageGroup);
        List<ProfileDto> dtos = profiles.stream().map(ProfileDto::fromEntity).toList();

        return ResponseEntity
                .ok()
                .header("Access-Control-Allow-Origin", "*")
                .body(new ProfileListResponseDto("success", dtos.size(), dtos));

    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@PathVariable UUID id) {
        profileManager.deleteProfile(id);
    }



    /**
     * 3: Get All Profiles (Filtering & Pagination)
     * GET /api/profiles?gender=male&min_age=20&sort_by=created_at
     */
    @GetMapping
    public ResponseEntity<NewProfileResponseDto<Profile>> getProfiles(
            QueryCriteria criteria,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "page", defaultValue = "1") String pageStr,
            @RequestParam(name = "limit", defaultValue = "10") String limitStr) {

        int page = parseOrDefault(pageStr, 1);
        int limit = parseOrDefault(limitStr, 10);

        // Standardize page/limit ranges
        page = Math.max(page, 1);
        limit = Math.min(Math.max(limit, 1), 50);

        // 1. Validate the sort field name
        validateSortField(sortBy);

        // 2. Map the incoming sort string to the actual Entity field name
        String internalSortField = mapSortField(sortBy);

        // 3. Manual DTO Validation (Throws "Invalid query parameter")
        criteria.validate();

        // 4. Execute with the Mapped sort field
        Pageable pageable = createPageable(page, limit, internalSortField, order);
        return ResponseEntity.ok(profileManager.searchWithCriteria(criteria, pageable));
    }

    /**
     * 4: Natural Language Query (NLQ)
     */
    @GetMapping("/search")
    public ResponseEntity<NewProfileResponseDto<Profile>> searchNLQ(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "page", defaultValue = "1") String pageStr,
            @RequestParam(name = "limit", defaultValue = "10") String limitStr) {

        if (q == null || q.trim().isBlank()) {
        
            throw new InvalidQueryException("Invalid query parameter");
        }

        if (q.length() > 100) {
            throw new InvalidQueryException("Invalid query parameter");
        }

        int page = Math.max(parseOrDefault(pageStr, 1), 1);
        int limit = Math.min(Math.max(parseOrDefault(limitStr, 10), 1), 50);

        validateSortField(sortBy);
        String internalSortField = mapSortField(sortBy);

        Pageable pageable = createPageable(page, limit, internalSortField, order);
        return ResponseEntity.ok(profileManager.searchWithNLQ(q.trim(), pageable));
    }



    /**
     * Maps incoming web parameters to Java Entity fields.
     * This prevents PropertyReferenceExceptions in JPA.
     */
    private String mapSortField(String sortBy) {
        return switch (sortBy) {
            case "created_at" -> "createdAt";
            case "gender_probability" -> "genderProbability";
            case "country_probability" -> "countryProbability";
            case "age_group" -> "ageGroup";
            case "country_id" -> "countryId";
            default -> sortBy;
        };
    }

    private void validateSortField(String sortBy) {
        // List all possible values the client might send (including snake_case)
        List<String> allowed = List.of(
                "age", "created_at", "createdAt", "gender_probability",
                "genderProbability", "country_id", "countryId"
        );

        if (!allowed.contains(sortBy)) {
            throw new InvalidQueryException("Invalid query parameter");
        }
    }

    private Pageable createPageable(int page, int limit, String sortBy, String order) {
        Sort sort = order.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        return PageRequest.of(page - 1, limit, sort);
    }

    private int parseOrDefault(String val, int defaultVal) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            
            return defaultVal;
        }
    }



}
