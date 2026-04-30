package com.mouse.profiler.seed;

import com.mouse.profiler.entity.Role;
import com.mouse.profiler.entity.User;
import com.mouse.profiler.repository.RoleRepository;
import com.mouse.profiler.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Seeds the {@code roles} table with default roles on application startup.
 *
 * Idempotent — skips any role that already exists, so it is safe to run
 * on every startup (no duplicates, no errors on re-deploy).
 *
 * Required roles:
 * <ul>
 *   <li>{@code ROLE_ADMIN}   — full access: create/delete profiles, query</li>
 *   <li>{@code ROLE_ANALYST} — read-only: search and read only.
 *       This is the default role assigned to every new OAuth user.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    private static final List<String> DEFAULT_ROLES = List.of(
            "ROLE_ADMIN",
            "ROLE_ANALYST"
    );

    private static final String TEST_ADMIN_USERNAME = "test_admin";
    private static final String TEST_ADMIN_GITHUB_ID = "test_admin_github_123";
    private static final String TEST_ADMIN_EMAIL = "admin@test.com";
    private static final String TEST_ADMIN_AVATAR = "https://avatars.githubusercontent.com/u/1";

    private static final String TEST_ANALYST_USERNAME = "test_analyst";
    private static final String TEST_ANALYST_GITHUB_ID = "test_analyst_github_456";
    private static final String TEST_ANALYST_EMAIL = "analyst@test.com";
    private static final String TEST_ANALYST_AVATAR = "https://avatars.githubusercontent.com/u/2";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Seed roles
        DEFAULT_ROLES.forEach(roleName -> {
            if (!roleRepository.existsByName(roleName)) {
                roleRepository.save(Role.builder().name(roleName).build());
                log.info("Seeded role: {}", roleName);
            }
        });

        // Seed test admin user
        seedTestUser(
                TEST_ADMIN_USERNAME,
                TEST_ADMIN_GITHUB_ID,
                TEST_ADMIN_EMAIL,
                TEST_ADMIN_AVATAR,
                "ROLE_ADMIN"
        );

        // Seed test analyst user
        seedTestUser(
                TEST_ANALYST_USERNAME,
                TEST_ANALYST_GITHUB_ID,
                TEST_ANALYST_EMAIL,
                TEST_ANALYST_AVATAR,
                "ROLE_ANALYST"
        );
    }

    private void seedTestUser(String username, String githubId, String email, String avatarUrl, String roleName) {
        if (userRepository.findByUsername(username).isPresent()) {
            log.debug("Test user '{}' already exists, skipping", username);
            return;
        }

        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role " + roleName + " not found"));

        User user = User.builder()
                .id(UUID.randomUUID())
                .githubId(githubId)
                .username(username)
                .email(email)
                .avatarUrl(avatarUrl)
                .roles(Set.of(role))
                .isActive(true)
                .lastLoginAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        log.info("Seeded test user: {} with role {}", username, roleName);
    }
}