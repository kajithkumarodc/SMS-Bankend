package com.smsapp.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionClaimTest {

    private static Jwt jwtWith(String claim, Object value) {
        return Jwt.withTokenValue("t").header("alg", "none").claim(claim, value).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    /** About as many, and as long, as the admin roles' permissions (plus plenty of headroom). */
    private static List<String> manyPermissions(int count) {
        List<String> names = new ArrayList<>();
        String[] modules = {"STAFF", "PAYROLL", "LEAVE", "INVENTORY", "TIMETABLE", "CALENDAR", "EXPENSE", "FEE", "STUDENT", "ADMISSION"};
        String[] actions = {"VIEW", "CREATE", "EDIT", "DELETE", "EXPORT", "PRINT", "APPROVE", "MANAGE", "IMPORT", "PROMOTE"};
        for (int i = 0; names.size() < count; i++) {
            names.add(modules[i % modules.length] + "_" + modules[(i / 7) % modules.length] + "_" + actions[(i / 3) % actions.length] + "_" + i);
        }
        return names;
    }

    @Test
    void roundTripsThePermissionList() {
        List<String> permissions = List.of("STAFF_VIEW", "PAYROLL_CREATE", "LEAVE_TYPE_MANAGE");
        assertThat(PermissionClaim.read(jwtWith(PermissionClaim.CLAIM, PermissionClaim.encode(permissions)))).containsExactlyElementsOf(permissions);
    }

    @Test
    void anEmptyListRoundTrips() {
        assertThat(PermissionClaim.read(jwtWith(PermissionClaim.CLAIM, PermissionClaim.encode(List.of())))).isEmpty();
    }

    @Test
    void readsTheLegacyPlainListAndMissingClaims() {
        assertThat(PermissionClaim.read(jwtWith(PermissionClaim.LEGACY_CLAIM, List.of("A", "B")))).containsExactly("A", "B");
        assertThat(PermissionClaim.read(jwtWith("other", "x"))).isEmpty();
    }

    @Test
    void garbageDecodesToNoPermissions() {
        assertThat(PermissionClaim.decode("not-valid-base64-or-deflate!!")).isEmpty();
    }

    @Test
    void theClaimStaysFarBelowTheCookieLimit() {
        // Browsers drop a cookie over 4096 bytes; the whole token must fit with room to spare.
        List<String> permissions = manyPermissions(300);
        int plain = String.join("\",\"", permissions).length();
        String packed = PermissionClaim.encode(permissions);
        assertThat(packed.length()).isLessThan(plain / 2);
        assertThat(PermissionClaim.read(jwtWith(PermissionClaim.CLAIM, packed))).containsExactlyElementsOf(permissions);
    }
}
