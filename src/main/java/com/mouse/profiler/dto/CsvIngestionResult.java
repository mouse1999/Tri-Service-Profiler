package com.mouse.profiler.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Response DTO for POST /api/profiles/upload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvIngestionResult {

    private String status;

    @JsonProperty("total_rows")
    private int totalRows;

    private int inserted;
    private int skipped;

    /**
     * Counts of skipped rows by reason.
     * Keys match the spec: duplicate_name, invalid_age, invalid_gender,
     * missing_fields, malformed_row, etc.
     */
    @Builder.Default
    private Map<String, Integer> reasons = new HashMap<>();
}