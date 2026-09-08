package com.uni.realtime.websocketgateway.auth.dev;

import com.uni.realtime.websocketgateway.auth.JoinTokenClaims;
import com.uni.realtime.websocketgateway.auth.JoinTokenRejectedException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Local-Docker-only stand-in for the real, externally-owned joinToken format (G1a/G1c -- see
 * {@link com.uni.realtime.websocketgateway.auth.JoinTokenVerifier}'s javadoc). This is NOT the production
 * signing algorithm; it exists solely so this repo's own test tooling can mint a joinToken
 * {@link com.uni.realtime.websocketgateway.auth.dev.DevJoinTokenVerifier} can verify, without waiting on the
 * platform team's decision. Never use outside local development/Docker.
 *
 * <p>Wire format: {@code base64url(payload) + "." + base64url(HMAC-SHA256(secret, base64url(payload)))}.
 * {@code payload} is {@code studentId|roomId|sessionId|role1,role2|expEpochMs|jti}, {@code |}-joined
 * UTF-8 -- a deliberately dumb format (no JSON dependency needed here), which is why it assumes
 * none of the fields contain {@code |} or {@code ,}.
 *
 * <p>{@code exp}/{@code jti} are carried only in the wire format and in {@link DecodedDevJoinToken}
 * here, never added to the shared {@link JoinTokenClaims} record -- that record is a cross-team
 * production contract this repo does not own, and the real G1a/G1c answer may carry expiry/replay
 * information in a completely different shape (e.g. a JWT {@code exp} claim).
 */
public final class DevJoinTokenCodec {

    /**
     * Shared literal between the {@link DevJoinTokenVerifier} Spring bean (verify side, reads this
     * as its YAML default) and any test tooling that signs its own joinTokens (e.g. a simulated
     * client) -- defined ONCE here so the two sides can never drift out of sync by copy-paste.
     * {@code application-dev-docker.yml}'s default must stay textually identical to this.
     */
    public static final String DEFAULT_DEV_SECRET = "uni-realtime-local-docker-dev-secret-do-not-use-elsewhere";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] hmacSecretKey;
    private final Clock clock;

    public DevJoinTokenCodec(byte[] hmacSecretKey, Clock clock) {
        this.hmacSecretKey = hmacSecretKey;
        this.clock = clock;
    }

    /** Decoded claims plus the dev-only expiry/replay fields the wire format carries. */
    public record DecodedDevJoinToken(JoinTokenClaims claims, String jti, long expEpochMs) {}

    /** Mints a joinToken for test tooling (a simulated client) -- never called from main verify-side code. */
    public String sign(JoinTokenClaims claims, Duration ttl) {
        long expEpochMs = clock.millis() + ttl.toMillis();
        String jti = UUID.randomUUID().toString();
        String payload = String.join("|",
                claims.studentId(), claims.roomId(), claims.sessionId(),
                String.join(",", claims.roles()), Long.toString(expEpochMs), jti);
        String encodedPayload = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(hmac(encodedPayload));
        return encodedPayload + "." + signature;
    }

    /**
     * @throws JoinTokenRejectedException the joinToken is malformed, unsigned by this secret, or
     *                                  expired -- every failure mode throws this, never a raw
     *                                  parsing exception, so a caller can uniformly close the
     *                                  channel on any rejection.
     */
    public DecodedDevJoinToken verifyRaw(String joinToken) throws JoinTokenRejectedException {
        String[] parts = joinToken.split("\\.", -1);
        if (parts.length != 2) {
            throw new JoinTokenRejectedException("dev joinToken: expected exactly one '.' separator");
        }
        String encodedPayload = parts[0];
        String providedSignature = parts[1];
        byte[] expectedSignature = hmac(encodedPayload);
        byte[] providedSignatureBytes;
        try {
            providedSignatureBytes = DECODER.decode(providedSignature);
        } catch (IllegalArgumentException e) {
            throw new JoinTokenRejectedException("dev joinToken: signature is not valid base64url");
        }
        if (!MessageDigest.isEqual(expectedSignature, providedSignatureBytes)) {
            throw new JoinTokenRejectedException("dev joinToken: signature mismatch");
        }

        String payload;
        try {
            payload = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new JoinTokenRejectedException("dev joinToken: payload is not valid base64url");
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 6) {
            throw new JoinTokenRejectedException("dev joinToken: expected 6 '|'-joined fields, got " + fields.length);
        }
        String studentId = fields[0];
        String roomId = fields[1];
        String sessionId = fields[2];
        List<String> roles = fields[3].isEmpty() ? List.of() : List.of(fields[3].split(",", -1));
        long expEpochMs;
        try {
            expEpochMs = Long.parseLong(fields[4]);
        } catch (NumberFormatException e) {
            throw new JoinTokenRejectedException("dev joinToken: exp is not a valid long");
        }
        String jti = fields[5];

        if (clock.millis() > expEpochMs) {
            throw new JoinTokenRejectedException("dev joinToken: expired at " + expEpochMs);
        }

        return new DecodedDevJoinToken(new JoinTokenClaims(studentId, roomId, sessionId, roles), jti, expEpochMs);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacSecretKey, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is a JDK-mandatory algorithm and hmacSecretKey is never empty by the
            // time this runs (DevJoinTokenVerifier validates that at startup) -- unreachable in
            // practice, wrapped only so this method's signature stays exception-free.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
