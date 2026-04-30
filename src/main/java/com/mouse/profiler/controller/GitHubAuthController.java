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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.server.Session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

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
        UserDetailsImpl principal = new UserDetailsImpl(user);
        String accessToken = jwtService.generateAccessToken(principal);
        RefreshToken refreshToken = refreshTokenService.create(user);

        return new TokenPairResponse(
                "success",
                accessToken,
                refreshToken.getToken()

        );
    }

    private TokenPairResponse handleTestCode() {
        log.info("Test code detected, returning seeded admin user tokens");

        User adminUser = userService.findByUsername(TEST_ADMIN_USERNAME)
                .orElseThrow(() -> new OAuthException("Test admin user not found"));

        return buildTokenResponse(adminUser);
    }

    private TokenPairResponse processOAuthFlow(String code, String codeVerifier) {
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
            HttpServletResponse response
    ) throws IOException {

        boolean isCli = codeChallenge != null && !codeChallenge.isBlank();

        String oauthState;
        String verifierToStore;
        String challengeToSend;

        if (isCli) {
            oauthState = (state != null && !state.isBlank()) ? state : PkceUtils.generateState();
            challengeToSend = codeChallenge;
            verifierToStore = "CLI_MANAGED";
        } else {
            oauthState = PkceUtils.generateState();
            verifierToStore = PkceUtils.generateCodeVerifier();
            challengeToSend = PkceUtils.deriveCodeChallenge(verifierToStore);
        }

        stateStore.put(oauthState, verifierToStore);

        String authorizeUrl = UriComponentsBuilder
                .fromHttpUrl(GitHubOAuthProperties.AUTHORIZE_URL)
                .queryParam("client_id", props.getClientId())
                .queryParam("redirect_uri", props.getRedirectUri())
                .queryParam("scope", GitHubOAuthProperties.SCOPES)
                .queryParam("state", oauthState)
                .queryParam("code_challenge", challengeToSend)
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUriString();

        if (isCli) {
            return ResponseEntity.ok(new GitHubDtos.InitiateResponse(authorizeUrl, oauthState));
        }

        response.sendRedirect(authorizeUrl);
        return null;
    }


    @GetMapping("/callback")
    public void browserCallback(
            @RequestParam String code,
            @RequestParam String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response
    ) throws IOException {
        if (error != null) {
            throw new OAuthException("GitHub OAuth denied by user: " + error);
        }

        OAuthStateStore.StateEntry entry = stateStore.consume(state)
                .orElseThrow(() -> new OAuthException("Invalid or expired OAuth state"));

        TokenPairResponse tokens = processOAuthFlow(code, entry.codeVerifier());

        // Set HTTP-only cookies
        AuthController.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());

        // Redirect to frontend callback page (NOT passing tokens in URL!)
        response.sendRedirect("%s/dashboard".formatted(props.getFrontendUri()));
    }
}