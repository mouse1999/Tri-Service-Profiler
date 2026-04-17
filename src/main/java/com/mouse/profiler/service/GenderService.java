package com.mouse.profiler.service;

import com.mouse.profiler.dto.GenderResponseDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidGenderizeException;
import com.mouse.profiler.model.GenderResponse;
import lombok.extern.slf4j.Slf4j; // Lombok Logger
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Slf4j // 1. Add this annotation
@Service
public class GenderService {

    private final WebClient webClient;
    private static final String GENDERIZE_BASE_URL = "https://api.genderize.io";
    private static final String ERROR_MESSAGE = "Genderize returned an invalid response";

    public GenderService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(GENDERIZE_BASE_URL).build();
    }

    public GenderResponseDto fetchGenderData(String name) {
        log.info("Initiating gender lookup for name: [{}]", name); // 2. Log start

        try {
            GenderResponse apiResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("name", name).build())
                    .retrieve()
                    .bodyToMono(GenderResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            log.debug("Received raw response from Genderize: {}", apiResponse); // 3. Log raw data

            // 1. Check for Empty/Null response
            if (apiResponse == null || apiResponse.gender() == null) {
                log.warn("Genderize returned null or missing gender field for name: {}", name);
                throw new InvalidGenderizeException(ERROR_MESSAGE);
            }

            // 2. Check for Count/Sample Size rules
            if (apiResponse.count() <= 0) {
                log.warn("Genderize returned 0 sample size for name: {}", name);
                throw new InvalidGenderizeException(ERROR_MESSAGE);
            }

            log.info("Successfully fetched gender data for [{}]: {}, Probability: {}",
                    name, apiResponse.gender(), apiResponse.probability());

            return new GenderResponseDto(
                    apiResponse.name(),
                    apiResponse.gender(),
                    apiResponse.probability(),
                    apiResponse.count()
            );

        } catch (InvalidGenderizeException e) {
            log.error("Validation failed for Genderize response: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling Genderize API for name [{}]: {}", name, e.getMessage(), e);
            throw new ApiException("Upstream or server failure");
        }
    }
}