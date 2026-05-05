package com.mouse.profiler.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties for OpenAI integration.
 * <p>
 * This class handles the binding of {@code openai.*} properties from 
 * {@code application-prod.properties} or environment variables. It supports a "fail-safe"
 * mode where LLM features can be toggled off to favor local regex processing.
 * </p>
 *
 * @param apiKey The secret API key. Should be injected via environment variables
 *     (e.g., {@code OPENAI_API_KEY}) rather than hardcoded.
 * @param model The specific model ID (e.g., {@code gpt-3.5-turbo}).
 *        gpt-3.5-turbo is sufficient for structured JSON extraction from short queries.
 *        Upgrade to gpt-4o-mini if extraction quality needs improvement.
 * @param enabled Master toggle. If {@code false}, the application bypasses
 *        OpenAI calls to save costs and reduce latency.
 * @param timeout The maximum duration to wait for a response. Supports
 *        Spring duration formats (e.g., "10s", "500ms").
 * @param maxTokens The maximum number of tokens allowed in the response to
 *        control costs and prevent runaway generation.
 */
@Validated
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        @NotBlank String apiKey,

        @DefaultValue("gpt-3.5-turbo")
        String model,

        @DefaultValue("true")
        boolean enabled,

        @DefaultValue("10s")
        Duration timeout,

        @Positive
        @DefaultValue("256")
        int maxTokens
) {}