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
@Slf4j
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

    log.info("========================================");
    log.info("UPLOAD REQUEST RECEIVED");
    log.info("========================================");
    
    long startTime = System.currentTimeMillis();

    // Validate file
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

    if (file.getSize() > 100 * 1024 * 1024) { // 100MB limit
        log.warn("Upload rejected: file too large - {} MB (max 100 MB)", fileSize / (1024 * 1024));
        throw new InvalidInputException("File size exceeds maximum allowed (100MB)");
    }

    log.info("File validation passed");

    // Generate unique job ID
    String jobId = UUID.randomUUID().toString();
    log.info("Generated jobId: {}", jobId);

    // Store initial processing status in Redis
    try {
        jobStatusService.createJob(jobId);
        log.info("Job status stored in Redis - jobId: {}, status: processing", jobId);
    } catch (Exception e) {
        log.error("Failed to store job status in Redis: {}", e.getMessage(), e);
        throw new RuntimeException("Could not initialize upload job", e);
    }

    // Log thread pool status before submission
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) csvIngestionExecutor;
    log.info("Thread pool status before submission:");
    log.info("  - Active threads: {}", executor.getActiveCount());
    log.info("  - Pool size: {}", executor.getPoolSize());
    log.info("  - Queue size: {}", executor.getThreadPoolExecutor().getQueue().size());

    // Process asynchronously
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
        log.info("  - Skip reasons: {}", result.getReasons());
        
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

    // Log thread pool status after submission
    log.info("Thread pool status after submission:");
    log.info("  - Active threads: {}", executor.getActiveCount());
    log.info("  - Pool size: {}", executor.getPoolSize());
    log.info("  - Queue size: {}", executor.getThreadPoolExecutor().getQueue().size());

    long totalTime = System.currentTimeMillis() - startTime;
    log.info("Upload endpoint returning response - total time: {}ms", totalTime);
    log.info("========================================");

    // Return immediately with job ID
    return ResponseEntity.accepted().body(Map.of(
            "status", "accepted",
            "jobId", jobId,
            "message", "Upload accepted. Poll /api/profiles/upload/" + jobId + "/status for progress."
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
