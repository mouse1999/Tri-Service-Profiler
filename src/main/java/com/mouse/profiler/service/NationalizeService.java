package com.mouse.profiler.service;

import com.mouse.profiler.dto.NationalityResponseDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidNationalityException;
import com.mouse.profiler.model.NationalityResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Service responsible for communicating with the Nationalize API.
 * Handles nationality prediction and extracts the most likely country of origin.
 */
public class NationalizeService {


    private final WebClient webClient;
    private static final String NATIONALIZE_BASE_URL = " https://api.nationalize.io";
    private static final String ERROR_MESSAGE = "Nationalize returned an invalid response";

    public NationalizeService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(NATIONALIZE_BASE_URL).build();
    }

    /**
     * Fetches nationality data from the Nationalize API.
     * * @param name The name to be analyzed.
     *
     * @return A DTO containing the top country_id and its probability.
     */
    public NationalityResponseDto fetchNationalityData(String name) {
        try {
            NationalityResponse apiResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("name", name).build())
                    .retrieve()
                    .bodyToMono(NationalityResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();


            if (apiResponse == null
                    || apiResponse.country() == null
                    || apiResponse.country().isEmpty()
                    || apiResponse.count() == null) {
                throw new InvalidNationalityException(ERROR_MESSAGE);
            }

            // Find the top country using the helper method
            NationalityResponse.CountryProbability topCountry = findTopNationality(apiResponse.country());

            return new NationalityResponseDto(
                    apiResponse.count(),
                    apiResponse.name(),
                   topCountry
            );

        } catch (InvalidNationalityException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Upstream or server failure");
        }
    }

    /**
     * Filters the API response to find the single most likely nationality.
     * * @param countries List of country predictions from the API.
     * @return The country object with the maximum probability value.
     */
    private NationalityResponse.CountryProbability findTopNationality(
            List<NationalityResponse.CountryProbability> countryProbabilityList) {

        return countryProbabilityList.stream()
                .max(Comparator.comparingDouble(NationalityResponse.CountryProbability::probability))
                .orElseThrow(() -> new InvalidNationalityException("Nationalize returned an invalid response"));
    }
}
