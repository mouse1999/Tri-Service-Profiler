package com.mouse.profiler.controller;

import com.mouse.profiler.dto.ProfileDto;
import com.mouse.profiler.dto.ProfileListResponseDto;
import com.mouse.profiler.dto.ProfileResponseDto;
import com.mouse.profiler.entity.Profile;
import com.mouse.profiler.exception.InvalidInputException;
import com.mouse.profiler.manager.ProfileManager;
import lombok.RequiredArgsConstructor;
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

    @GetMapping
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
}