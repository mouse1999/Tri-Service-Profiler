package com.mouse.profiler.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * Comma-separated list of allowed origins from application properties.
     * Defaults to localhost for dev. In prod, set CORS_ALLOWED_ORIGINS env var.
     *
     * NOTE: allowedOrigins("*") is incompatible with allowCredentials(true).
     * We use explicit origins so cookies work correctly in the browser flow.
     */
    @Value("${cors.allowed.origins:http://localhost:3000,http://localhost:5173}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)         // required for cookies (browser flow)
                .exposedHeaders("Access-Control-Allow-Origin");
    }
}