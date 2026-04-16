package com.mouse.profiler.service;

import com.mouse.profiler.dto.AgeResponseDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidAgifyException;
import com.mouse.profiler.model.AgeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

@ExtendWith(MockitoExtension.class)
public class AgeServiceTest {

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

    private AgeService ageService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);
        ageService = new AgeService(webClientBuilder);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void fetchAgeData_ValidResponse_ReturnsCorrectDto() {
        // GIVEN:
        String queryParam = "michael";
        AgeResponse mock = new AgeResponse(queryParam, 35, 1000);
        when(responseSpec.bodyToMono(AgeResponse.class))
                .thenReturn(Mono.just(mock));

        // WHEN
        AgeResponseDto result = ageService.fetchAgeData(queryParam);

        // THEN
        assertNotNull(result);
        assertEquals(35, result.age());
        assertEquals("adult", result.ageCategory().getLabel());
        assertEquals("michael", result.name());
    }

    @Test
    void fetchAgeData_BoundaryChild_ReturnsChild() {

        String queryParam = "child";

        AgeResponse mock = new AgeResponse(queryParam, 12, 50);
        when(responseSpec.bodyToMono(AgeResponse.class))
                .thenReturn(Mono.just(mock));

        AgeResponseDto result = ageService.fetchAgeData(queryParam);
        assertEquals(queryParam, result.ageCategory().getLabel());
    }

    @Test
    void fetchAgeData_BoundarySenior_ReturnsSenior() {

        String queryParam = "senior";
        AgeResponse mock = new AgeResponse(queryParam, 60, 50);
        when(responseSpec.bodyToMono(AgeResponse.class))
                .thenReturn(Mono.just(mock));

        AgeResponseDto result = ageService.fetchAgeData(queryParam);
        assertEquals(queryParam, result.ageCategory().getLabel());
    }

    @Test
    void fetchAgeData_AgeIsNull_ThrowsInvalidExternalResponseException() {
        // GIVEN:

        String queryParam = "unknown";

        AgeResponse mock = new AgeResponse(queryParam, null, 0);
        when(responseSpec.bodyToMono(AgeResponse.class)).thenReturn(Mono.just(mock));

        // WHEN & THEN
        InvalidAgifyException ex = assertThrows(InvalidAgifyException.class,
                () -> ageService.fetchAgeData(queryParam));

        assertEquals("Agify returned an invalid response", ex.getMessage());
    }

    @Test
    void fetchAgeData_Timeout_ThrowsApiException() {
        // GIVEN: API hangs
        String queryParam = "slow";
        when(responseSpec.bodyToMono(AgeResponse.class))
                .thenReturn(Mono.error(new TimeoutException()));

        // WHEN & THEN
        ApiException ex = assertThrows(ApiException.class, () -> ageService.fetchAgeData(queryParam));
        assertEquals("Upstream or server failure", ex.getMessage());
    }

    @Test
    void fetchAgeData_EmptyMono_ThrowsApiException() {
        // GIVEN: API returns empty body

        String queryParam = "empty";

        when(responseSpec.bodyToMono(AgeResponse.class)).thenReturn(Mono.empty());

        // WHEN & THEN
        assertThrows(InvalidAgifyException.class, () -> ageService.fetchAgeData(queryParam));
    }
}