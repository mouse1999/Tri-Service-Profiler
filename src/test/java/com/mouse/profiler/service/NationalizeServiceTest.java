package com.mouse.profiler.service;

import com.mouse.profiler.dto.NationalityResponseDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidNationalityException;
import com.mouse.profiler.model.NationalityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class NationalizeServiceTest {

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

    private NationalizeService nationalizeService;

    private static final String ERROR_MSG = "Nationalize returned an invalid response";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);
        nationalizeService = new NationalizeService(webClientBuilder);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void fetchNationalityData_ValidResponse_ReturnsTopCountry() {
        // GIVEN: Multiple countries, NG has the highest probability (0.10)
        NationalityResponse.CountryProbability c1 = new NationalityResponse.CountryProbability("RO", 0.05);
        NationalityResponse.CountryProbability c2 = new NationalityResponse.CountryProbability("NG", 0.10);
        NationalityResponse.CountryProbability c3 = new NationalityResponse.CountryProbability("NE", 0.04);

        NationalityResponse mockResponse = new NationalityResponse(81243, "victor", List.of(c1, c2, c3));
        when(responseSpec.bodyToMono(NationalityResponse.class)).thenReturn(Mono.just(mockResponse));

        // WHEN
        NationalityResponseDto result = nationalizeService.fetchNationalityData("victor");

        // THEN
        assertNotNull(result);
        assertEquals("NG", result.countryProbability().countryId());
        assertEquals(0.10, result.countryProbability().probability());
        assertEquals("victor", result.name());
    }

    @Test
    void fetchNationalityData_SingleCountry_ReturnsThatCountry() {
        NationalityResponse.CountryProbability c1 = new NationalityResponse.CountryProbability("US", 0.99);
        NationalityResponse mockResponse = new NationalityResponse(100, "sam", List.of(c1));
        when(responseSpec.bodyToMono(NationalityResponse.class)).thenReturn(Mono.just(mockResponse));

        NationalityResponseDto result = nationalizeService.fetchNationalityData("sam");

        assertEquals("US",  result.countryProbability().countryId());
    }

    @Test
    void fetchNationalityData_EmptyCountryList_ThrowsInvalidNationalityException() {
        // GIVEN: API returns count 0 and empty country list
        String queryParam = "unknown";
        NationalityResponse mockResponse = new NationalityResponse(0, queryParam, Collections.emptyList());
        when(responseSpec.bodyToMono(NationalityResponse.class)).thenReturn(Mono.just(mockResponse));

        // WHEN & THEN
        InvalidNationalityException ex = assertThrows(InvalidNationalityException.class,
                () -> nationalizeService.fetchNationalityData(queryParam));

        assertEquals(ERROR_MSG, ex.getMessage());
    }

    @Test
    void fetchNationalityData_CountryListIsNull_ThrowsInvalidNationalityException() {
        // GIVEN: Malformed API response where country array is null
        String queryParam = "fail";
        NationalityResponse mockResponse = new NationalityResponse(0, queryParam, null);
        when(responseSpec.bodyToMono(NationalityResponse.class)).thenReturn(Mono.just(mockResponse));

        // WHEN & THEN
        assertThrows(InvalidNationalityException.class, () -> nationalizeService.fetchNationalityData(queryParam));
    }

    @Test
    void fetchNationalityData_UpstreamTimeout_ThrowsApiException() {

        String queryParam = "slow";

        when(responseSpec.bodyToMono(NationalityResponse.class))
                .thenReturn(Mono.error(new TimeoutException()));

        ApiException ex = assertThrows(ApiException.class, () -> nationalizeService.fetchNationalityData("slow"));
        assertEquals("Upstream or server failure", ex.getMessage());
    }

    @Test
    void fetchNationalityData_EmptyResponse_ThrowsApiException() {
        // GIVEN: API returns nothing (empty body)
        when(responseSpec.bodyToMono(NationalityResponse.class)).thenReturn(Mono.empty());

        ApiException ex = assertThrows(ApiException.class, () -> nationalizeService.fetchNationalityData("nothing"));
        assertEquals(ERROR_MSG, ex.getMessage());
    }

    @Test
    void fetchNationalityData_ApiHttpError_ThrowsApiException() {
        // GIVEN: API returns 500
        when(responseSpec.bodyToMono(NationalityResponse.class))
                .thenReturn(Mono.error(new ApiException("Upstream or server failure")));

        ApiException ex = assertThrows(ApiException.class, () -> nationalizeService.fetchNationalityData("error"));
        assertEquals("Upstream or server failure", ex.getMessage());
    }
}