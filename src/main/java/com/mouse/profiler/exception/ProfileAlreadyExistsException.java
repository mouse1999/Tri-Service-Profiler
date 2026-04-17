package com.mouse.profiler.exception;

import com.mouse.profiler.dto.ProfileDto;
import com.mouse.profiler.entity.Profile;
import lombok.Getter;

/**
 * Thrown when an attempt is made to create a profile that already exists.
 * Carries the existing profile data to fulfill idempotency requirements.
 */
@Getter
public class ProfileAlreadyExistsException extends ApiException {

    private final ProfileDto existingProfile;

    public ProfileAlreadyExistsException(ProfileDto existingProfile) {
        super("Profile already exists");
        this.existingProfile = existingProfile;
    }

    public ProfileAlreadyExistsException(String message, ProfileDto existingProfile) {
        super(message);
        this.existingProfile = existingProfile;
    }
}