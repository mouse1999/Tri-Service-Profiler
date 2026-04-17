package com.mouse.profiler.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "profiles", indexes = {
        @Index(name = "idx_profile_name", columnList = "name", unique = true),
        @Index(name = "idx_profile_filters", columnList = "gender, country_id, age_group")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Use a unique constraint here as well for database-level safety
    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String gender;

    @Column(name = "gender_probability", nullable = false)
    private Double genderProbability;

    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize;

    @Column(nullable = false)
    private Integer age;

    @Column(name = "age_group", nullable = false)
    private String ageGroup;

    @Column(name = "country_id", nullable = false)
    private String countryId;

    @Column(name = "country_probability", nullable = false)
    private Double countryProbability;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;
}