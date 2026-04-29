package com.mouse.profiler.utils;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * RFC 7636 PKCE utilities.
 *
 * <p>Flow:
 * <pre>
 *   code_verifier  = high-entropy random string (43-128 chars, URL-safe)
 *   code_challenge = BASE64URL(SHA-256(ASCII(code_verifier)))
 * </pre>
 *
 * <p>CLI usage:
 * <ol>
 *   <li>CLI generates a {@code code_verifier} and derives {@code code_challenge}.</li>
 *   <li>CLI sends {@code code_challenge} to {@code GET /auth/github} (stored in state store).</li>
 *   <li>Backend includes {@code code_challenge} in the GitHub authorize URL.</li>
 *   <li>On callback, CLI sends the original {@code code_verifier}.</li>
 *   <li>Backend forwards {@code code_verifier} to GitHub token exchange — GitHub
 *       verifies the hash matches, proving the CLI started the flow.</li>
 * </ol>
 */
public final class PkceUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder()
            .withoutPadding();

    private PkceUtils() {}

    /**
     * Generates a cryptographically random code_verifier.
     * Length: 43 URL-safe characters (satisfies RFC 7636 §4.1).
     */
    public static String generateCodeVerifier() {
        byte[] bytes = new byte[32]; // 32 bytes → 43 base64url chars
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /**
     * Derives code_challenge from code_verifier using S256 method.
     * code_challenge = BASE64URL(SHA-256(ASCII(code_verifier)))
     */
    public static String deriveCodeChallenge(String codeVerifier) {
        if (codeVerifier == null || codeVerifier.isEmpty()) {
            throw new IllegalArgumentException("Code verifier cannot be null or empty");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Generates a cryptographically random state parameter for CSRF protection.
     * 32 bytes → 43 URL-safe base64 characters.
     */
    public static String generateState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
