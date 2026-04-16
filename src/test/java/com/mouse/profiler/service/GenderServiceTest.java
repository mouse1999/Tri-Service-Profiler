package com.mouse.profiler.service;

import com.mouse.profiler.dto.GenderResponseDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidGenderizeException;
import com.mouse.profiler.model.GenderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GenderService using Mockito.
 * Refactored to return GenderResponseDto as the transformed result.
 */
@ExtendWith(MockitoExtension.class)
public class GenderServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private GenderService genderService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Mock the Builder to return the Mock WebClient
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);

        // Initialize the service with the mocked builder
        genderService = new GenderService(webClientBuilder);

        // Setup the fluent chain for the WebClient calls
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void fetchGenderData_ValidResponse_ReturnsFullGenderProfile() {
        // GIVEN
        String queryParameter = "peter";
        GenderResponse mockApiResponse = new GenderResponse(1094417, "peter", "male", 1.0);
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.just(mockApiResponse));

        // WHEN
        GenderResponseDto result = genderService.fetchGenderData(queryParameter);

        // THEN
        assertNotNull(result);
        assertEquals("male", result.gender());
        assertEquals(1094417, result.sampleSize()); // Checking the renamed DTO field
        assertEquals(1.0, result.probability());
        assertEquals("peter", result.name());
    }

    @Test
    void fetchGenderData_ProbabilityIsOne_ReturnsValidProfile() {
        // GIVEN
        String queryParameter = "ella";
        GenderResponse mockApiResponse = new GenderResponse(500, queryParameter, "female", 1.0);
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.just(mockApiResponse));

        // WHEN
        GenderResponseDto result = genderService.fetchGenderData(queryParameter);

        // THEN
        assertNotNull(result);
        assertEquals(1.0, result.probability());
    }

    @Test
    void fetchGenderData_ProbabilityIsLow_ReturnsValidProfile() {
        // GIVEN
        GenderResponse mockApiResponse = new GenderResponse(100, "alex", "male", 0.51);
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.just(mockApiResponse));

        // WHEN
        GenderResponseDto result = genderService.fetchGenderData("alex");

        // THEN
        assertNotNull(result);
        assertEquals(0.51, result.probability());
    }

    @Test
    void fetchGenderData_GenderFieldIsNull_ThrowsInvalidGenderizeException() {
        // GIVEN
        GenderResponse mockApiResponse = new GenderResponse(0, "unknown", null, 0.0);
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.just(mockApiResponse));

        // WHEN & THEN
        InvalidGenderizeException exception = assertThrows(InvalidGenderizeException.class, () ->
                genderService.fetchGenderData("unknown")
        );

        assertEquals("Genderize returned an invalid response", exception.getMessage());
    }

    @Test
    void fetchGenderData_CountIsZero_ThrowsInvalidGenderizeException() {
        // GIVEN
        String queryParameter = "x";
        GenderResponse mockApiResponse = new GenderResponse(0, queryParameter, "male", 1.0);
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.just(mockApiResponse));

        // WHEN & THEN
        InvalidGenderizeException exception = assertThrows(InvalidGenderizeException.class, () ->
                genderService.fetchGenderData(queryParameter)
        );

        assertEquals("Genderize returned an invalid response", exception.getMessage());
    }

    @Test
    void fetchGenderData_CountIsNegative_ThrowsInvalidGenderizeException() {
        // GIVEN
        String queryParam = "test";
        GenderResponse mockApiResponse = new GenderResponse(-5, queryParam, "male", 1.0);
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.just(mockApiResponse));

        // WHEN & THEN
        InvalidGenderizeException exception = assertThrows(InvalidGenderizeException.class, () ->
                genderService.fetchGenderData(queryParam)
        );

        assertEquals("Genderize returned an invalid response", exception.getMessage());
    }

    @Test
    void fetchGenderData_EmptyResponse_ThrowsInvalidGenderizeException() {
        // GIVEN
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.empty());

        // WHEN & THEN
        InvalidGenderizeException exception = assertThrows(InvalidGenderizeException.class, () ->
                genderService.fetchGenderData("invalid")
        );

        assertEquals("Genderize returned an invalid response", exception.getMessage());
    }

    @Test
    void fetchGenderData_UpstreamHttp5xxError_ThrowsApiException() {
        // GIVEN
        String queryParam = "peter";
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.error(new ApiException("API Down")));

        // WHEN & THEN
        ApiException exception = assertThrows(ApiException.class, () ->
                genderService.fetchGenderData(queryParam)
        );

        assertEquals("Upstream or server failure", exception.getMessage());
    }

    @Test
    void fetchGenderData_UpstreamTimeout_ThrowsApiException() {
        // GIVEN
        when(responseSpec.bodyToMono(GenderResponse.class)).thenReturn(Mono.error(new TimeoutException()));

        // WHEN & THEN
        ApiException exception = assertThrows(ApiException.class, () ->
                genderService.fetchGenderData("slow_name")
        );

        assertEquals("Upstream or server failure", exception.getMessage());
    }
}