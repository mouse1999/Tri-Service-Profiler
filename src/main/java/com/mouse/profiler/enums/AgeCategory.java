package com.mouse.profiler.enums;

import lombok.Getter;

/**
 * Defines business rules for age classification.
 * Rules: 0–12 child, 13–19 teenager, 20–59 adult, 60+ senior.
 */
@Getter
public enum AgeCategory {
    CHILD("child"),
    TEENAGER("teenager"),
    ADULT("adult"),
    SENIOR("senior");

    private final String label;

    AgeCategory(String label) {
        this.label = label;
    }

    /**
     * Factory method using Java 21 Pattern Matching for switch.
     * Rules: 0–12 child, 13–19 teenager, 20–59 adult, 60+ senior.
     */
    public static AgeCategory fromAge(int age) {
        return switch ((Integer) age) {
            case Integer a when a >= 0 && a <= 12 -> CHILD;
            case Integer a when a >= 13 && a <= 19 -> TEENAGER;
            case Integer a when a >= 20 && a <= 59 -> ADULT;
            case Integer a when a >= 60 -> SENIOR;
            default -> throw new IllegalArgumentException("Age cannot be negative: " + age);
        };
    }
}
