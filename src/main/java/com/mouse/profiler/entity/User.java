package com.mouse.profiler.entity;

import com.fasterxml.uuid.Generators;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_user_github_id", columnList = "github_id", unique = true),
        @Index(name = "idx_user_username", columnList = "username", unique = true),
        @Index(name = "idx_user_email", columnList = "email"),
        @Index(name = "idx_user_last_login", columnList = "last_login_at"),
        @Index(name = "idx_user_is_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "github_id", unique = true, nullable = false, length = 100)
    private String githubId;

    @Column(unique = true, nullable = false, length = 100)
    private String username;

    @Column(length = 150)
    private String email;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"),
            indexes = {
                    @Index(name = "idx_user_roles_user_id", columnList = "user_id"),
                    @Index(name = "idx_user_roles_role_id", columnList = "role_id")
            }
    )
    private Set<Role> roles = new HashSet<>();

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();

        if (this.id == null) {
            this.id = Generators.timeBasedEpochGenerator().generate();
        }
    }
}