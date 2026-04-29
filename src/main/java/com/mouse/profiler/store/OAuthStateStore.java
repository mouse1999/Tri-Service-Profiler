package com.mouse.profiler.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
 *       verifier in the callback call.</li>
 * </ul>
 *
 * CSRF + PKCE contract (both flows):
 * <ol>
 *   <li>On initiation, store state → { codeVerifier, createdAt }.</li>
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
     * Browser flow: backend-generated. CLI flow: client-generated.
     */
    public record StateEntry(
            String  codeVerifier,
            Instant createdAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(TTL_SECONDS));
        }
    }

    private final Map<String, StateEntry> store = new ConcurrentHashMap<>();

    /**
     * Stores a state + verifier pair.
     *
     * @param state cryptographically random state string
     * @param codeVerifier PKCE verifier — required for both flows
     */
    public void put(String state, String codeVerifier) {
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new IllegalArgumentException(
                    "codeVerifier must not be null — PKCE is required for all flows");
        }
        store.put(state, new StateEntry(codeVerifier, Instant.now()));
    }

    /**
     * Validates and consumes a state value (one-time use).
     *
     * @param state the state received in the OAuth callback
     * @return StateEntry (containing the codeVerifier) if valid
     */
    public Optional<StateEntry> consume(String state) {
        StateEntry entry = store.remove(state);
        if (entry == null) {
            log.warn("OAuth state not found or already used: {}", state);
            return Optional.empty();
        }
        if (entry.isExpired()) {
            log.warn("OAuth state expired for key: {}", state);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    @Scheduled(fixedRateString = "PT5M")
    public void purgeExpired() {
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - store.size();
        if (removed > 0) log.debug("Purged {} expired OAuth state entries", removed);
    }
}
