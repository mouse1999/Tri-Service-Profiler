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

@Slf4j
@Service
@RequiredArgsConstructor
public class CsvIngestionService {

    private static final int BATCH_SIZE = 500;
    private static final int EXPECTED_COLUMNS = 7;
    private static final int MAX_NAME_LENGTH = 100;

    private static final int COL_NAME = 0;
    private static final int COL_GENDER = 1;
    private static final int COL_AGE = 2;
    private static final int COL_COUNTRY_ID = 3;
    private static final int COL_COUNTRY_NAME = 4;
    private static final int COL_GENDER_PROB = 5;
    private static final int COL_COUNTRY_PROB = 6;

    private static final Set<String> VALID_GENDERS = Set.of("male", "female");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z\\s\\-']*$");
    private static final Pattern COMMA_SPLIT = Pattern.compile(",");
    private static final Pattern COUNTRY_CODE_PATTERN = Pattern.compile("^[A-Z]{2}$");

    private final JdbcTemplate jdbcTemplate;
    private final QueryCacheService cacheService;

    public CsvIngestionResult ingest(MultipartFile file) {
        long start = System.currentTimeMillis();
        int total = 0, inserted = 0, skipped = 0;
        int[] reasons = new int[SkipReason.VALUES.length];
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) {
                return buildResult(0, 0, 0, toReasonMap(reasons));
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    reasons[SkipReason.MALFORMED_ROW.ordinal()]++;
                    total++;
                    continue;
                }

                total++;
                Object[] row = validateRow(line, reasons);
                if (row == null) {
                    skipped++;
                    continue;
                }

                batch.add(row);

                if (batch.size() >= BATCH_SIZE) {
                    int[] result = flushBatch(batch, reasons);
                    inserted += result[0];
                    skipped += result[1];
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                int[] result = flushBatch(batch, reasons);
                inserted += result[0];
                skipped += result[1];
            }

        } catch (Exception e) {
            log.error("CSV ingestion failed: {}", e.getMessage());
        }

        if (inserted > 0) {
            cacheService.evictAll();
        }

        log.info("Ingestion done: {} rows, {} inserted, {} skipped in {}ms",
                total, inserted, skipped, System.currentTimeMillis() - start);

        return buildResult(total, inserted, skipped, toReasonMap(reasons));
    }

    private Object[] validateRow(String line, int[] reasons) {
        String[] cols = COMMA_SPLIT.split(line, -1);

        if (cols.length != EXPECTED_COLUMNS) {
            reasons[SkipReason.MALFORMED_ROW.ordinal()]++;
            return null;
        }

        String name = cols[COL_NAME].trim().toLowerCase();
        if (name.isBlank()) {
            reasons[SkipReason.MISSING_FIELDS.ordinal()]++;
            return null;
        }

        if (!NAME_PATTERN.matcher(name).matches()) {
            reasons[SkipReason.INVALID_NAME.ordinal()]++;
            return null;
        }

        if (name.length() > MAX_NAME_LENGTH) {
            reasons[SkipReason.INVALID_NAME.ordinal()]++;
            return null;
        }

        String gender = null;
        if (!cols[COL_GENDER].trim().isBlank()) {
            String g = cols[COL_GENDER].trim().toLowerCase();
            if (!VALID_GENDERS.contains(g)) {
                reasons[SkipReason.INVALID_GENDER.ordinal()]++;
                return null;
            }
            gender = g;
        }

        Integer age = null;
        String ageGroup = null;
        String ageStr = cols[COL_AGE].trim();
        if (!ageStr.isBlank()) {
            try {
                age = Integer.parseInt(ageStr);
                if (age < 0 || age > 150) {
                    reasons[SkipReason.INVALID_AGE.ordinal()]++;
                    return null;
                }
                ageGroup = AgeCategory.fromAge(age).getLabel();
            } catch (NumberFormatException e) {
                reasons[SkipReason.INVALID_AGE.ordinal()]++;
                return null;
            }
        }

        String countryId = null;
        String countryName = null;
        String rawCountryId = cols[COL_COUNTRY_ID].trim().toUpperCase();
        if (!rawCountryId.isBlank()) {
            if (!COUNTRY_CODE_PATTERN.matcher(rawCountryId).matches()) {
                reasons[SkipReason.INVALID_COUNTRY.ordinal()]++;
                return null;
            }
            countryId = rawCountryId;
            countryName = cols[COL_COUNTRY_NAME].trim().isBlank() ? null : cols[COL_COUNTRY_NAME].trim();
        }

        Float genderProb = parseProbability(cols[COL_GENDER_PROB].trim());
        Float countryProb = parseProbability(cols[COL_COUNTRY_PROB].trim());

        if (genderProb == null && !cols[COL_GENDER_PROB].trim().isBlank()) {
            reasons[SkipReason.INVALID_PROBABILITY.ordinal()]++;
            return null;
        }

        if (countryProb == null && !cols[COL_COUNTRY_PROB].trim().isBlank()) {
            reasons[SkipReason.INVALID_PROBABILITY.ordinal()]++;
            return null;
        }

        return new Object[]{
                Generators.timeBasedEpochGenerator().generate().toString(),
                name,
                gender,
                genderProb == null ? 0.0f : genderProb,
                age,
                ageGroup,
                countryId,
                countryName,
                countryProb == null ? 0.0f : countryProb
        };
    }

    private int[] flushBatch(List<Object[]> batch, int[] reasons) {
        String sql = """
                INSERT INTO profiles
                    (id, name, gender, gender_probability, age, age_group,
                     country_id, country_name, country_probability, created_at)
                VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (name) DO NOTHING
                """;

        try {
            int[] results = jdbcTemplate.batchUpdate(sql, batch);
            int inserted = 0;
            for (int r : results) {
                if (r > 0) inserted++;
            }
            int duplicates = batch.size() - inserted;
            if (duplicates > 0) {
                reasons[SkipReason.DUPLICATE_NAME.ordinal()] += duplicates;
            }
            return new int[]{inserted, duplicates};
        } catch (Exception e) {
            log.error("Batch failed: {}", e.getMessage());
            reasons[SkipReason.DUPLICATE_NAME.ordinal()] += batch.size();
            return new int[]{0, batch.size()};
        }
    }

    private Float parseProbability(String value) {
        if (value.isBlank()) {
            return null;
        }
        try {
            float f = Float.parseFloat(value);
            if (f < 0.0f || f > 1.0f) {
                return null;
            }
            return f;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Integer> toReasonMap(int[] counts) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("duplicate_name", counts[SkipReason.DUPLICATE_NAME.ordinal()]);
        map.put("invalid_age", counts[SkipReason.INVALID_AGE.ordinal()]);
        map.put("invalid_gender", counts[SkipReason.INVALID_GENDER.ordinal()]);
        map.put("invalid_name", counts[SkipReason.INVALID_NAME.ordinal()]);
        map.put("invalid_country", counts[SkipReason.INVALID_COUNTRY.ordinal()]);
        map.put("invalid_probability", counts[SkipReason.INVALID_PROBABILITY.ordinal()]);
        map.put("missing_fields", counts[SkipReason.MISSING_FIELDS.ordinal()]);
        map.put("malformed_row", counts[SkipReason.MALFORMED_ROW.ordinal()]);
        return map;
    }

    private CsvIngestionResult buildResult(int total, int inserted, int skipped, Map<String, Integer> reasons) {
        return CsvIngestionResult.builder()
                .status("success")
                .totalRows(total)
                .inserted(inserted)
                .skipped(skipped)
                .reasons(reasons)
                .build();
    }
}