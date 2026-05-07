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
        log.info("Profile created successfully with ID: {}", profile.getId());

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

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadProfiles(@RequestParam("file") MultipartFile file) {

        log.info("========================================");
        log.info("UPLOAD REQUEST RECEIVED");
        log.info("========================================");

        if (file == null || file.isEmpty()) {
            log.warn("Upload rejected: file is empty or null");
            throw new InvalidInputException("File is empty or missing");
        }

        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();
        
        log.info("File details:");
        log.info("  - Name: {}", filename);
        log.info("  - Size: {} bytes ({} MB)", fileSize, fileSize / (1024 * 1024));
        log.info("  - Content type: {}", file.getContentType());

        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            log.warn("Upload rejected: invalid file type - {}", filename);
            throw new InvalidInputException("Only CSV files are accepted");
        }

        if (file.getSize() > 100 * 1024 * 1024) {
            log.warn("Upload rejected: file too large - {} MB", fileSize / (1024 * 1024));
            throw new InvalidInputException("File size exceeds maximum allowed (100MB)");
        }

        log.info("File validation passed");

        String jobId = UUID.randomUUID().toString();
        log.info("Generated jobId: {}", jobId);

        try {
            jobStatusService.createJob(jobId);
            log.info("Job status stored in Redis - jobId: {}, status: processing", jobId);
        } catch (Exception e) {
            log.error("Failed to store job status in Redis: {}", e.getMessage(), e);
            throw new RuntimeException("Could not initialize upload job", e);
        }

        log.info("Submitting CSV ingestion to thread pool - jobId: {}", jobId);
        long submitTime = System.currentTimeMillis();

        CompletableFuture.supplyAsync(() -> {
            log.info("Async task started for jobId: {}", jobId);
            long taskStart = System.currentTimeMillis();
            try {
                CsvIngestionResult result = csvIngestionService.ingest(file);
                long taskDuration = System.currentTimeMillis() - taskStart;
                log.info("Async task completed for jobId: {} - duration: {}ms", jobId, taskDuration);
                return result;
            } catch (Exception e) {
                log.error("Async task failed for jobId: {} - error: {}", jobId, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, csvIngestionExecutor)
        .thenAccept(result -> {
            log.info("Processing result for jobId: {}", jobId);
            log.info("  - Inserted: {}", result.getInserted());
            log.info("  - Skipped: {}", result.getSkipped());
            log.info("  - Total rows: {}", result.getTotalRows());
            
            try {
                jobStatusService.completeJob(jobId, result);
                log.info("Job status updated to COMPLETED in Redis - jobId: {}", jobId);
            } catch (Exception e) {
                log.error("Failed to update job status to COMPLETED: {}", e.getMessage(), e);
            }
        })
        .exceptionally(throwable -> {
            log.error("Async processing failed for jobId: {}", jobId, throwable);
            String errorMessage = throwable.getCause() != null ? 
                    throwable.getCause().getMessage() : throwable.getMessage();
            log.error("Error details: {}", errorMessage);
            
            try {
                jobStatusService.failJob(jobId, errorMessage);
                log.info("Job status updated to FAILED in Redis - jobId: {}", jobId);
            } catch (Exception e) {
                log.error("Failed to update job status to FAILED: {}", e.getMessage(), e);
            }
            return null;
        });

        long dispatchTime = System.currentTimeMillis() - submitTime;
        log.info("Async task dispatched in {}ms - jobId: {}", dispatchTime, jobId);
        log.info("Upload endpoint returning response - total time: {}ms", dispatchTime);
        log.info("========================================");

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
