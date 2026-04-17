package com.mouse.profiler.controller;

import com.mouse.profiler.dto.ProfileExistDto;
import com.mouse.profiler.exception.ApiException;
import com.mouse.profiler.exception.InvalidInputException;
import com.mouse.profiler.exception.ProfileAlreadyExistsException;
import com.mouse.profiler.exception.ProfileNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Requirement: Idempotency.
     * Returns 200 OK with the existing data and a specific message.
     */
    @ExceptionHandler(ProfileAlreadyExistsException.class)
    public ResponseEntity<ProfileExistDto> handleProfileAlreadyExists(ProfileAlreadyExistsException ex) {
        return ResponseEntity.ok(new ProfileExistDto(
                "success",
                ex.getMessage(),
                ex.getExistingProfile()
        ));
    }

    /**
     * Requirement: 404 Not Found.
     */
    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ProfileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("status", "error", "message", ex.getMessage()));
    }

    /**
     * Requirement: 502 Bad Gateway (External API failures).
     * This handles Genderize/Agify/Nationalize invalid response errors.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleUpstreamError(ApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("status", "error", "message", ex.getMessage()));
    }


    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMissingBody(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "status", "error",
                        "message", "Missing or empty name"
                ));
    }

    /**
     * Requirement: 400 Bad Request (Missing/Empty name).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", "error", "message", ex.getMessage()));
    }

    /**
     * Requirement: 422 Unprocessable Entity (Invalid types, e.g., passing a string as UUID).
     */
    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<Map<String, String>> handleInvalidInput(InvalidInputException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("status", "error", "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("status", "error", "message", "Invalid type"));
    }

    /**
     * Requirement: 500 Internal Server Error (Generic fallback).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "message", "An unexpected server error occurred"));
    }
}