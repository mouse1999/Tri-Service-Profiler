package com.mouse.profiler.nlp;

import com.mouse.profiler.dto.QueryCriteria;
import com.mouse.profiler.exception.InvalidQueryException;
import com.mouse.profiler.nlp.QueryInterpreter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class QueryInterpreterTest {

    @InjectMocks
    private QueryInterpreter interpreter;

    @Nested
    @DisplayName("Basic Keyword Mapping Tests")
    class BasicMappingTests {

        @Test
        @DisplayName("Should map 'young males' to specific age range and gender")
        void testYoungMales() {
            QueryCriteria result = interpreter.interpret("young males");
            assertNotNull(result);
            assertEquals("male", result.getGender());
            assertEquals(16, result.getMinAge());
            assertEquals(24, result.getMaxAge());
        }

        @Test
        @DisplayName("Should map 'people from angola' to country code AO")
        void testCountryMapping() {
            QueryCriteria result = interpreter.interpret("people from angola");
            assertEquals("AO", result.getCountryId());
        }
    }

    @Nested
    @DisplayName("Advanced Logic & Combination Tests")
    class AdvancedLogicTests {

        @Test
        @DisplayName("Should handle 'adult males from kenya' (3-way combination)")
        void testTripleCombination() {
            QueryCriteria result = interpreter.interpret("adult males from kenya");
            assertEquals("male", result.getGender());
            assertEquals("adult", result.getAgeGroup());
            assertEquals("KE", result.getCountryId());
        }

        @Test
        @DisplayName("Should parse 'male and female teenagers above 17'")
        void testComplexTeenagerQuery() {
            QueryCriteria result = interpreter.interpret("male and female teenagers above 17");
            // Requirement says "male and female" - since our entity gender is singular,
            // rule-based logic usually defaults to 'both' or ignores gender if both present.
            assertEquals("teenager", result.getAgeGroup());
            assertEquals(17, result.getMinAge());
        }

        @ParameterizedTest
        @CsvSource({
                "'females above 30', female, 30",
                "'males over 50', male, 50",
                "'people older than 18', , 18"
        })
        @DisplayName("Should handle various 'greater than' synonyms")
        void testAgeComparisonSynonyms(String input, String expectedGender, Integer expectedMinAge) {
            QueryCriteria result = interpreter.interpret(input);
            if (expectedGender != null) assertEquals(expectedGender, result.getGender());
            assertEquals(expectedMinAge, result.getMinAge());
        }
    }

    @Nested
    @DisplayName("Edge Cases & Robustness")
    class EdgeCaseTests {

        @Test
        @DisplayName("Edge Case: Case insensitivity (e.g., 'YOUNG MALES')")
        void testCaseInsensitivity() {
            QueryCriteria result = interpreter.interpret("YOUNG MALES FROM NIGERIA");
            assertEquals("male", result.getGender());
            assertEquals("NG", result.getCountryId());
        }

        @Test
        @DisplayName("Edge Case: Noise words should be ignored")
        void testNoiseWords() {
            // "Searching for", "who are", "in" are noise words
            QueryCriteria result = interpreter.interpret("Searching for males who are in nigeria");
            assertEquals("male", result.getGender());
            assertEquals("NG", result.getCountryId());
        }

        @Test
        @DisplayName("Edge Case: Multi-word country mapping (e.g., 'United Kingdom')")
        void testMultiWordCountry() {
            QueryCriteria result = interpreter.interpret("people from united kingdom");
            assertEquals("GB", result.getCountryId());
        }

        @Test
        @DisplayName("Edge Case: Out of order terms")
        void testOrderIndependency() {
            QueryCriteria result = interpreter.interpret("nigeria from males young");
            assertEquals("male", result.getGender());
            assertEquals("NG", result.getCountryId());
            assertEquals(16, result.getMinAge());
        }
    }

    @Nested
    @DisplayName("Error Handling & Validation")
    class ErrorHandlingTests {

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "!!!", "hello world", "just some text"})
        @DisplayName("Should throw exception when query has no identifiable filters")
        void testUninterpretableQueries(String input) {
            // Requirement: return { "status": "error", "message": "Unable to interpret query" }
            // Your service should throw a custom exception that the GlobalExceptionHandler catches.
            assertThrows(InvalidQueryException.class, () -> interpreter.interpret(input));
        }

        @Test
        @DisplayName("Should throw exception for null input")
        void testNullInput() {
            assertThrows(InvalidQueryException.class, () -> interpreter.interpret(null));
        }
    }
}