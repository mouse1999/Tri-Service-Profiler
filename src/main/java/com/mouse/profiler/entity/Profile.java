package com.mouse.profiler.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity representing a demographic profile.
 * - Optimized with indexes for JPA Specifications.
 * - Jackson annotations ensure mapping from snake_case JSON.
 * - UUID v7 used for primary keys.
 */
@Entity
@Table(name = "profiles", indexes = {
        // Unique index for the name check during data seeding
        @Index(name = "idx_profile_name", columnList = "name", unique = true),

        // Composite index for common filtering (Gender + Country + Age Group)
        @Index(name = "idx_profile_lookup", columnList = "gender, country_id, age_group"),

        // Separate index for range-based age filtering (min_age / max_age)
        @Index(name = "idx_profile_age", columnList = "age")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Profile {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id; // UUID v7 (assigned by DataSeeder)

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String gender;

    @Column(name = "gender_probability", nullable = false)
    @JsonProperty("gender_probability")
    private Float genderProbability;

    @Column(nullable = false)
    private Integer age;

    @Column(name = "age_group", nullable = false)
    @JsonProperty("age_group")
    private String ageGroup; // child, teenager, adult, senior

    @Column(name = "country_id", length = 2, nullable = false)
    @JsonProperty("country_id")
    private String countryId; // ISO code (e.g., NG, AU)

    @Column(name = "country_name", nullable = false)
    @JsonProperty("country_name")
    private String countryName;

    @Column(name = "country_probability", nullable = false)
    @JsonProperty("country_probability")
    private Float countryProbability;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * sampleSize is kept for compatibility with legacy logic
     * but is not persisted in the database.
     */
    @Transient
    private Integer sampleSize;
}