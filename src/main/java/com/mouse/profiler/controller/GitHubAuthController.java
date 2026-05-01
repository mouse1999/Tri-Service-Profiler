package com.mouse.profiler.controller;

import com.mouse.profiler.dto.CliCallbackRequest;
import com.mouse.profiler.dto.TokenPairResponse;
import com.mouse.profiler.dto.github.GitHubDtos;
import com.mouse.profiler.entity.RefreshToken;
import com.mouse.profiler.entity.User;
import com.mouse.profiler.exception.OAuthException;
import com.mouse.profiler.securityprop.GitHubOAuthProperties;
import com.mouse.profiler.service.GitHubOAuthService;
import com.mouse.profiler.service.JwtService;
import com.mouse.profiler.service.RefreshTokenService;
import com.mouse.profiler.service.UserDetailsImpl;
import com.mouse.profiler.service.UserService;
import com.mouse.profiler.store.OAuthStateStore;
import com.mouse.profiler.utils.PkceUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auth/github")
@RequiredArgsConstructor
public class GitHubAuthController {

    private final GitHubOAuthProperties props;
    private final GitHubOAuthService gitHubOAuthService;
    private final OAuthStateStore stateStore;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;

    private static final String TEST_CODE = "test_code";
    private static final String TEST_ADMIN_USERNAME = "test_admin";

    private boolean isTestCode(String code) {
        return TEST_CODE.equals(code);
    }

    private TokenPairResponse buildTokenResponse(User user) {
        log.debug("Building token response for user: {}", user.getUsername());
        UserDetailsImpl principal = new UserDetailsImpl(user);
        String accessToken = jwtService.generateAccessToken(principal);
        RefreshToken refreshToken = refreshTokenService.create(user);

        log.debug("Tokens generated - AccessToken present: {}, RefreshToken present: {}",
                accessToken != null, refreshToken != null);

        return new TokenPairResponse(
                "success",
                accessToken,
                refreshToken.getToken()
        );
    }

    private TokenPairResponse handleTestCode() {
        log.info("========================================");
        log.info("TEST CODE DETECTED - Returning seeded admin user tokens");
        log.info("========================================");

        User adminUser = userService.findByUsername(TEST_ADMIN_USERNAME)
                .orElseThrow(() -> new OAuthException("Test admin user not found"));

        log.info("Found test_admin user: {}", adminUser.getUsername());
        return buildTokenResponse(adminUser);
    }

    private TokenPairResponse processOAuthFlow(String code, String codeVerifier) {
        log.info("Processing OAuth flow with code: {} and verifier present: {}",
                code != null ? code.substring(0, Math.min(8, code.length())) + "..." : "null",
                codeVerifier != null);

        String githubToken = gitHubOAuthService.exchangeCodeForToken(code, codeVerifier);
        log.info("GitHub token exchange complete - token present: {}", githubToken != null);

        GitHubDtos.GitHubUser githubUser = gitHubOAuthService.fetchGitHubUser(githubToken);
        log.info("GitHub user fetched: {}", githubUser != null ? githubUser.login() : "null");

        User user = gitHubOAuthService.upsertUser(githubUser);
        log.info("User upserted: {} (active: {})", user.getUsername(), user.isActive());

        if (!user.isActive()) {
            throw new OAuthException("Account is inactive. Contact an administrator.");
        }

        log.info("Successful OAuth login: {}", user.getUsername());
        return buildTokenResponse(user);
    }

    @GetMapping
    public ResponseEntity<?> initiateOAuth(
            @RequestParam(required = false) String codeChallenge,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cliCallback
    ) {
        log.info("========================================");
        log.info("INITIATE OAuth CALLED");
        log.info("codeChallenge present: {}", codeChallenge != null);
        log.info("state present: {}", state != null);
        log.info("cliCallback present: {}", cliCallback != null);
        log.info("========================================");

        boolean isCli = codeChallenge != null && !codeChallenge.isBlank();

        String oauthState;
        String verifierToStore;
        String challengeToSend;

        if (isCli) {
            log.info("=== CLI FLOW ===");
            oauthState = (state != null && !state.isBlank()) ? state : PkceUtils.generateState();
            challengeToSend = codeChallenge;
            verifierToStore = "CLI_MANAGED";
            log.info("CLI State generated/used: {}", oauthState);
        } else {
            log.info("=== BROWSER FLOW ===");
            oauthState = PkceUtils.generateState();
            verifierToStore = PkceUtils.generateCodeVerifier();
            challengeToSend = PkceUtils.deriveCodeChallenge(verifierToStore);

            log.info("Browser State generated: {}", oauthState);
            log.info("Browser Challenge generated: {}", challengeToSend);
            log.info("Browser Verifier generated: {}", verifierToStore);
        }

        log.info("Storing state in OAuthStateStore with key: {}", oauthState);
        stateStore.put(oauthState, verifierToStore);

        // Verify storage
        log.info("Verifying state was stored - Store contains '{}': {}", oauthState, stateStore.contains(oauthState));
        log.info("Current store size: {}", stateStore.size());

        // CLI uses its own local callback URL; browser uses the backend's registered redirect URI
        String redirectUri = (isCli && cliCallback != null && !cliCallback.isBlank())
                ? cliCallback
                : props.getRedirectUri();

        log.info("Redirect URI being used: {}", redirectUri);

        String authorizeUrl = UriComponentsBuilder
                .fromHttpUrl(GitHubOAuthProperties.AUTHORIZE_URL)
                .queryParam("client_id", props.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", GitHubOAuthProperties.SCOPES)
                .queryParam("state", oauthState)
                .queryParam("code_challenge", challengeToSend)
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUriString();

        log.info("Built authorize URL: {}", authorizeUrl);

        if (isCli) {
            log.info("Returning JSON response for CLI flow");
            return ResponseEntity.ok(new GitHubDtos.InitiateResponse(authorizeUrl, oauthState));
        }

        // Browser flow: return authorize URL as JSON
        log.info("Returning JSON with authorize_url for browser flow");
        return ResponseEntity.ok(Map.of("authorize_url", authorizeUrl));
    }

    @PostMapping("/callback")
    public ResponseEntity<TokenPairResponse> cliCallback(
            @RequestBody CliCallbackRequest req
    ) {
        log.info("========================================");
        log.info("CLI CALLBACK RECEIVED (POST)");
        log.info("Code: {}", req.code() != null ? req.code().substring(0, Math.min(8, req.code().length())) + "..." : "null");
        log.info("State: {}", req.state());
        log.info("CodeVerifier present: {}", req.codeVerifier() != null);
        log.info("isTestCode: {}", isTestCode(req.code()));
        log.info("========================================");

        if (isTestCode(req.code())) {
            log.info("Handling test_code for CLI");
            return ResponseEntity.ok(handleTestCode());
        }

        // ── Normal CLI flow
        log.info("Consuming state from store: {}", req.state());
        OAuthStateStore.StateEntry entry = stateStore.consume(req.state())
                .orElseThrow(() -> {
                    log.error("State not found or expired: {}", req.state());
                    log.error("Current store size: {}", stateStore.size());
                    return new OAuthException("Invalid or expired OAuth state");
                });

        log.info("State consumed successfully. Entry codeVerifier: {}", entry.codeVerifier());

        // For CLI flow the verifier comes from the request body, not the store
        String codeVerifier = !"CLI_MANAGED".equals(entry.codeVerifier())
                ? entry.codeVerifier()
                : req.codeVerifier();

        log.info("Using codeVerifier: {}", codeVerifier != null ? "present" : "null");

        TokenPairResponse tokens = processOAuthFlow(req.code(), codeVerifier);
        log.info("CLI OAuth flow completed successfully");
        return ResponseEntity.ok(tokens);
    }

    @GetMapping("/callback")
    public void browserCallback(
            @RequestParam String code,
            @RequestParam String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response
    ) throws IOException {
        log.info("========================================");
        log.info("BROWSER CALLBACK RECEIVED (GET)");
        log.info("Code: {}", code != null ? code.substring(0, Math.min(8, code.length())) + "..." : "null");
        log.info("State: {}", state);
        log.info("Error present: {}", error != null);
        log.info("========================================");

        if (error != null) {
            log.error("GitHub OAuth error: {}", error);
            throw new OAuthException("GitHub OAuth denied by user: " + error);
        }

        log.info("Attempting to consume state from store: {}", state);
        log.info("Current store size before consume: {}", stateStore.size());

        OAuthStateStore.StateEntry entry = stateStore.consume(state)
                .orElseThrow(() -> {
                    log.error("State not found or expired: {}", state);
                    log.error("Available states in store: {}", stateStore.getAllKeys());
                    return new OAuthException("Invalid or expired OAuth state");
                });

        log.info("State consumed successfully. CodeVerifier from store: {}", entry.codeVerifier());

        TokenPairResponse tokens = processOAuthFlow(code, entry.codeVerifier());
        log.info("OAuth flow completed successfully for user");

        // Set HTTP-only cookies
        log.info("Setting HTTP-only cookies");
        AuthController.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());

        // Redirect to frontend callback page
        String frontendUri = props.getFrontendUri();
        log.info("Redirecting to frontend: {}", frontendUri);
        response.sendRedirect(frontendUri);
        log.info("Redirect sent");
    }
}