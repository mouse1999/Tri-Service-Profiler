package com.mouse.profiler.jwt;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "insighta.jwt")
@Getter
@Setter
public class JwtProperties {
    private String secret;
    private Duration accessTokenTtl = Duration.ofMinutes(3);
    private Duration refreshTokenTtl = Duration.ofMinutes(5);
}
