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
        return new TokenPairResponse("success", accessToken, refreshToken.getToken());
    }

    private TokenPairResponse handleTestCode() {
        log.info("TEST CODE DETECTED - Returning seeded admin user tokens");
        User adminUser = userService.findByUsername(TEST_ADMIN_USERNAME)
                .orElseThrow(() -> new OAuthException("Test admin user not found"));
        return buildTokenResponse(adminUser);
    }

    private TokenPairResponse processOAuthFlow(String code, String codeVerifier) {
        log.info("Processing OAuth flow - verifier present: {}", codeVerifier != null);
        String githubToken = gitHubOAuthService.exchangeCodeForToken(code, codeVerifier);
        GitHubDtos.GitHubUser githubUser = gitHubOAuthService.fetchGitHubUser(githubToken);
        User user = gitHubOAuthService.upsertUser(githubUser);

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
        log.info("INITIATE OAuth CALLED - codeChallenge: {}, state: {}, cliCallback: {}",
                codeChallenge != null, state != null, cliCallback != null);

        boolean isCli = codeChallenge != null && !codeChallenge.isBlank();

        String oauthState;
        String verifierToStore;
        String challengeToSend;

        if (isCli) {
            log.info("=== CLI FLOW ===");
            oauthState = (state != null && !state.isBlank()) ? state : PkceUtils.generateState();
            challengeToSend = codeChallenge;
            verifierToStore = "CLI_MANAGED";
        } else {
            log.info("=== BROWSER FLOW ===");
            oauthState = PkceUtils.generateState();
            verifierToStore = PkceUtils.generateCodeVerifier();
            challengeToSend = PkceUtils.deriveCodeChallenge(verifierToStore);
        }

        // Store state with cliCallbackUrl (null for browser flow)
        stateStore.put(oauthState, verifierToStore, isCli ? cliCallback : null);

        // CLI uses its own local callback; browser uses backend's registered redirect URI
        String redirectUri = (isCli && cliCallback != null && !cliCallback.isBlank())
                ? cliCallback
                : props.getRedirectUri();

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

        log.info("Built authorize URL for {} flow", isCli ? "CLI" : "browser");

        if (isCli) {
            return ResponseEntity.ok(new GitHubDtos.InitiateResponse(authorizeUrl, oauthState));
        }

        return ResponseEntity.ok(Map.of("authorize_url", authorizeUrl));
    }

    @PostMapping("/callback")
    public ResponseEntity<TokenPairResponse> cliCallback(
            @RequestBody CliCallbackRequest req
    ) {
        log.info("CLI CALLBACK RECEIVED (POST) - state: {}, verifier present: {}",
                req.state(), req.codeVerifier() != null);

        if (isTestCode(req.code())) {
            log.info("Handling test_code for CLI");
            return ResponseEntity.ok(handleTestCode());
        }

        OAuthStateStore.StateEntry entry = stateStore.consume(req.state())
                .orElseThrow(() -> {
                    log.error("State not found or expired: {}", req.state());
                    return new OAuthException("Invalid or expired OAuth state");
                });

        // CLI_MANAGED sentinel — use verifier from request body
        String codeVerifier = entry.isCliFlow() ? req.codeVerifier() : entry.codeVerifier();

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
        log.info("BROWSER CALLBACK RECEIVED (GET) - state: {}, error: {}", state, error);

        if (error != null) {
            log.error("GitHub OAuth error: {}", error);
            throw new OAuthException("GitHub OAuth denied by user: " + error);
        }

        OAuthStateStore.StateEntry entry = stateStore.consume(state)
                .orElseThrow(() -> {
                    log.error("State not found or expired: {}", state);
                    return new OAuthException("Invalid or expired OAuth state");
                });

        // CLI flow — forward code+state to CLI's local server, don't exchange here
        if (entry.isCliFlow()) {
            String cliCallbackUrl = entry.cliCallbackUrl();
            if (cliCallbackUrl == null || cliCallbackUrl.isBlank()) {
                throw new OAuthException("CLI callback URL missing from state entry");
            }
            String redirectUrl = UriComponentsBuilder.fromHttpUrl(cliCallbackUrl)
                    .queryParam("code", code)
                    .queryParam("state", state)
                    .build()
                    .toUriString();
            log.info("CLI flow — forwarding code to local server: {}", cliCallbackUrl);
            response.sendRedirect(redirectUrl);
            return;
        }

        // Browser flow — exchange code, set cookies, redirect to frontend
        TokenPairResponse tokens = processOAuthFlow(code, entry.codeVerifier());
        log.info("Browser OAuth flow completed successfully");
        AuthController.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());
        response.sendRedirect(props.getFrontendUri());
    }
}