package com.smsapp.auth;

import org.springframework.security.oauth2.jwt.Jwt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * How a user's permissions travel inside the access token.
 *
 * <p>The token is sent as a cookie and browsers drop any cookie over 4096 bytes, so a long permission list written out
 * as plain strings eventually made the cookie too big and every request after login failed with 401. The list is
 * therefore stored as one compressed claim ({@value #CLAIM}: deflate, then base64url). Tokens issued before that still
 * carry the plain {@value #LEGACY_CLAIM} list, which is read too.
 */
public final class PermissionClaim {

    /** The compressed claim: the permission names joined by newlines, deflated and base64url-encoded. */
    public static final String CLAIM = "perms";

    /** The claim older tokens use: a JSON array of permission names. */
    public static final String LEGACY_CLAIM = "permissions";

    private PermissionClaim() {
    }

    /** The claim value for a permission list. */
    public static String encode(List<String> permissions) {
        byte[] raw = String.join("\n", permissions).getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** The permissions of a token: the compressed claim, else the legacy list, else none. */
    public static List<String> read(Jwt jwt) {
        String packed = jwt.getClaimAsString(CLAIM);
        if (packed != null) {
            return decode(packed);
        }
        List<String> legacy = jwt.getClaimAsStringList(LEGACY_CLAIM);
        return legacy == null ? List.of() : legacy;
    }

    static List<String> decode(String packed) {
        if (packed.isEmpty()) {
            return List.of();
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(Base64.getUrlDecoder().decode(packed));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            String text = out.toString(StandardCharsets.UTF_8);
            return text.isEmpty() ? List.of() : List.of(text.split("\n"));
        } catch (DataFormatException | IllegalArgumentException ex) {
            return List.of();
        } finally {
            inflater.end();
        }
    }
}
