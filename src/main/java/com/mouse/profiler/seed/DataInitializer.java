package com.mouse.profiler.seed;

import com.mouse.profiler.entity.Role;
import com.mouse.profiler.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    private static final List<String> DEFAULT_ROLES = List.of(
            "ROLE_ADMIN",
            "ROLE_ANALYST"
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        DEFAULT_ROLES.forEach(roleName -> {
            if (!roleRepository.existsByName(roleName)) {
                roleRepository.save(Role
                        .builder()
                        .name(roleName)
                        .build());
                log.info("Seeded role: {}", roleName);
            }
        });
    }
}
