package com.mouse.profiler.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.uuid.Generators;
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
        @Index(name = "idx_profile_name", columnList = "name", unique = true),
        @Index(name = "idx_profile_lookup", columnList = "gender, country_id, age_group"),
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
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    // Optional fields - can be null
    private String gender;

    @Column(name = "gender_probability")
    @JsonProperty("gender_probability")
    private Float genderProbability;

    private Integer age;

    @Column(name = "age_group")
    @JsonProperty("age_group")
    private String ageGroup;

    @Column(name = "country_id", length = 2)
    @JsonProperty("country_id")
    private String countryId;

    @Column(name = "country_name")
    @JsonProperty("country_name")
    private String countryName;

    @Column(name = "country_probability")
    @JsonProperty("country_probability")
    private Float countryProbability;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Transient
    private Integer sampleSize;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = Generators.timeBasedEpochGenerator().generate();
        }
    }
}