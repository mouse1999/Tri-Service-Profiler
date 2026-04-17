package com.mouse.profiler.service;

import com.mouse.profiler.dto.NationalityResponseDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidNationalityException;
import com.mouse.profiler.model.NationalityResponse;
import lombok.extern.slf4j.Slf4j; // Lombok Logger
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Service responsible for communicating with the Nationalize API.
 */
@Slf4j // 1. Add this annotation
@Service
public class NationalizeService {

    private final WebClient webClient;
    private static final String NATIONALIZE_BASE_URL = "https://api.nationalize.io";
    private static final String ERROR_MESSAGE = "Nationalize returned an invalid response";

    public NationalizeService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(NATIONALIZE_BASE_URL).build();
    }

    public NationalityResponseDto fetchNationalityData(String name) {
        log.info("Initiating nationality lookup for name: [{}]", name); // 2. Log start

        try {
            NationalityResponse apiResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("name", name).build())
                    .retrieve()
                    .bodyToMono(NationalityResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            log.debug("Received raw response from Nationalize: {}", apiResponse); // 3. Log raw data

            // 1. Check for Empty/Null response or missing country list
            if (apiResponse == null
                    || apiResponse.country() == null
                    || apiResponse.country().isEmpty()
                    || apiResponse.count() == null) {
                log.warn("Nationalize returned insufficient data for name: {}. Data: {}", name, apiResponse);
                throw new InvalidNationalityException(ERROR_MESSAGE);
            }

            // 2. Find the top country
            NationalityResponse.CountryProbability topCountry = findTopNationality(apiResponse.country());

            log.info("Successfully fetched nationality for [{}]: Top Country: {}, Probability: {}",
                    name, topCountry.countryId(), topCountry.probability());

            return new NationalityResponseDto(
                    apiResponse.count(),
                    apiResponse.name(),
                    topCountry
            );

        } catch (InvalidNationalityException e) {
            log.error("Validation failed for Nationalize response: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling Nationalize API for name [{}]: {}", name, e.getMessage(), e);
            throw new ApiException("Upstream or server failure");
        }
    }

    private NationalityResponse.CountryProbability findTopNationality(
            List<NationalityResponse.CountryProbability> countryProbabilityList) {

        log.trace("Calculating top probability from list of {} countries", countryProbabilityList.size());

        return countryProbabilityList.stream()
                .max(Comparator.comparingDouble(NationalityResponse.CountryProbability::probability))
                .orElseThrow(() -> {
                    log.error("Failed to extract top nationality from country list");
                    return new InvalidNationalityException(ERROR_MESSAGE);
                });
    }
}