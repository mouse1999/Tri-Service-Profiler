package com.mouse.profiler.enums;


public enum SkipReason {
    DUPLICATE_NAME,
    INVALID_AGE,
    INVALID_GENDER,
    INVALID_NAME,
    INVALID_COUNTRY,
    INVALID_PROBABILITY,
    MISSING_FIELDS,
    MALFORMED_ROW;

    public static final SkipReason[] VALUES = values();
}
