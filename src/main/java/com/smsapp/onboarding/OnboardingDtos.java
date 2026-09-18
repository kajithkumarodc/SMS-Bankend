package com.smsapp.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request/response payloads for the public onboarding API. Entities are never exposed directly (plan section 7.1d). */
final class OnboardingDtos {

    private OnboardingDtos() {
    }

    /**
     * This is the ONE endpoint in the app that accepts input from a caller with no
     * tenant and no JWT, so validation here is the only line of defense against
     * malformed data (plan section 7 note in the ticket: strict input validation
     * now, IP-based rate limiting at the infrastructure/gateway level later -- see
     * {@link OnboardingController}).
     */
    record RegisterSchoolRequest(
            @NotBlank @Size(max = 200) String schoolName,

            // Subdomain/slug-style code (e.g. "springfield"): letters/digits, hyphens only in
            // the middle, 3-50 chars. Case is normalised (lower-cased) by the service before
            // the availability check and storage, so this pattern accepts either case.
            @NotBlank
            @Size(max = 100)
            @Pattern(
                    regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,48}[A-Za-z0-9]$",
                    message = "School identifier must be 3-50 characters, letters/digits/hyphens only, "
                            + "and can't start or end with a hyphen")
            String schoolIdentifier,

            @NotBlank @Size(max = 200) String adminFullName,

            @NotBlank @Email @Size(max = 320) String adminEmail,

            // "Reasonable minimum" per the ticket: 8-100 chars, at least one letter and one digit.
            // A full password-strength policy (breach lists, entropy scoring, etc.) is out of
            // scope for this endpoint.
            @NotBlank
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,100}$",
                    message = "Password must be at least 8 characters and include a letter and a number")
            String adminPassword) {
    }

    /**
     * Deliberately does NOT include a token or set the auth cookie -- the ticket calls for
     * directing the new admin to the login page with their school identifier, not auto-login.
     */
    record RegisterSchoolResponse(
            UUID tenantId,
            String schoolName,
            String schoolIdentifier,
            String adminEmail,
            UUID adminUserId) {
    }
}
