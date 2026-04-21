package com.mouse.profiler.exception;

public class InvalidQueryException extends ApiException{
    public InvalidQueryException(String message) {
        super(message);
    }
}
