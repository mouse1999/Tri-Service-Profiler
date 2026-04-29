package com.mouse.profiler.utils;

import com.mouse.profiler.entity.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * Flexible CSV utility with customizable columns and formatting.
 */
@Component
public class CsvExportUtil {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String DEFAULT_DELIMITER = ",";
    private static final String DEFAULT_NEW_LINE = "\n";
    private static final String DEFAULT_QUOTE = "\"";
    private static final String DEFAULT_DOUBLE_QUOTE = "\"\"";
    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * Generate CSV from profiles with default settings
     */
    public byte[] generateCsv(List<Profile> profiles) {
        return new CsvBuilder(profiles)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generate CSV with custom configuration
     */
    public CsvBuilder builder(List<Profile> profiles) {
        return new CsvBuilder(profiles);
    }

    /**
     * Generate filename with timestamp
     */
    public String generateFilename() {
        return "profiles_" + LocalDateTime.now().format(TIMESTAMP_FORMATTER) + ".csv";
    }

    /**
     * Check if format is supported
     */
    public boolean isSupportedFormat(String format) {
        return format != null && "csv".equalsIgnoreCase(format);
    }

    /**
     * Builder class for flexible CSV generation
     */
    public static class CsvBuilder {
        private final List<Profile> profiles;
        private String delimiter = DEFAULT_DELIMITER;
        private String newLine = DEFAULT_NEW_LINE;
        private String quote = DEFAULT_QUOTE;
        private String doubleQuote = DEFAULT_DOUBLE_QUOTE;
        private boolean includeHeader = true;
        private String[] columns = {
                "id", "name", "gender", "gender_probability",
                "age", "age_group", "country_id", "country_name",
                "country_probability", "created_at"
        };

        public CsvBuilder(List<Profile> profiles) {
            this.profiles = profiles;
        }

        public CsvBuilder withDelimiter(String delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        public CsvBuilder withoutHeader() {
            this.includeHeader = false;
            return this;
        }

        public CsvBuilder withCustomColumns(String[] columns) {
            this.columns = columns;
            return this;
        }

        public String build() {
            StringBuilder csv = new StringBuilder();

            // Add header if requested
            if (includeHeader) {
                csv.append(String.join(delimiter, columns)).append(newLine);
            }

            // Add data rows
            for (Profile profile : profiles) {
                csv.append(formatValue(profile.getId().toString())).append(delimiter)
                        .append(formatValue(profile.getName())).append(delimiter)
                        .append(formatValue(profile.getGender())).append(delimiter)
                        .append(profile.getGenderProbability() != null ? profile.getGenderProbability() : "").append(delimiter)
                        .append(profile.getAge() != null ? profile.getAge() : "").append(delimiter)
                        .append(formatValue(profile.getAgeGroup())).append(delimiter)
                        .append(formatValue(profile.getCountryId())).append(delimiter)
                        .append(formatValue(profile.getCountryName())).append(delimiter)
                        .append(profile.getCountryProbability() != null ? profile.getCountryProbability() : "").append(delimiter)
                        .append(profile.getCreatedAt() != null ? profile.getCreatedAt().toString() : "")
                        .append(newLine);
            }

            return csv.toString();
        }

        private String formatValue(String value) {
            if (value == null || value.isEmpty()) return "";

            boolean needsQuoting = value.contains(delimiter) ||
                    value.contains(quote) ||
                    value.contains(newLine);

            if (needsQuoting) {
                String escaped = value.replace(quote, doubleQuote);
                return quote + escaped + quote;
            }

            return value;
        }
    }
}