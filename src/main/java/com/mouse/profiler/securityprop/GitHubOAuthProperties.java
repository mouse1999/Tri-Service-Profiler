package com.mouse.profiler.securityprop;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized GitHub OAuth configuration.
 *
 * application.yml:
 * <pre>
 * insighta:
 *   github:
 *     client-id: Ov23li...
 *     client-secret: ${GITHUB_CLIENT_SECRET}
 *     redirect-uri: <a href="https://localhost:8080/auth/github/callback">...</a>
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "insighta.github")
@Getter
@Setter
public class GitHubOAuthProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String frontendUri;


    public static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    public static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    public static final String USER_API_URL = "https://api.github.com/user";
    public static final String SCOPES = "read:user,user:email";
}
