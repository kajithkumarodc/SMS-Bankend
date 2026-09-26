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

        // Never resolve a cookie token for /login or /refresh specifically. Both
        // are permitAll, and a browser automatically re-sends a stale/expired
        // access_token cookie alongside those requests -- letting the resource
        // server try to validate it there would reject the login/refresh with
        // 401 instead of letting the user sign in (or silently refresh) again.
        // Other /auth/** endpoints (e.g. /logout-all) are NOT excluded here: they
        // require an authenticated caller, and a browser session identifies
        // itself via this same cookie, not a header.
        String path = request.getRequestURI();
        if (path != null && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/refresh"))) {
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
