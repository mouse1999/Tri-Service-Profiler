package com.mouse.profiler.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Provides a shared {@link WebClient} bean.
 *
 * Used by {@link com.mouse.profiler.service.GitHubOAuthService} for
 * GitHub API calls. A single shared instance is efficient — WebClient
 * is thread-safe and designed to be reused.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(config -> config.defaultCodecs()
                        .maxInMemorySize(1024 * 1024)) // 1 MB is enough for GitHub responses
                .build();
    }
}
