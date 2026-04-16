package com.mouse.profiler.service;

/**
 * Service responsible for communicating with the Genderize API.
 * Handles the retrieval of gender identity, probability, and sample size
 * for a given name.
 */
public class GenderService {

    /**
     * Fetches gender data from the external Genderize API.
     * * @param name The name to be analyzed.
     * @return A DTO containing the name, gender, probability, and count.
     * @throws ExternalApi502Exception if the API returns gender: null, count: 0,
     * or an unsuccessful HTTP status code.
     */
    public GenderResponse fetchGenderData(String name) {
        // TODO: Implement external API call
        // TODO: Handle 502 edge cases (null gender or 0 count)
        return null;
    }

    /**
     * Internal validator to ensure the API response meets the business
     * requirements for data persistence.
     * * @param response The raw data received from the upstream API.
     * @return true if the data is valid and can be stored.
     */
    private boolean isValidResponse(GenderResponse response) {
        // TODO: Implement validation logic
        return false;
    }
}
