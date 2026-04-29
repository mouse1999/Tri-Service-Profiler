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

import java.time.LocalDateTime;

/**
 * Handles the GitHub OAuth token exchange and user provisioning.
 *
 * Responsibilities:
 * <ol>
 *   <li>Exchange authorization {@code code} (+ optional PKCE {@code code_verifier})
 *       for a GitHub access token.</li>
 *   <li>Fetch GitHub user profile from the API.</li>
 *   <li>Upsert the user in our DB — create with {@code ROLE_ANALYST} if new,
 *       update {@code lastLoginAt} if existing.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubOAuthService {

    private final GitHubOAuthProperties props;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WebClient webClient;

    // ── Step 1: Exchange code for GitHub access token ────────────────────────

    /**
     * Calls GitHub's token endpoint to exchange the authorization code for
     * a GitHub access token.
     *
     * @param code authorization code from GitHub callback
     * @param codeVerifier PKCE verifier (CLI flow) or null (browser flow)
     * @return GitHub access token string
     * @throws OAuthException if GitHub returns an error
     */
    public String exchangeCodeForToken(String code, String codeVerifier) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", props.getClientId());
        params.add("client_secret", props.getClientSecret());
        params.add("code", code);
        params.add("redirect_uri", props.getRedirectUri());

        // PKCE: only add code_verifier if present
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            params.add("code_verifier", codeVerifier);
        }

        GitHubDtos.TokenResponse response = webClient.post()
                .uri(GitHubOAuthProperties.TOKEN_URL)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(params))
                .retrieve()
                .bodyToMono(GitHubDtos.TokenResponse.class)
                .block();

        if (response == null || response.isError()) {
            String reason = response != null ? response.errorDescription() : "null response";
            log.error("GitHub token exchange failed: {}", reason);
            throw new OAuthException("GitHub token exchange failed: " + reason);
        }

        return response.accessToken();
    }

    // ── Step 2: Fetch GitHub user profile

    /**
     * Fetches the authenticated user's profile from the GitHub API.
     *
     * @param githubAccessToken short-lived token from Step 1
     */
    public GitHubDtos.GitHubUser fetchGitHubUser(String githubAccessToken) {
        GitHubDtos.GitHubUser user = webClient.get()
                .uri(GitHubOAuthProperties.USER_API_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + githubAccessToken)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .bodyToMono(GitHubDtos.GitHubUser.class)
                .block();

        if (user == null) {
            throw new OAuthException("Failed to fetch GitHub user profile");
        }
        return user;
    }

    // ── Step 3: Upsert user

    /**
     * Creates or updates the Insighta user record from the GitHub profile.
     *
     * <p>New users are automatically assigned {@code ROLE_ANALYST}.
     * Existing users get their profile fields synced and {@code lastLoginAt} updated.
     *
     * @param githubUser profile data from the GitHub API
     * @return the persisted {@link User} entity
     */
    @Transactional
    public User upsertUser(GitHubDtos.GitHubUser githubUser) {
        return userRepository.findByGithubId(githubUser.githubIdAsString())
                .map(existing -> updateExisting(existing, githubUser))
                .orElseGet(() -> createNew(githubUser));
    }

    // ── Private helpers --

    private User updateExisting(User user, GitHubDtos.GitHubUser gh) {
        // Sync mutable profile fields from GitHub
        user.setUsername(gh.login());
        user.setEmail(gh.email());
        user.setAvatarUrl(gh.avatarUrl());
        user.setLastLoginAt(LocalDateTime.now());
        log.debug("Updated existing user: {}", user.getUsername());
        return userRepository.save(user);
    }

    private User createNew(GitHubDtos.GitHubUser gh) {

        Role analystRole = roleRepository.findByName("ROLE_ANALYST")
                .orElseThrow(() -> new IllegalStateException(
                        "ROLE_ANALYST not found — has DataInitializer run?"));

        User newUser = User.builder()
                .githubId(gh.githubIdAsString())
                .username(gh.login())
                .email(gh.email())
                .avatarUrl(gh.avatarUrl())
                .isActive(true)
                .lastLoginAt(LocalDateTime.now())
                .build();

        newUser.getRoles().add(analystRole);

        User saved = userRepository.save(newUser);
        log.info("Created new user: {} (githubId={})", saved.getUsername(), saved.getGithubId());
        return saved;
    }
}