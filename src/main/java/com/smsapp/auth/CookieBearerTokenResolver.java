package com.smsapp.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.util.StringUtils;

/**
 * Resolves the JWT from the {@code Authorization: Bearer} header (default behaviour)
 * and, failing that, from the httpOnly access-token cookie.
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final String cookieName;

    public CookieBearerTokenResolver(String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String fromHeader = headerResolver.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }

        // Never resolve a token for the auth endpoints themselves. They are
        // permitAll, and a browser automatically re-sends a stale/expired
        // access_token cookie with the login request -- letting the resource
        // server try to validate it there would reject the login with 401
        // instead of letting the user sign in again.
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/v1/auth/")) {
            return null;
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }
}
