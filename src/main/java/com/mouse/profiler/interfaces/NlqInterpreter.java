package com.mouse.profiler.interfaces;

import com.mouse.profiler.dto.QueryCriteria;

/**
 * Contract for natural language query interpretation.
 */
public interface NlqInterpreter {

    /**
     * Interprets a raw natural language query string into structured filter criteria.
     *
     * @param query the raw user query — already trimmed and non-blank
     * @return populated {@link QueryCriteria}
     */
    QueryCriteria interpret(String query);
}
