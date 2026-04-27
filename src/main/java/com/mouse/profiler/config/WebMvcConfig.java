package com.mouse.profiler.config;

import com.mouse.profiler.interceptor.ApiVersionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration that registers interceptors and maps them
 * to specific URL patterns.
 *
 * <p>Implements {@link WebMvcConfigurer} which provides hook methods for
 * customising the Spring MVC framework without replacing the auto-configured
 * dispatcher servlet setup.</p>
 *
 * <p>Why not use {@code @Bean} to register the interceptor?</p>
 * <p>Interceptors must be registered through {@code addInterceptors()} to
 * be recognised by Spring MVC's dispatcher. Adding them as plain beans
 * does not connect them to the request lifecycle.</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiVersionInterceptor apiVersionInterceptor;


    /**
     * Registers interceptors and specifies which URL patterns they apply to.
     * @param registry the Spring MVC interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiVersionInterceptor)
                .addPathPatterns("/api/**");
    }
}
