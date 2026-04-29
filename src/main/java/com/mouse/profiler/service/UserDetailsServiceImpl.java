package com.mouse.profiler.service;


import com.mouse.profiler.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom {@link UserDetailsService} used by the JWT filter to load a user
 * by username after validating a Bearer token.
 *
 * The Inactive Guard is enforced here via {@link UserDetailsImpl#isEnabled()}.
 * Spring Security checks isEnabled() and will refuse to set the SecurityContext
 * if the account is inactive, causing a 403 Forbidden response.
 *
 * Note: This service is NOT used for password-based login (GitHub OAuth app).
 * It is called only from {@link com.mouse.profiler.filter.JwtAuthenticationFilter}.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found: " + username));
        return new UserDetailsImpl(user);
    }
}
