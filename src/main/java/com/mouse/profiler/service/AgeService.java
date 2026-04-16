package com.mouse.profiler.service;

import com.mouse.profiler.dto.AgeResponseDto;
import com.mouse.profiler.enums.AgeCategory;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidAgifyException;
import com.mouse.profiler.model.AgeResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Service
public class AgeService {

    private final WebClient webClient;
    private static final String AGIFY_BASE_URL="https://api.agify.io";
    private static final String ERROR_MESSAGE = "Agify returned an invalid response";

    public AgeService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(AGIFY_BASE_URL).build();
    }

    public AgeResponseDto fetchAgeData(String name) {
        try {
            AgeResponse apiResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("name", name).build())
                    .retrieve()
                    .bodyToMono(AgeResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();


            if (apiResponse == null || apiResponse.age() == null) {
                throw new InvalidAgifyException(ERROR_MESSAGE);
            }

            AgeCategory ageGroup = AgeCategory.fromAge(apiResponse.age());

            return new AgeResponseDto(
                    apiResponse.name(),
                    apiResponse.age(),
                    apiResponse.count(),
                    ageGroup
            );

        } catch (InvalidAgifyException e) {
            throw e;
        } catch (Exception e) {
            // Catches timeouts, connection issues, and 4xx/5xx errors
            throw new ApiException("Upstream or server failure");
        }
    }
}