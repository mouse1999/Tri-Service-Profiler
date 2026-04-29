package com.mouse.profiler.dto.github;


import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTOs for GitHub API responses and controller exchanges.
 */
public final class GitHubDtos {

    private GitHubDtos() {}

    /** Response from POST <a href="https://github.com/login/oauth/access_token">...</a> */
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("scope") String scope,
            @JsonProperty("error") String error,
            @JsonProperty("error_description") String errorDescription
    ) {
        public boolean isError() { return error != null; }
    }

    /** Response from GET <a href="https://api.github.com/user">...</a> */
    public record GitHubUser(
            @JsonProperty("id") long   id,
            @JsonProperty("login") String login,
            @JsonProperty("email") String email,
            @JsonProperty("avatar_url") String avatarUrl,
            @JsonProperty("name") String name
    ) {
        public String githubIdAsString() { return String.valueOf(id); }
    }

    // ── Initiation responses ─────────────────────────────────────────────────

    /**
     * Returned by GET /auth/github for the CLI flow.
     * CLI opens {@code authorizeUrl} in the browser, saves {@code state}.
     */
    public record InitiateResponse(
            @JsonProperty("authorize_url") String authorizeUrl,
            @JsonProperty("state") String state
    ) {}

    /**
     * Returned by GET /auth/github for the browser flow.
     * Not sent as JSON — controller does an HTTP 302 redirect to authorizeUrl.
     * Defined here for documentation clarity only.
     */
    public record BrowserInitiateData(String authorizeUrl, String state) {}
}
