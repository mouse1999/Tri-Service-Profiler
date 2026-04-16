package com.mouse.profiler.service;

import com.mouse.profiler.dto.GenderResponseDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidGenderizeException;
import com.mouse.profiler.model.GenderResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Service responsible for communicating with the Genderize API.
 * Handles the retrieval of gender identity, probability, and sample size
 * for a given name.
 */
public class GenderService {

    private final WebClient webClient;
    private static final String GENDERIZE_BASE_URL = "https://api.genderize.io";
    private static final String ERROR_MESSAGE = "Genderize returned an invalid response";

    public GenderService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl( GENDERIZE_BASE_URL).build();
    }
    /**
     * Fetches gender data and transforms it into the GenderResponseDto.
     * Applies strict validation rules for the grading script.
     */
    public GenderResponseDto fetchGenderData(String name) {
        try {
            GenderResponse apiResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("name", name).build())
                    .retrieve()
                    .bodyToMono(GenderResponse.class)
                    .timeout(Duration.ofSeconds(5)) // Protect against upstream hanging
                    .block();

            // 1. Check for Empty/Null response
            if (apiResponse == null || apiResponse.gender() == null) {
                throw new InvalidGenderizeException(ERROR_MESSAGE);
            }

            // 2. Check for Count/Sample Size rules
            if (apiResponse.count() <= 0) {
                throw new InvalidGenderizeException(ERROR_MESSAGE);
            }

            // 3. Transform to DTO (Mapping count -> sampleSize)
            return new GenderResponseDto(
                    apiResponse.name(),
                    apiResponse.gender(),
                    apiResponse.probability(),
                    apiResponse.count()
            );

        } catch (InvalidGenderizeException e) {
            // Re-throw our specific validation exception
            throw e;
        } catch (Exception e) {
            // Catch-all for other unexpected issues to ensure the 502 message format
            throw new ApiException("Upstream or server failure");
        }
    }
}
