package com.mouse.profiler.dto;

/**
 * CLI callback request body.
 * The CLI captures the GitHub redirect, then POSTs these three values.
 */
public record CliCallbackRequest(
        String code,
        String state,
        String codeVerifier   // the verifier the CLI generated in step 1
) {}
