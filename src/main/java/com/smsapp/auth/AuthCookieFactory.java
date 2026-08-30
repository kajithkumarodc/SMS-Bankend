package com.smsapp.auth;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds the httpOnly access-token cookie (and its matching clear cookie). */
@Component
public class AuthCookieFactory {

    private final AuthCookieProperties properties;

    public AuthCookieFactory(AuthCookieProperties properties) {
        this.properties = properties;
    }

    public String cookieName() {
        return properties.name();
    }

    public ResponseCookie create(String token) {
        return base(token, Duration.ofSeconds(properties.maxAgeSeconds())).build();
    }

    public ResponseCookie clear() {
        return base("", Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value, Duration maxAge) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path("/")
                .maxAge(maxAge);
    }
}
