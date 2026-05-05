package com.mouse.profiler.service;

import com.fasterxml.uuid.Generators;
import com.mouse.profiler.dto.CsvIngestionResult;
import com.mouse.profiler.enums.AgeCategory;
import com.mouse.profiler.enums.SkipReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * CSV Ingestion Service for batch profile uploads.
 *
 * <h2>Design Decisions</h2>
 * <ul>
 *   <li><b>Streaming:</b> BufferedReader reads line by line - O(1) memory</li>
 *   <li><b>Batch inserts:</b> 500 rows per batch - reduces DB round trips</li>
 *   <li><b>ON CONFLICT:</b> Database-level duplicate detection - no race conditions</li>
 *   <li><b>No pre-load:</b> Doesn't load existing names - saves memory (~10MB)</li>
 *   <li><b>Continue on error:</b> Invalid rows skipped, upload continues</li>
 *   <li><b>No rollback:</b> Each batch independent - committed rows persist</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvIngestionService {

    // Batch size: 500 rows per database insert (balance between memory and performance)
    private static final int BATCH_SIZE = 500;

    // Expected CSV columns: name,gender,age,country_id,country_name,gender_probability,country_probability
    private static final int EXPECTED_COLUMNS = 7;

    // Column indices
    private static final int COL_NAME = 0;
    private static final int COL_GENDER = 1;
    private static final int COL_AGE = 2;
    private static final int COL_COUNTRY_ID = 3;
    private static final int COL_COUNTRY_NAME = 4;
    private static final int COL_GENDER_PROB = 5;
    private static final int COL_COUNTRY_PROB = 6;

    // Validations
    private static final Set<String> VALID_GENDERS = Set.of("male", "female");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z\\s\\-']*$");
    private static final Pattern COMMA_SPLIT = Pattern.compile(",");

    // Country code pattern (2 letters)
    private static final Pattern COUNTRY_CODE_PATTERN = Pattern.compile("^[A-Z]{2}$");

    private final JdbcTemplate jdbcTemplate;
    private final QueryCacheService cacheService;

    /**
     * Ingests a CSV file with streaming and batch processing.
     *
     * @param file the uploaded CSV file
     * @return ingestion summary with inserted/skipped counts and reasons
     */
    public CsvIngestionResult ingest(MultipartFile file) {
        long startTime = System.currentTimeMillis();

        int totalRows = 0;
        int inserted = 0;
        int skipped = 0;
        int[] skipCounts = new int[SkipReason.VALUES.length];

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            // Read and validate header
            String headerLine = reader.readLine();
            if (headerLine == null) {
                log.warn("CSV file is empty");
                return buildResult(0, 0, 0, toReasonMap(skipCounts));
            }

            log.info("CSV header: {}", headerLine);

            String line;
            while ((line = reader.readLine()) != null) {
                // Skip empty lines
                if (line.isBlank()) {
                    continue;
                }

                totalRows++;

                // Validate and map row to database columns
                Object[] row = validateAndMap(line, skipCounts);
                if (row == null) {
                    skipped++;
                    continue;
                }

                batch.add(row);

                // Flush batch when full
                if (batch.size() >= BATCH_SIZE) {
                    int[] result = flushBatch(batch, skipCounts);
                    inserted += result[0];
                    skipped += result[1];
                    batch.clear();
                }
            }

            // Flush remaining rows
            if (!batch.isEmpty()) {
                int[] result = flushBatch(batch, skipCounts);
                inserted += result[0];
                skipped += result[1];
                batch.clear();
            }

        } catch (Exception e) {
            log.error("Fatal error during CSV ingestion: {}", e.getMessage(), e);
            // Rows already inserted remain committed
        }

        // Evict cache only if new profiles were added
        if (inserted > 0) {
            cacheService.evictAll();
            log.info("Cache evicted after {} new profile inserts", inserted);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("CSV ingestion complete — total: {}, inserted: {}, skipped: {}, duration: {}ms",
                totalRows, inserted, skipped, duration);

        return buildResult(totalRows, inserted, skipped, toReasonMap(skipCounts));
    }

    /**
     * Validates a single CSV row and maps it to database column values.
     *
     * @param line the CSV line to validate
     * @param skipCounts array to track skip reasons
     * @return Object[] of database values, or null if row should be skipped
     */
    private Object[] validateAndMap(String line, int[] skipCounts) {
        String[] cols = COMMA_SPLIT.split(line, -1);

        // Check column count
        if (cols.length != EXPECTED_COLUMNS) {
            skipCounts[SkipReason.MALFORMED_ROW.ordinal()]++;
            log.debug("Malformed row: expected {} columns, got {}", EXPECTED_COLUMNS, cols.length);
            return null;
        }

        // Extract values
        String name = cols[COL_NAME].trim().toLowerCase();
        String gender = cols[COL_GENDER].trim().toLowerCase();
        String ageStr = cols[COL_AGE].trim();
        String countryId = cols[COL_COUNTRY_ID].trim().toUpperCase();
        String countryName = cols[COL_COUNTRY_NAME].trim();
        String genderProbStr = cols[COL_GENDER_PROB].trim();
        String countryProbStr = cols[COL_COUNTRY_PROB].trim();

        if (name.isBlank()) {
            skipCounts[SkipReason.MISSING_FIELDS.ordinal()]++;
            log.debug("Skipped row: missing name");
            return null;
        }

        // Validate name format (letters, spaces, hyphens, apostrophes)
        if (!NAME_PATTERN.matcher(name).matches()) {
            skipCounts[SkipReason.INVALID_NAME.ordinal()]++;
            log.debug("Skipped row: invalid name format '{}'", name);
            return null;
        }


        String validatedGender = null;
        if (!gender.isBlank()) {
            if (!VALID_GENDERS.contains(gender)) {
                skipCounts[SkipReason.INVALID_GENDER.ordinal()]++;
                log.debug("Skipped row: invalid gender '{}'", gender);
                return null;
            }
            validatedGender = gender;
        }

        Integer age = null;
        String ageGroup = null;
        if (!ageStr.isBlank()) {
            try {
                age = Integer.parseInt(ageStr);
                if (age < 0 || age > 150) {
                    skipCounts[SkipReason.INVALID_AGE.ordinal()]++;
                    log.debug("Skipped row: invalid age '{}' (must be 0-150)", age);
                    return null;
                }
                ageGroup = AgeCategory.fromAge(age).getLabel();
            } catch (NumberFormatException e) {
                skipCounts[SkipReason.INVALID_AGE.ordinal()]++;
                log.debug("Skipped row: non-numeric age '{}'", ageStr);
                return null;
            }
        }

        // ========== OPTIONAL: COUNTRY ==========
        String validatedCountryId = null;
        String validatedCountryName = null;
        if (!countryId.isBlank()) {
            if (!COUNTRY_CODE_PATTERN.matcher(countryId).matches()) {
                skipCounts[SkipReason.INVALID_COUNTRY.ordinal()]++;
                log.debug("Skipped row: invalid country code '{}' (must be 2 letters)", countryId);
                return null;
            }
            validatedCountryId = countryId;
            validatedCountryName = countryName.isBlank() ? null : countryName;
        }


        float genderProb = parseFloatSafe(genderProbStr);
        float countryProb = parseFloatSafe(countryProbStr);

        // Validate probability ranges if provided
        if (!genderProbStr.isBlank() && (genderProb < 0 || genderProb > 1)) {
            skipCounts[SkipReason.INVALID_PROBABILITY.ordinal()]++;
            log.debug("Skipped row: invalid gender probability '{}' (must be 0-1)", genderProbStr);
            return null;
        }

        if (!countryProbStr.isBlank() && (countryProb < 0 || countryProb > 1)) {
            skipCounts[SkipReason.INVALID_PROBABILITY.ordinal()]++;
            log.debug("Skipped row: invalid country probability '{}' (must be 0-1)", countryProbStr);
            return null;
        }

        String uuid = Generators.timeBasedEpochGenerator().generate().toString();

        return new Object[]{
                uuid,                    // id (CAST to UUID)
                name,                    // name
                validatedGender,         // gender (may be null)
                genderProb,              // gender_probability
                age,                     // age (may be null)
                ageGroup,                // age_group (may be null)
                validatedCountryId,      // country_id (may be null)
                validatedCountryName,    // country_name (may be null)
                countryProb              // country_probability
        };
    }

    /**
     * Flushes a batch of rows to the database using JDBC batch update.
     * Uses ON CONFLICT to handle duplicates at the database level.
     *
     * @param batch the batch of rows to insert
     * @param skipCounts array to track duplicate counts
     * @return int[2] where [0] = inserted count, [1] = duplicate count
     */
    private int[] flushBatch(List<Object[]> batch, int[] skipCounts) {
        String sql = """
                INSERT INTO profiles
                    (id, name, gender, gender_probability, age, age_group,
                     country_id, country_name, country_probability, created_at)
                VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (name) DO NOTHING
                """;

        try {
            int[] results = jdbcTemplate.batchUpdate(sql, batch);

            // Count actual inserts (result > 0 means row was inserted)
            int inserted = 0;
            for (int result : results) {
                if (result > 0) {
                    inserted++;
                }
            }

            int duplicates = batch.size() - inserted;
            if (duplicates > 0) {
                skipCounts[SkipReason.DUPLICATE_NAME.ordinal()] += duplicates;
                log.debug("Batch: {} inserted, {} duplicates", inserted, duplicates);
            }

            return new int[]{inserted, duplicates};

        } catch (Exception e) {
            log.error("Batch insert failed: {}. Batch of {} rows skipped.", e.getMessage(), batch.size());
            // Count all rows in batch as duplicates (since they weren't inserted)
            skipCounts[SkipReason.DUPLICATE_NAME.ordinal()] += batch.size();
            return new int[]{0, batch.size()};
        }
    }

    /**
     * Safely parses a float value, returning 0.0f on error.
     */
    private float parseFloatSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0.0f;
        }
        try {
            float f = Float.parseFloat(value);
            return Math.max(0.0f, Math.min(1.0f, f)); // Clamp to [0,1]
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    /**
     * Converts the skip reason counts array to a map for the response DTO.
     */
    private Map<String, Integer> toReasonMap(int[] counts) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("duplicate_name",   counts[SkipReason.DUPLICATE_NAME.ordinal()]);
        map.put("invalid_age",      counts[SkipReason.INVALID_AGE.ordinal()]);
        map.put("invalid_gender",   counts[SkipReason.INVALID_GENDER.ordinal()]);
        map.put("invalid_name",     counts[SkipReason.INVALID_NAME.ordinal()]);
        map.put("invalid_country",  counts[SkipReason.INVALID_COUNTRY.ordinal()]);
        map.put("invalid_probability", counts[SkipReason.INVALID_PROBABILITY.ordinal()]);
        map.put("missing_fields",   counts[SkipReason.MISSING_FIELDS.ordinal()]);
        map.put("malformed_row",    counts[SkipReason.MALFORMED_ROW.ordinal()]);
        return map;
    }

    /**
     * Builds the ingestion result DTO.
     */
    private CsvIngestionResult buildResult(int totalRows, int inserted, int skipped,
                                           Map<String, Integer> reasons) {
        return CsvIngestionResult.builder()
                .status("success")
                .totalRows(totalRows)
                .inserted(inserted)
                .skipped(skipped)
                .reasons(reasons)
                .build();
    }
}