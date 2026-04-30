package com.mouse.profiler.service;


import com.mouse.profiler.entity.User;
import com.mouse.profiler.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByGithubId(String githubId) {
        return userRepository.findByGithubId(githubId);
    }


    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }

    @Transactional
    public User updateLastLogin(User user) {
        user.setLastLoginAt(java.time.LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userRepository.findByUsername(username).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean existsByGithubId(String githubId) {
        return userRepository.findByGithubId(githubId).isPresent();
    }
}