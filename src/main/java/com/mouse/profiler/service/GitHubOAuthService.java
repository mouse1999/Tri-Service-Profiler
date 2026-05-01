package com.mouse.profiler.service;

import com.mouse.profiler.dto.github.GitHubDtos;
import com.mouse.profiler.entity.Role;
import com.mouse.profiler.entity.User;
import com.mouse.profiler.exception.OAuthException;
import com.mouse.profiler.repository.RoleRepository;
import com.mouse.profiler.repository.UserRepository;
import com.mouse.profiler.securityprop.GitHubOAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubOAuthService {

    private final GitHubOAuthProperties props;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WebClient webClient;

    public String exchangeCodeForToken(String code, String codeVerifier) {
        log.info("========================================");
        log.info("STEP 1: Exchanging code for GitHub token");
        log.info("Code: {}...", code != null ? code.substring(0, Math.min(10, code.length())) : "null");
        log.info("CodeVerifier present: {}", codeVerifier != null && !codeVerifier.isBlank());
        log.info("Client ID present: {}", props.getClientId() != null);
        log.info("Client Secret present: {}", props.getClientSecret() != null);
        log.info("Redirect URI: {}", props.getRedirectUri());
        log.info("========================================");

        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", props.getClientId());
            params.add("client_secret", props.getClientSecret());
            params.add("code", code);
            params.add("redirect_uri", props.getRedirectUri());

            if (codeVerifier != null && !codeVerifier.isBlank()) {
                params.add("code_verifier", codeVerifier);
                log.info("Added code_verifier to request");
            }

            log.info("Calling GitHub token endpoint: {}", GitHubOAuthProperties.TOKEN_URL);

            GitHubDtos.TokenResponse response = webClient.post()
                    .uri(GitHubOAuthProperties.TOKEN_URL)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(params))
                    .retrieve()
                    .bodyToMono(GitHubDtos.TokenResponse.class)
                    .block();

            if (response == null) {
                log.error("GitHub token response is null");
                throw new OAuthException("GitHub token exchange failed: null response");
            }

            if (response.isError()) {
                log.error("GitHub returned error: {}", response.errorDescription());
                throw new OAuthException("GitHub token exchange failed: " + response.errorDescription());
            }

            log.info("✅ GitHub token exchange successful");
            log.info("Access token: {}...", response.accessToken().substring(0, Math.min(20, response.accessToken().length())));
            return response.accessToken();

        } catch (WebClientResponseException e) {
            log.error("HTTP error calling GitHub: Status {}, Body: {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new OAuthException("GitHub API error: " + e.getStatusCode());
        } catch (Exception e) {
            log.error("Unexpected error during token exchange: {}", e.getMessage(), e);
            throw new OAuthException("Token exchange failed: " + e.getMessage());
        }
    }

    public GitHubDtos.GitHubUser fetchGitHubUser(String githubAccessToken) {
        log.info("========================================");
        log.info("STEP 2: Fetching GitHub user profile");
        log.info("Access token: {}...", githubAccessToken.substring(0, Math.min(20, githubAccessToken.length())));
        log.info("========================================");

        try {
            GitHubDtos.GitHubUser user = webClient.get()
                    .uri(GitHubOAuthProperties.USER_API_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + githubAccessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .bodyToMono(GitHubDtos.GitHubUser.class)
                    .block();

            if (user == null) {
                log.error("GitHub user response is null");
                throw new OAuthException("Failed to fetch GitHub user profile: null response");
            }

            log.info("✅ GitHub user fetched successfully");
            log.info("User ID: {}", user.id());
            log.info("Username: {}", user.login());
            log.info("Email: {}", user.email());
            log.info("Avatar URL: {}", user.avatarUrl());
            return user;

        } catch (WebClientResponseException e) {
            log.error("HTTP error fetching GitHub user: Status {}, Body: {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new OAuthException("GitHub API error: " + e.getStatusCode());
        } catch (Exception e) {
            log.error("Unexpected error fetching GitHub user: {}", e.getMessage(), e);
            throw new OAuthException("Failed to fetch GitHub user: " + e.getMessage());
        }
    }

    @Transactional
    public User upsertUser(GitHubDtos.GitHubUser githubUser) {
        log.info("========================================");
        log.info("STEP 3: Upserting user in database");
        log.info("GitHub ID: {}", githubUser.githubIdAsString());
        log.info("Username: {}", githubUser.login());
        log.info("Email: {}", githubUser.email());
        log.info("========================================");

        try {
            String githubId = githubUser.githubIdAsString();
            log.info("Looking for existing user with githubId: {}", githubId);

            return userRepository.findByGithubId(githubId)
                    .map(existing -> {
                        log.info("✅ Found existing user: {}", existing.getUsername());
                        return updateExisting(existing, githubUser);
                    })
                    .orElseGet(() -> {
                        log.info("No existing user found, creating new user");
                        return createNew(githubUser);
                    });
        } catch (Exception e) {
            log.error("Database error during upsert: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upsert user: " + e.getMessage(), e);
        }
    }

    private User updateExisting(User user, GitHubDtos.GitHubUser gh) {
        log.info("Updating existing user: {}", user.getUsername());
        log.info("  - Old username: {}", user.getUsername());
        log.info("  - New username: {}", gh.login());
        log.info("  - Old email: {}", user.getEmail());
        log.info("  - New email: {}", gh.email());

        user.setUsername(gh.login());
        user.setEmail(gh.email());
        user.setAvatarUrl(gh.avatarUrl());
        user.setLastLoginAt(LocalDateTime.now());

        log.info("Saving updated user to database...");
        User saved = userRepository.save(user);
        log.info("✅ User updated successfully: {}", saved.getUsername());
        return saved;
    }

    private User createNew(GitHubDtos.GitHubUser gh) {
        log.info("Creating new user: {}", gh.login());

        log.info("Looking for ROLE_ANALYST in database...");
        Role analystRole = roleRepository.findByName("ROLE_ANALYST")
                .orElseThrow(() -> {
                    log.error("❌ ROLE_ANALYST not found in database!");
                    log.error("Available roles should be: ROLE_ADMIN, ROLE_ANALYST");
                    return new IllegalStateException("ROLE_ANALYST not found — has DataInitializer run?");
                });

        log.info("✅ Found ROLE_ANALYST with ID: {}", analystRole.getId());

        User newUser = User.builder()
                .githubId(gh.githubIdAsString())
                .username(gh.login())
                .email(gh.email())
                .avatarUrl(gh.avatarUrl())
                .isActive(true)
                .lastLoginAt(LocalDateTime.now())
                .build();

        log.info("Building user entity with roles: ROLE_ANALYST");
        newUser.getRoles().add(analystRole);

        log.info("Saving new user to database...");
        User saved = userRepository.save(newUser);
        log.info("✅ New user created successfully!");
        log.info("  - User ID: {}", saved.getId());
        log.info("  - Username: {}", saved.getUsername());
        log.info("  - GitHub ID: {}", saved.getGithubId());
        log.info("  - Active: {}", saved.isActive());
        log.info("  - Roles count: {}", saved.getRoles().size());

        return saved;
    }
}