package com.mouse.profiler.controller;

import com.mouse.profiler.dto.*;
import com.mouse.profiler.entity.Profile;
import com.mouse.profiler.exception.InvalidInputException;
import com.mouse.profiler.exception.InvalidQueryException;
import com.mouse.profiler.manager.ProfileManager;
import com.mouse.profiler.service.CsvIngestionService;
import com.mouse.profiler.service.JobStatusService;
import com.mouse.profiler.utils.CsvExportUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static reactor.netty.http.HttpConnectionLiveness.log;

@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileManager profileManager;
    private final CsvExportUtil csvExportUtil;

    private final CsvIngestionService csvIngestionService;

    private final JobStatusService jobStatusService;

    @Qualifier("csvIngestionExecutor")
    private final Executor csvIngestionExecutor;

    @PostMapping
    public ResponseEntity<ProfileResponseDto> createProfile(@RequestBody Map<String, String> request) {
        String name = request.get("name");

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Missing or empty name");
        }

        // Regex: letters, spaces, hyphens, apostrophes e.g. "Harriet Tubman", "Mary-Jane", "O'Brien"
        if (!name.matches("^[a-zA-Z][a-zA-Z\\s\\-']*$")) {
            throw new InvalidInputException("Invalid type");
        }


        ProfileDto profile = profileManager.createProfile(name);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new ProfileResponseDto("success", profile));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProfileResponseDto> getProfile(@PathVariable UUID id) {
        Profile profile = profileManager.getProfile(id);
        return ResponseEntity.ok(new ProfileResponseDto("success", ProfileDto.fromEntity(profile)));
    }

//    @GetMapping("")
//    public ResponseEntity<ProfileListResponseDto> getAllProfiles(
//            @RequestParam(required = false) String gender,
//            @RequestParam(required = false, name = "country_id") String countryId,
//            @RequestParam(required = false, name = "age_group") String ageGroup) {
//
//        List<Profile> profiles = profileManager.getAllProfiles(gender, countryId, ageGroup);
//        List<ProfileDto> dtos = profiles.stream().map(ProfileDto::fromEntity).toList();
//
//        return ResponseEntity
//                .ok()
//                .header("Access-Control-Allow-Origin", "*")
//                .body(new ProfileListResponseDto("success", dtos.size(), dtos));
//
//    }

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
     * Export Profiles to CSV
     * GET /api/profiles/export?format=csv&gender=male&country_id=US&age_group=adult&min_age=20&max_age=40&sort_by=age&order=desc
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportProfiles(
            @RequestParam(name = "format", defaultValue = "csv") String format,
            QueryCriteria criteria,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {

        // Validate format using util
        if (!csvExportUtil.isSupportedFormat(format)) {
            throw new InvalidInputException("Only CSV format is supported");
        }

        // Validate and map sort field
        validateSortField(sortBy);
        String internalSortField = mapSortField(sortBy);

        // Validate criteria
        criteria.validate();

        // Create sort
        Sort sort = order.equalsIgnoreCase("asc")
                ? Sort.by(internalSortField).ascending()
                : Sort.by(internalSortField).descending();

        // Get all profiles matching criteria
        List<Profile> profiles = profileManager.getAllProfilesForExport(criteria, sort);

        // Generate CSV using util
        byte[] csvData = csvExportUtil.generateCsv(profiles);

        // Generate filename using util
        String filename = csvExportUtil.generateFilename();

        return ResponseEntity
                .ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(csvData);
    }



    /**
     * Uploads a CSV file for batch profile ingestion - ASYNCHRONOUS with Redis.
     *
     * @param file CSV file (max 500k rows)
     * @return job ID for status tracking
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadProfiles(
            @RequestParam("file") MultipartFile file) {

        // Validate file
        if (file == null || file.isEmpty()) {
            throw new InvalidInputException("File is empty or missing");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            throw new InvalidInputException("Only CSV files are accepted");
        }

        if (file.getSize() > 100 * 1024 * 1024) { // 100MB limit
            throw new InvalidInputException("File size exceeds maximum allowed (100MB)");
        }

        // Generate unique job ID
        String jobId = UUID.randomUUID().toString();
        log.info("Async CSV upload started - jobId: {}, filename: {}, size: {} bytes",
                jobId, filename, file.getSize());

        // Store initial processing status in Redis
        jobStatusService.createJob(jobId);

        // Process asynchronously
        CompletableFuture.supplyAsync(() -> csvIngestionService.ingest(file),
                        csvIngestionExecutor)
                .thenAccept(result -> {
                    jobStatusService.completeJob(jobId, result);
                    log.info("Async CSV upload completed - jobId: {}, inserted: {}, skipped: {}",
                            jobId, result.getInserted(), result.getSkipped());
                })
                .exceptionally(throwable -> {
                    log.error("Async CSV upload failed - jobId: {}, error: {}", jobId, throwable.getMessage());
                    jobStatusService.failJob(jobId, throwable.getMessage());
                    return null;
                });

        // Return immediately with job ID
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "jobId", jobId,
                "message", "Upload accepted."
        ));
    }

    /**
     * Gets the status of an async CSV upload job from Redis.
     *
     * @param jobId the job ID from upload endpoint
     * @return status and result if completed
     */
    @GetMapping("/upload/{jobId}/status")
    public ResponseEntity<?> getUploadStatus(@PathVariable String jobId) {
        JobStatus jobStatus = jobStatusService.getJobStatus(jobId);

        if (jobStatus == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "Job not found"));
        }

        if ("processing".equals(jobStatus.getStatus())) {
            return ResponseEntity.ok(Map.of(
                    "status", "processing",
                    "jobId", jobId,
                    "message", "Upload is processing. Please check back later."
            ));
        }

        if ("failed".equals(jobStatus.getStatus())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", "error",
                            "jobId", jobId,
                            "message", "Upload failed"
                    ));
        }

        // Return the actual ingestion result
        return ResponseEntity.ok(jobStatus.getResult());
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