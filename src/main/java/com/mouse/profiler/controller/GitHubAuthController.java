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
import com.mouse.profiler.store.OAuthStateStore;
import com.mouse.profiler.utils.PkceUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * GitHub OAuth 2.0 endpoints — PKCE enforced for BOTH browser and CLI.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  BROWSER FLOW                                                           │
 * │                                                                         │
 * │  1. GET /auth/github                                                    │
 * │     Backend generates code_verifier + code_challenge                   │
 * │     Stores: state → { codeVerifier }                                   │
 * │     Redirects browser to GitHub with code_challenge in URL             │
 * │                                                                         │
 * │  2. GitHub → GET /auth/github/callback?code=...&state=...              │
 * │     Backend looks up state → retrieves stored codeVerifier             │
 * │     Sends codeVerifier to GitHub token exchange                        │
 * │     Returns JSON: { access_token, refresh_token, ... }                 │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  CLI FLOW                                                               │
 * │                                                                         │
 * │  1. CLI generates code_verifier + code_challenge locally               │
 * │     GET /auth/github?code_challenge=...&state=...                      │
 * │     Backend stores: state → { codeVerifier: null placeholder }         │
 * │     BUT — CLI sends challenge so backend never needs the verifier;     │
 * │     the CLI sends it back in step 2.                                   │
 * │     Returns JSON: { authorize_url, state }                             │
 * │                                                                         │
 * │  2. CLI local server captures GitHub redirect                          │
 * │     POST /auth/github/callback { code, state, code_verifier }         │
 * │     Backend validates state, forwards code_verifier to GitHub          │
 * │     Returns JSON: { access_token, refresh_token, ... }                 │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * In BOTH cases GitHub receives a code_challenge at authorization time and
 * a code_verifier at token exchange time — full RFC 7636 S256 PKCE.
 */
@Slf4j
@RestController
@RequestMapping("/auth/github")
@RequiredArgsConstructor
public class GitHubAuthController {

    private final GitHubOAuthProperties props;
    private final GitHubOAuthService oauthService;
    private final OAuthStateStore stateStore;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;



    /**
     * Initiates the GitHub OAuth flow with PKCE for both browser and CLI.
     *
     * <b>Browser:</b> no query params needed.
     *   Backend generates verifier + challenge, stores verifier, redirects to GitHub.
     *
     * <b>CLI:</b> send {@code code_challenge} + {@code state} (CLI generated them).
     *   Backend stores the challenge source for later verification, returns
     *   {@code authorize_url} as JSON for the CLI to open in the browser.
     *   The CLI holds onto {@code code_verifier} and sends it in the callback.
     *
     * @param codeChallenge S256 challenge from CLI (absent for browser flow)
     * @param state         random state from CLI (generated here if absent)
     */
    @GetMapping
    public ResponseEntity<?> initiateOAuth(
            @RequestParam(required = false) String codeChallenge,
            @RequestParam(required = false) String state,
            HttpServletResponse response
    ) throws IOException {

        boolean isCli = codeChallenge != null && !codeChallenge.isBlank();

        String oauthState;
        String verifierToStore;
        String challengeToSend;

        if (isCli) {
            // ── CLI FLOW ──
            // CLI owns the verifier — it will send it back in the callback POST.
            // We store a sentinel so the state entry exists for CSRF validation.
            // The actual verifier comes from the CLI in the callback, not from us.
            oauthState = (state != null && !state.isBlank()) ? state : PkceUtils.generateState();
            challengeToSend = codeChallenge;
            // Store "CLI_MANAGED" so we know on callback to use the client-supplied verifier
            verifierToStore = "CLI_MANAGED";

        } else {
            // ── BROWSER FLOW ──
            // Backend generates the full PKCE pair and stores the verifier.
            // The browser never sees the verifier, it only ever touches the challenge.
            oauthState = PkceUtils.generateState();
            verifierToStore = PkceUtils.generateCodeVerifier();
            challengeToSend = PkceUtils.deriveCodeChallenge(verifierToStore);
        }

        // Store state ,verifier (CSRF guard + PKCE bridge)
        stateStore.put(oauthState, verifierToStore);

        // Build the GitHub authorize URL — always includes PKCE challenge
        String authorizeUrl = UriComponentsBuilder
                .fromHttpUrl(GitHubOAuthProperties.AUTHORIZE_URL)
                .queryParam("client_id", props.getClientId())
                .queryParam("redirect_uri", props.getRedirectUri())
                .queryParam("scope", GitHubOAuthProperties.SCOPES)
                .queryParam("state", oauthState)
                .queryParam("code_challenge", challengeToSend)
                .queryParam("code_challenge_method", "S256")
                .encode()  // Explicit encoding for safety
                .build()
                .toUriString();

        if (isCli) {
            // Return JSON — CLI opens this URL in the browser
            return ResponseEntity.ok(new GitHubDtos
                    .InitiateResponse(authorizeUrl, oauthState));
        } else {
            // Browser redirect
            response.sendRedirect(authorizeUrl);
            return null;
        }
    }

    // ── GET /auth/github/callback (browser) ──────────────────────────────────

    /**
     * GitHub redirects the browser here after authorization.
     *
     * The backend retrieves the stored {@code codeVerifier} from the state store
     * and forwards it to GitHub's token endpoint — completing the PKCE proof.
     */
    @GetMapping("/callback")
    public ResponseEntity<TokenPairResponse> browserCallback(
            @RequestParam String code,
            @RequestParam String state,
            @RequestParam(required = false) String error
    ) {
        if (error != null) {
            throw new OAuthException("GitHub OAuth denied by user: " + error);
        }

        // Retrieve the verifier the backend stored during initiation
        OAuthStateStore.StateEntry entry = stateStore.consume(state)
                .orElseThrow(() -> new OAuthException(
                        "Invalid or expired OAuth state — possible CSRF attack"));

        // For browser flow, codeVerifier is what WE stored
        return ResponseEntity.ok(processCallback(code, entry.codeVerifier()));
    }

    // ── POST /auth/github/callback (CLI)

    @PostMapping("/callback")
    public ResponseEntity<TokenPairResponse> cliCallback(@RequestBody CliCallbackRequest req) {
        OAuthStateStore.StateEntry entry = stateStore.consume(req.state())
                .orElseThrow(() -> new OAuthException("Invalid or expired OAuth state"));

        // Verify this is actually a CLI flow!
        if (!"CLI_MANAGED".equals(entry.codeVerifier())) {
            throw new OAuthException("Invalid flow: state was created for browser flow, not CLI");
        }

        if (req.codeVerifier() == null || req.codeVerifier().isBlank()) {
            throw new OAuthException("code_verifier is required for CLI flow");
        }

        return ResponseEntity
                .ok(processCallback(req.code(),
                        req.codeVerifier()));
    }

    // ── Shared callback logic

    /**
     * Exchanges the authorization code for a GitHub token (with PKCE verifier),
     * fetches the user profile, upserts the user, and issues our token pair.
     *
     * @param code authorization code from GitHub
     * @param codeVerifier the PKCE verifier — backend-generated (browser) or
     *  client-generated (CLI)
     */
    private TokenPairResponse processCallback(String code, String codeVerifier) {
        // Exchange code + verifier → GitHub access token
        // GitHub verifies: SHA-256(codeVerifier) == code_challenge sent earlier
        String githubToken = oauthService.exchangeCodeForToken(code, codeVerifier);

        // Fetch GitHub user profile
        GitHubDtos.GitHubUser githubUser = oauthService.fetchGitHubUser(githubToken);

        // Upsert Insighta user (new users get ROLE_ANALYST automatically)
        User user = oauthService.upsertUser(githubUser);

        // Inactive Guard
        if (!user.isActive()) {
            throw new OAuthException("Account is inactive. Contact an administrator.");
        }

        // Issue our token pair
        UserDetailsImpl principal = new UserDetailsImpl(user);
        String accessToken = jwtService.generateAccessToken(principal);
        RefreshToken rt = refreshTokenService.create(user);

        log.info("Successful OAuth login: {} ({})", user.getUsername(),
                principal.getAuthorities());

        return new TokenPairResponse(
                "success",
                accessToken,
                rt.getToken(),
                user.getUsername(),
                user.getAvatarUrl()
        );
    }
}
