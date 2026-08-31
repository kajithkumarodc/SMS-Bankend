# 002 — CSRF mitigation via SameSite=Strict cookies instead of CSRF tokens

- **Status:** Accepted
- **Date:** 2026-08-31
- **Deciders:** Backend team
- **Related:** `SecurityConfig.securityFilterChain`, `AuthCookieFactory`, ADR-001 (multi-tenancy)

## Context

The backend is a **stateless Spring Security OAuth2 resource server**. There is no
server-side HTTP session (`SessionCreationPolicy.STATELESS`). Authentication is a
JWT that the browser holds in an **httpOnly, Secure, `SameSite=Strict` cookie**
named `access_token` (issued by `AuthCookieFactory`; the token is also returned in
the login response body for non-browser use). `CookieBearerTokenResolver` accepts
the token from that cookie **or** from an `Authorization: Bearer` header.

Cross-Site Request Forgery is the attack where a third-party site causes the
victim's browser to send a state-changing request to our API and the browser
**automatically attaches the victim's ambient credential** (cookie). Spring
Security's built-in defense is a synchronizer/double-submit CSRF token that the
attacker's page cannot read or predict.

SonarQube flags `http.csrf(AbstractHttpConfigurer::disable)`
(rule `java:S4502`) as a security hotspot and asks for a human review. This ADR
is that review, recorded once so it is not re-investigated every scan.

## Decision

**Disable Spring Security's CSRF token protection.** Rely on the auth cookie's
`SameSite=Strict` attribute as the CSRF mitigation for browser clients.

Reasoning:

1. **`SameSite=Strict` removes the vector entirely for browsers.** A modern
   browser will not attach a `SameSite=Strict` cookie to *any* request that
   originates from another site — including top-level navigations and form posts.
   The forged request therefore arrives with **no `access_token` cookie**, the
   resource server sees an unauthenticated request, and it is rejected with 401
   before any handler runs. CSRF tokens defend the same boundary; here the cookie
   never crosses it in the first place.
2. **There is no session to ride.** CSRF tokens are most valuable alongside a
   stateful session cookie. We have none — every request is authenticated purely
   from the bearer token.
3. **CORS is restrictive and credentialed.** `allowedOriginPatterns` is an
   explicit allow-list (`app.cors.allowed-origins`), `allowCredentials(true)`,
   and only `Authorization` / `Content-Type` request headers are allowed. A
   browser cannot make a credentialed cross-origin XHR/fetch to the API from an
   un-listed origin.
4. **Enabling CSRF tokens on a token-based API adds cost without adding safety.**
   It would force every client (including our SPA and any future first-party
   tooling) to fetch and echo a token on every mutating call, for a threat that
   `SameSite=Strict` already closes.

Cookie flags are configured centrally (`app.auth.cookie.*` in `application.yml`,
applied by `AuthCookieFactory`): `httpOnly=true`, `secure=true`,
`sameSite=Strict`, `path=/`, `maxAge=3600`. `AUTH_COOKIE_SECURE=false` is
available for plain-HTTP local dev only.

## Consequences

### Positive

- Simpler clients: no CSRF-token round-trip on mutating requests.
- The SPA holds no token in JavaScript-readable storage (httpOnly cookie), so an
  XSS bug cannot exfiltrate the session the way `localStorage` would allow.
- One documented, testable security boundary (cookie `SameSite` + CORS
  allow-list) instead of two overlapping ones.

### Negative / risks

- **Browser-only guarantee.** `SameSite` is enforced by the browser, not the
  server. Any client that is not a standards-compliant browser does not get this
  protection for free.
- **Old browsers.** Browsers that predate `SameSite` support would fall back to
  sending the cookie cross-site. Our supported-browser matrix (evergreen Chrome/
  Edge/Firefox/Safari) all enforce `SameSite`; revisit if that matrix widens.
- **`SameSite=Strict` UX edge:** a user following an inbound link from an email or
  another site into a deep app URL arrives without the cookie on that first
  navigation and must re-authenticate / the SPA must re-bootstrap. Acceptable for
  an admin-style product; `SameSite=Lax` would trade a little CSRF surface for
  smoother inbound links if that ever matters.

### What would make us revisit this decision

- **Adding a mobile app or any non-browser first-party client.** A native mobile
  client does not have a browser cookie jar and cannot rely on `SameSite`
  semantics. Recommended direction when that happens:
  - Mobile/native clients should authenticate with the **`Authorization: Bearer`
    header** (already supported by `CookieBearerTokenResolver`) and **not** use
    the cookie at all. Header-based bearer auth is not attachable by a third
    party, so it is not CSRF-exposed — CSRF is specifically an *ambient
    credential* problem.
  - Keep the cookie flow for the browser SPA; keep the header flow for native.
    The resource server already accepts both, so no server change is required
    *unless* we also want cookie auth on native (we should not).
  - If a future design does require cookie auth outside a browser, re-enable
    Spring Security CSRF tokens (`CookieCsrfTokenRepository.withHttpOnlyFalse()`
    + an SPA interceptor that echoes the `XSRF-TOKEN`) and supersede this ADR.
- **Introducing server-side sessions** (e.g. for a server-rendered admin area).
- **Relaxing the auth cookie to `SameSite=Lax` or `None`** for any reason — that
  immediately reopens the CSRF vector and CSRF tokens must come back.
- **Supporting legacy browsers** without `SameSite` enforcement.

## Verification

- `PhaseOneIntegrationTest` asserts the login response sets
  `Set-Cookie: access_token=...; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=3600`.
- `SecurityConfig` carries an inline comment pointing at this ADR so the decision
  is discoverable from the code.
