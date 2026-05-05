package com.mouse.profiler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.profiler.dto.CsvIngestionResult;
import com.mouse.profiler.dto.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing async CSV upload job statuses using Redis.
 *
 * <h2>Why Redis?</h2>
 * <ul>
 *   <li>Persists across application restarts</li>
 *   <li>Handles concurrent access safely</li>
 *   <li>Automatic TTL cleanup of old jobs</li>
 *   <li>Can be shared across multiple instances</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobStatusService {

    private static final String JOB_STATUS_PREFIX = "csv:job:";
    private static final String JOB_RESULT_PREFIX = "csv:result:";
    private static final Duration JOB_TTL = Duration.ofHours(24); // Jobs expire after 24 hours

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new job with "processing" status.
     *
     * @param jobId unique job identifier
     */
    public void createJob(String jobId) {
        String statusKey = JOB_STATUS_PREFIX + jobId;
        redisTemplate.opsForValue().set(statusKey, "processing", JOB_TTL);
        log.debug("Created job: {}", jobId);
    }

    /**
     * Updates job status to "completed" with the ingestion result.
     *
     * @param jobId job identifier
     * @param result ingestion result
     */
    public void completeJob(String jobId, CsvIngestionResult result) {
        try {
            String statusKey = JOB_STATUS_PREFIX + jobId;
            String resultKey = JOB_RESULT_PREFIX + jobId;

            // Update status to completed
            redisTemplate.opsForValue().set(statusKey, "completed", JOB_TTL);

            // Store result as JSON
            String resultJson = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(resultKey, resultJson, JOB_TTL);

            log.info("Job completed: {} - inserted: {}, skipped: {}",
                    jobId, result.getInserted(), result.getSkipped());
        } catch (Exception e) {
            log.error("Failed to store job result for {}: {}", jobId, e.getMessage());
            throw new RuntimeException("Failed to store job result", e);
        }
    }

    /**
     * Updates job status to "failed".
     *
     * @param jobId job identifier
     * @param errorMessage error description
     */
    public void failJob(String jobId, String errorMessage) {
        try {
            String statusKey = JOB_STATUS_PREFIX + jobId;
            String resultKey = JOB_RESULT_PREFIX + jobId;

            // Update status to failed
            redisTemplate.opsForValue().set(statusKey, "failed", JOB_TTL);

            // Store error result
            CsvIngestionResult errorResult = CsvIngestionResult.builder()
                    .status("error")
                    .totalRows(0)
                    .inserted(0)
                    .skipped(0)
                    .build();

            String resultJson = objectMapper.writeValueAsString(errorResult);
            redisTemplate.opsForValue().set(resultKey, resultJson, JOB_TTL);

            log.error("Job failed: {} - {}", jobId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to store job failure for {}: {}", jobId, e.getMessage());
        }
    }

    /**
     * Gets the current status of a job.
     *
     * @param jobId job identifier
     * @return JobStatus object containing status and result if available
     */
    public JobStatus getJobStatus(String jobId) {
        String statusKey = JOB_STATUS_PREFIX + jobId;
        String status = redisTemplate.opsForValue().get(statusKey);

        if (status == null) {
            return null;
        }

        JobStatus jobStatus = new JobStatus();
        jobStatus.setJobId(jobId);
        jobStatus.setStatus(status);

        // If completed or failed, fetch the result
        if ("completed".equals(status) || "failed".equals(status)) {
            String resultKey = JOB_RESULT_PREFIX + jobId;
            String resultJson = redisTemplate.opsForValue().get(resultKey);

            if (resultJson != null) {
                try {
                    jobStatus.setResult(objectMapper.readValue(resultJson, CsvIngestionResult.class));
                } catch (Exception e) {
                    log.warn("Failed to deserialize job result for {}: {}", jobId, e.getMessage());
                }
            }
        }

        return jobStatus;
    }

    /**
     * Deletes a job from Redis (cleanup).
     *
     * @param jobId job identifier
     */
    public void deleteJob(String jobId) {
        String statusKey = JOB_STATUS_PREFIX + jobId;
        String resultKey = JOB_RESULT_PREFIX + jobId;

        redisTemplate.delete(statusKey);
        redisTemplate.delete(resultKey);

        log.debug("Deleted job: {}", jobId);
    }


}
