package com.mouse.profiler.exception;

import java.util.function.Supplier;

public class ProfileNotFoundException extends ApiException {
    public ProfileNotFoundException(String message) {
        super(message);
    }
}
