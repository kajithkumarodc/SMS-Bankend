package com.smsapp.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the httpOnly cookie that carries the access token.
 * All environment-specific values (secure flag, SameSite, lifetime) are
 * configurable — see {@code app.auth.cookie.*} in application.yml.
 */
@ConfigurationProperties(prefix = "app.auth.cookie")
public record AuthCookieProperties(String name, Boolean secure, String sameSite, Long maxAgeSeconds) {

    public AuthCookieProperties {
        if (name == null || name.isBlank()) {
            name = "access_token";
        }
        if (secure == null) {
            secure = Boolean.TRUE;
        }
        if (sameSite == null || sameSite.isBlank()) {
            sameSite = "Strict";
        }
        if (maxAgeSeconds == null || maxAgeSeconds <= 0) {
            maxAgeSeconds = 3600L;
        }
    }
}
