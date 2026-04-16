package com.mouse.profiler.model;

public record GenderResponse(int count,
                             String name,
                             String gender,
                             double probability) {
}
