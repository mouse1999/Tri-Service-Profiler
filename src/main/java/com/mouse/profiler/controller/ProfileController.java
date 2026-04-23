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



    @GetMapping
    public ResponseEntity<NewProfileResponseDto<Profile>> getProfiles(
            QueryCriteria criteria,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "page", defaultValue = "1") String pageStr,
            @RequestParam(name = "limit", defaultValue = "10") String limitStr) {

        // 1. Manual Parsing of Pagination (To prevent TypeMismatchException)
        int page = parseOrDefault(pageStr, 1);
        int limit = parseOrDefault(limitStr, 10);

        // 2. Manual Range Checks for Pagination
        if (page < 1) page = 1;
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50; // Requirement: max 50

        // 3. Manual Sorting Validation
        validateSortField(sortBy);

        // 4. Manual DTO Validation
        criteria.validate();

        // 5. Execute
        Pageable pageable = createPageable(page, limit, sortBy, order);
        return ResponseEntity.ok(profileManager.searchWithCriteria(criteria, pageable));
    }


    /**
     * 4: Natural Language Query (NLQ)
     * GET /api/profiles/search?q=young males from nigeria
     */
    @GetMapping("/search")
    public ResponseEntity<NewProfileResponseDto<Profile>> searchNLQ(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "page", defaultValue = "1") String pageStr,
            @RequestParam(name = "limit", defaultValue = "10") String limitStr) {

        // 1. Manual Validation for the 'q' parameter
        if (q == null || q.trim().isBlank()) {
            throw new IllegalArgumentException("Missing or empty parameter");
        }

        if (q.length() > 100) {
            throw new InvalidQueryException("Search query is too long (max 100 characters)");
        }

        // 2. Manual Parsing and Range Validation for Pagination
        int page = parseOrDefault(pageStr, 1);
        int limit = parseOrDefault(limitStr, 10);

        if (page < 1) page = 1;
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50; // Requirement: max 50

        // 3. Manual Sorting Validation
        validateSortField(sortBy);

        // 4. Build Pageable and Execute
        Pageable pageable = createPageable(page, limit, sortBy, order);

        return ResponseEntity.ok(profileManager.searchWithNLQ(q.trim(), pageable));
    }

    /**
     * Reusable helper to handle pagination and sorting logic.
     */
    private Pageable createPageable(int page, int limit, String sortBy, String order) {
        int validatedLimit = Math.min(limit, 50);
        int adjustedPage = Math.max(page - 1, 0);

        Sort sort = order.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        return PageRequest.of(adjustedPage, validatedLimit, sort);
    }


    private void validateSortField(String sortBy) {
        List<String> allowed = List.of("age", "created_at", "gender_probability", "createdAt");
        if (!allowed.contains(sortBy)) {
            throw new InvalidQueryException("Invalid sort_by field: " + sortBy);
        }
    }

    private int parseOrDefault(String val, int defaultVal) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal; // Or throw InvalidQueryException if you prefer strictness todo
        }
    }



}