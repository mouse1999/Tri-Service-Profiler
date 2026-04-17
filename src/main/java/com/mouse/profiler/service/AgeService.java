package com.mouse.profiler.service;

import com.mouse.profiler.dto.AgeResponseDto;
import com.mouse.profiler.enums.AgeCategory;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidAgifyException;
import com.mouse.profiler.model.AgeResponse;
import lombok.extern.slf4j.Slf4j; // Lombok Logger
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Slf4j // 1. Add this annotation
@Service
public class AgeService {

    private final WebClient webClient;
    private static final String AGIFY_BASE_URL = "https://api.agify.io";
    private static final String ERROR_MESSAGE = "Agify returned an invalid response";

    public AgeService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(AGIFY_BASE_URL).build();
    }

    public AgeResponseDto fetchAgeData(String name) {
        log.info("Initiating age lookup for name: [{}]", name); // 2. Log start

        try {
            AgeResponse apiResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("name", name).build())
                    .retrieve()
                    .bodyToMono(AgeResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            log.debug("Received raw response from Agify: {}", apiResponse); // 3. Log raw data

            // 1. Check for Empty/Null response
            if (apiResponse == null || apiResponse.age() == null) {
                log.warn("Agify returned null or missing age field for name: {}", name);
                throw new InvalidAgifyException(ERROR_MESSAGE);
            }

            AgeCategory ageGroup = AgeCategory.fromAge(apiResponse.age());

            log.info("Successfully fetched age data for [{}]: Age {}, Group: {}",
                    name, apiResponse.age(), ageGroup);

            return new AgeResponseDto(
                    apiResponse.name(),
                    apiResponse.age(),
                    apiResponse.count(),
                    ageGroup
            );

        } catch (InvalidAgifyException e) {
            log.error("Validation failed for Agify response: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling Agify API for name [{}]: {}", name, e.getMessage(), e);
            throw new ApiException("Upstream or server failure");
        }
    }
}