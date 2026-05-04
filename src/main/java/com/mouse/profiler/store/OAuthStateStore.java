package com.mouse.profiler.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for OAuth {@code state} parameters and their associated
 * PKCE {@code code_verifier} values.
 *
 * Both browser and CLI flows use PKCE — the verifier is ALWAYS present.
 * The difference is only in who generates it:
 * <ul>
 *   <li><b>Browser</b> — the backend generates the verifier + challenge and
 *       stores the verifier here. The challenge goes to GitHub.</li>
 *   <li><b>CLI</b>  — the CLI generates the verifier + challenge client-side.
 *       The backend receives the challenge in the initiate call and the
 *       verifier in the callback call. cliCallbackUrl is stored so the
 *       browser can be redirected back to the CLI's local server.</li>
 * </ul>
 *
 * CSRF + PKCE contract (both flows):
 * <ol>
 *   <li>On initiation, store state → { codeVerifier, cliCallbackUrl, createdAt }.</li>
 *   <li>On callback, {@link #consume(String)} validates AND deletes (one-time use).</li>
 *   <li>The returned {@code codeVerifier} is forwarded to GitHub's token endpoint.</li>
 *   <li>Entries older than 10 minutes are purged by the scheduled cleaner.</li>
 * </ol>
 */
@Slf4j
@Component
public class OAuthStateStore {

    private static final long TTL_SECONDS = 600; // 10 minutes

    /**
     * Stored state entry.
     *
     * {@code codeVerifier} is NEVER null — both flows require PKCE.
     * Browser flow: backend-generated verifier, cliCallbackUrl is null.
     * CLI flow: "CLI_MANAGED" sentinel, cliCallbackUrl holds the local server URL.
     */
    public record StateEntry(
            String codeVerifier,
            String cliCallbackUrl,  // null for browser flow
            Instant createdAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(TTL_SECONDS));
        }

        public boolean isCliFlow() {
            return "CLI_MANAGED".equals(codeVerifier);
        }
    }

    private final Map<String, StateEntry> store = new ConcurrentHashMap<>();

    /**
     * Stores a state + verifier pair for browser flow (no CLI callback URL).
     *
     * @param state         cryptographically random state string
     * @param codeVerifier  PKCE verifier — backend generated for browser flow
     */
    public void put(String state, String codeVerifier) {
        put(state, codeVerifier, null);
    }

    /**
     * Stores a state + verifier pair with an optional CLI callback URL.
     *
     * @param state            cryptographically random state string
     * @param codeVerifier     PKCE verifier or "CLI_MANAGED" sentinel
     * @param cliCallbackUrl   local server URL for CLI flow, null for browser flow
     */
    public void put(String state, String codeVerifier, String cliCallbackUrl) {
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new IllegalArgumentException(
                    "codeVerifier must not be null — PKCE is required for all flows");
        }
        log.info("STORE PUT - State: {}, Verifier: {}, CliCallback: {}, Store size before: {}",
                state, codeVerifier, cliCallbackUrl, store.size());
        store.put(state, new StateEntry(codeVerifier, cliCallbackUrl, Instant.now()));
        log.info("STORE PUT - Store size after: {}", store.size());
    }

    /**
     * Validates and consumes a state value (one-time use).
     *
     * @param state the state received in the OAuth callback
     * @return StateEntry (containing the codeVerifier and optional cliCallbackUrl) if valid
     */
    public Optional<StateEntry> consume(String state) {
        log.info("STORE CONSUME - Looking for state: {}, Store size before: {}",
                state, store.size());
        log.info("STORE CONSUME - Available keys: {}", store.keySet());

        StateEntry entry = store.remove(state);
        if (entry == null) {
            log.warn("STORE CONSUME - State not found or already used: {}", state);
            return Optional.empty();
        }
        if (entry.isExpired()) {
            log.warn("STORE CONSUME - State expired for key: {}", state);
            return Optional.empty();
        }
        log.info("STORE CONSUME - State found and consumed: {}", state);
        return Optional.of(entry);
    }

    /**
     * Clears all entries from the store.
     * Useful for testing or manual cleanup.
     */
    public void clear() {
        int before = store.size();
        store.clear();
        log.debug("Cleared {} OAuth state entries", before);
    }

    /**
     * Returns the current number of entries in the store.
     */
    public int size() {
        return store.size();
    }

    /**
     * Returns all keys in the store (for debugging).
     */
    public Set<String> getAllKeys() {
        return store.keySet();
    }

    /**
     * Checks if a state exists in the store.
     */
    public boolean contains(String state) {
        return store.containsKey(state);
    }

    @Scheduled(fixedRateString = "PT5M")
    public void purgeExpired() {
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - store.size();
        if (removed > 0) log.debug("Purged {} expired OAuth state entries", removed);
    }
}
