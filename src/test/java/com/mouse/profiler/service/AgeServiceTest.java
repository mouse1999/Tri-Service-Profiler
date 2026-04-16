package com.mouse.profiler.service;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for AgeService.
 * Focuses on classification logic (0-12, 13-19, 20-59, 60+)
 * and edge case handling for upstream API failures.
 */
public class AgeServiceTest {

    @Test
    void fetchAgeData_AgeZero_ReturnsChild() {
        // Test lower boundary of 'child'
    }

    @Test
    void fetchAgeData_AgeTwelve_ReturnsChild() {
        // Test upper boundary of 'child'
    }

    @Test
    void fetchAgeData_AgeThirteen_ReturnsTeenager() {
        // Test lower boundary of 'teenager'
    }

    @Test
    void fetchAgeData_AgeNineteen_ReturnsTeenager() {
        // Test upper boundary of 'teenager'
    }

    @Test
    void fetchAgeData_AgeTwenty_ReturnsAdult() {
        // Test lower boundary of 'adult'
    }

    @Test
    void fetchAgeData_AgeFiftyNine_ReturnsAdult() {
        // Test upper boundary of 'adult'
    }

    @Test
    void fetchAgeData_AgeSixty_ReturnsSenior() {
        // Test lower boundary of 'senior'
    }

    @Test
    void fetchAgeData_ExtremeAge_ReturnsSenior() {
        // Test high value (e.g., 100)
    }

    @Test
    void fetchAgeData_AgeIsNull_ThrowsExternalApi502Exception() {
        // Requirement: "Agify returns age: null -> return 502"
    }

    @Test
    void fetchAgeData_UpstreamTimeout_ThrowsExternalApi502Exception() {
        // Handling network/server failure from Agify
    }

    @Test
    void fetchAgeData_EmptyResponse_ThrowsExternalApi502Exception() {
        // Handling malformed or empty JSON payloads
    }
}
