package com.mouse.profiler.service;

import com.mouse.profiler.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


public class UserDetailsImpl implements UserDetails {

    @Getter
    private final UUID userId;

    @Getter
    private final String githubId;

    private final String username;
    private final boolean active;
    private final Set<GrantedAuthority> authorities;

    public UserDetailsImpl(User user) {
        this.userId    = user.getId();
        this.githubId  = user.getGithubId();
        this.username  = user.getUsername();
        this.active    = user.isActive();

        this.authorities = user.getRoles().stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r.getName()))
                .collect(Collectors.toUnmodifiableSet());
    }


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * No password — GitHub OAuth application.
     * Returning null is safe; the JWT filter bypasses password-based
     * authentication entirely.
     */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * THE INACTIVE GUARD.
     * Returning {@code false} causes Spring Security to refuse authentication
     * even when the JWT signature is valid — results in 403 Forbidden.
     */
    @Override
    public boolean isEnabled() {
        return active;
    }
}
