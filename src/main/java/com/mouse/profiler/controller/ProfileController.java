package com.mouse.profiler.controller;

import com.mouse.profiler.dto.*;
import com.mouse.profiler.entity.Profile;
import com.mouse.profiler.exception.InvalidInputException;
import com.mouse.profiler.exception.InvalidQueryException;
import com.mouse.profiler.manager.ProfileManager;
import com.mouse.profiler.service.CsvIngestionService;
import com.mouse.profiler.service.JobStatusService;
import com.mouse.profiler.service.QueryCacheService;
import com.mouse.profiler.utils.CsvExportUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileManager profileManager;
    private final CsvExportUtil csvExportUtil;
    private final CsvIngestionService csvIngestionService;
    private final JobStatusService jobStatusService;
    private final QueryCacheService cacheService;

    @Qualifier("csvIngestionExecutor")
    private final Executor csvIngestionExecutor;

    // ==================== CREATE PROFILE ====================

    @PostMapping
    public ResponseEntity<ProfileResponseDto> createProfile(@RequestBody Map<String, String> request) {
        log.info("========================================");
        log.info("CREATE PROFILE REQUEST");
        log.info("Request body: {}", request);
        
        String name = request.get("name");

        if (name == null || name.isBlank()) {
            log.warn("Create profile failed: missing or empty name");
            throw new IllegalArgumentException("Missing or empty name");
        }

        if (!name.matches("^[a-zA-Z][a-zA-Z\\s\\-']*$")) {
            log.warn("Create profile failed: invalid name format '{}'", name);
            throw new InvalidInputException("Invalid type");
        }

        log.info("Creating profile for name: {}", name);
        ProfileDto profile = profileManager.createProfile(name);
        log.info("Profile created successfully with ID: {}", profile.id());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new ProfileResponseDto("success", profile));
    }

    // ==================== GET PROFILE ====================

    @GetMapping("/{id}")
    public ResponseEntity<ProfileResponseDto> getProfile(@PathVariable UUID id) {
        log.debug("Fetching profile by ID: {}", id);
        
        Profile profile = profileManager.getProfile(id);
        log.debug("Profile found: {}", profile.getName());
        
        return ResponseEntity.ok(new ProfileResponseDto("success", ProfileDto.fromEntity(profile)));
    }

    // ==================== DELETE PROFILE ====================

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@PathVariable UUID id) {
        log.info("Deleting profile by ID: {}", id);
        profileManager.deleteProfile(id);
        log.info("Profile deleted successfully: {}", id);
    }

    // ==================== GET PROFILES WITH FILTERS ====================

    @GetMapping
    public ResponseEntity<NewProfileResponseDto<Profile>> getProfiles(
            QueryCriteria criteria,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "page", defaultValue = "1") String pageStr,
            @RequestParam(name = "limit", defaultValue = "10") String limitStr) {

        log.info("========================================");
        log.info("GET PROFILES REQUEST");
        log.info("Criteria: {}", criteria);
        log.info("Sort by: {}, Order: {}", sortBy, order);
        log.info("Page: {}, Limit: {}", pageStr, limitStr);

        int page = parseOrDefault(pageStr, 1);
        int limit = parseOrDefault(limitStr, 10);

        page = Math.max(page, 1);
        limit = Math.min(Math.max(limit, 1), 50);

        log.debug("Normalized page: {}, limit: {}", page, limit);

        validateSortField(sortBy);
        String internalSortField = mapSortField(sortBy);
        log.debug("Internal sort field: {}", internalSortField);

        criteria.validate();
        log.debug("Criteria validated successfully");

        Pageable pageable = createPageable(page, limit, internalSortField, order);
        
        long start = System.currentTimeMillis();
        NewProfileResponseDto<Profile> response = profileManager.searchWithCriteria(criteria, pageable);
        long duration = System.currentTimeMillis() - start;
        
        log.info("Query executed in {}ms, returned {} profiles", duration, response.getData().size());
        log.info("========================================");

        return ResponseEntity.ok(response);
    }

    // ==================== NATURAL LANGUAGE SEARCH ====================

    @GetMapping("/search")
    public ResponseEntity<NewProfileResponseDto<Profile>> searchNLQ(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(name = "page", defaultValue = "1") String pageStr,
            @RequestParam(name = "limit", defaultValue = "10") String limitStr) {

        log.info("========================================");
        log.info("NLQ SEARCH REQUEST");
        log.info("Query: {}", q);
        log.info("Sort by: {}, Order: {}", sortBy, order);
        log.info("Page: {}, Limit: {}", pageStr, limitStr);

        if (q == null || q.trim().isBlank()) {
            log.warn("NLQ search failed: empty query");
            throw new InvalidQueryException("Invalid query parameter");
        }

        if (q.length() > 100) {
            log.warn("NLQ search failed: query too long ({} chars)", q.length());
            throw new InvalidQueryException("Invalid query parameter");
        }

        int page = Math.max(parseOrDefault(pageStr, 1), 1);
        int limit = Math.min(Math.max(parseOrDefault(limitStr, 10), 1), 50);

        validateSortField(sortBy);
        String internalSortField = mapSortField(sortBy);

        Pageable pageable = createPageable(page, limit, internalSortField, order);
        
        long start = System.currentTimeMillis();
        NewProfileResponseDto<Profile> response = profileManager.searchWithNLQ(q.trim(), pageable);
        long duration = System.currentTimeMillis() - start;
        
        log.info("NLQ search executed in {}ms, returned {} profiles", duration, response.getData().size());
        log.info("========================================");

        return ResponseEntity.ok(response);
    }

    // ==================== EXPORT PROFILES TO CSV ====================

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportProfiles(
            @RequestParam(name = "format", defaultValue = "csv") String format,
            QueryCriteria criteria,
            @RequestParam(name = "sort_by", defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {

        log.info("========================================");
        log.info("EXPORT PROFILES REQUEST");
        log.info("Format: {}, Sort by: {}, Order: {}", format, sortBy, order);
        log.info("Criteria: {}", criteria);

        if (!csvExportUtil.isSupportedFormat(format)) {
            log.warn("Export failed: unsupported format '{}'", format);
            throw new InvalidInputException("Only CSV format is supported");
        }

        validateSortField(sortBy);
        String internalSortField = mapSortField(sortBy);
        criteria.validate();

        Sort sort = order.equalsIgnoreCase("asc")
                ? Sort.by(internalSortField).ascending()
                : Sort.by(internalSortField).descending();

        long start = System.currentTimeMillis();
        List<Profile> profiles = profileManager.getAllProfilesForExport(criteria, sort);
        byte[] csvData = csvExportUtil.generateCsv(profiles);
        String filename = csvExportUtil.generateFilename();
        long duration = System.currentTimeMillis() - start;
        
        log.info("Export completed in {}ms, {} profiles exported to {}", duration, profiles.size(), filename);
        log.info("========================================");

        return ResponseEntity
                .ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csvData);
    }

    // ==================== CSV UPLOAD (ASYNC) ====================

    @PostMapping(value = "/upload", consumes = "text/csv")
    public ResponseEntity<Map<String, String>> uploadProfiles(HttpServletRequest request) {

        log.info("UPLOAD REQUEST RECEIVED (RAW CSV)");

        long contentLength = request.getContentLengthLong();
        String contentType = request.getContentType();

        // Validation...
        if (contentLength <= 0 || contentLength > 100 * 1024 * 1024) {
            throw new InvalidInputException("Invalid file size");
        }

        if (contentType == null || !contentType.toLowerCase().contains("csv")) {
            throw new InvalidInputException("Only CSV files accepted");
        }

        String jobId = UUID.randomUUID().toString();
        log.info("Generated jobId: {}", jobId);

        // Stream to temp file (memory efficient)
        Path tempFile;
        try {
            tempFile = Files.createTempFile("csv_upload_" + jobId, ".csv");
            Files.copy(request.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Saved CSV to temp file: {} ({} bytes)", tempFile, Files.size(tempFile));
        } catch (IOException e) {
            log.error("Failed to save temp file", e);
            throw new RuntimeException("Failed to save uploaded file", e);
        }

        try {
            jobStatusService.createJob(jobId);
            log.info("Job status stored in Redis - jobId: {}, status: processing", jobId);
        } catch (Exception e) {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            throw new RuntimeException("Could not initialize upload job", e);
        }

        CompletableFuture.supplyAsync(() -> {
                    log.info("Async task started for jobId: {}", jobId);
                    try (InputStream fileStream = Files.newInputStream(tempFile)) {
                        CsvIngestionResult result = csvIngestionService.ingest(fileStream);
                        log.info("Async task completed for jobId: {}", jobId);
                        return result;
                    } catch (Exception e) {
                        log.error("Async task failed for jobId: {}", jobId, e);
                        throw new RuntimeException(e);
                    } finally {
                        try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                    }
                }, csvIngestionExecutor)
                .thenAccept(result -> {
                    jobStatusService.completeJob(jobId, result);
                    if (result.getInserted() > 0) {
                        cacheService.evictAll();
                    }
                    log.info("Job completed: {} inserted, {} skipped", result.getInserted(), result.getSkipped());
                })
                .exceptionally(throwable -> {
                    log.error("Async processing failed for jobId: {}", jobId, throwable);
                    jobStatusService.failJob(jobId, throwable.getMessage());
                    return null;
                });

        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "jobId", jobId,
                "message", "Upload accepted. Poll /api/profiles/upload/" + jobId + "/status for progress."
        ));
    }

    // ==================== UPLOAD STATUS ====================

    @GetMapping("/upload/{jobId}/status")
    public ResponseEntity<?> getUploadStatus(@PathVariable String jobId) {
        log.debug("Checking status for jobId: {}", jobId);
        
        JobStatus jobStatus = jobStatusService.getJobStatus(jobId);

        if (jobStatus == null) {
            log.warn("Job not found: {}", jobId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "Job not found"));
        }

        log.debug("Job status for {}: {}", jobId, jobStatus.getStatus());

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

        return ResponseEntity.ok(jobStatus.getResult());
    }

    // ==================== HELPER METHODS ====================

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
        List<String> allowed = List.of(
                "age", "created_at", "createdAt", "gender_probability",
                "genderProbability", "country_id", "countryId"
        );

        if (!allowed.contains(sortBy)) {
            log.warn("Invalid sort field: {}", sortBy);
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
            log.debug("Failed to parse '{}', using default value {}", val, defaultVal);
            return defaultVal;
        }
    }
}
