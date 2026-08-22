package com.piecetrack.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Verifies a signed lease token issued by the licence server (M14, {@code license-server/}).
 * Deliberately pure - no database, no network, no clock comparison - so it can be exercised
 * directly in {@code M14LicenseTest} without a real server. {@link LicenseService} is what
 * turns a verified (or rejected) lease into a {@code LicenseState} decision.
 * <p>
 * A lease token is {@code base64url(payloadJson) + "." + base64url(ed25519Signature)} -
 * deliberately JWT-shaped but not an actual JWT: there is no header, so there is no
 * attacker-controlled "alg" field to downgrade to "none". Ed25519 is fixed on both ends
 * (see {@code worker.js}), and is JDK-native since Java 15 - no new Maven dependency, and
 * {@code jdk.crypto.ec} (where {@code SunEC}'s EdDSA implementation lives) is already in the
 * jlink module list in {@code scripts/package-windows.ps1}.
 * <p>
 * The order of checks in {@link #verify} matters: signature first, then everything else -
 * nothing from an unverified payload is ever trusted, including its own claimed
 * {@code licenseId} or {@code fp}.
 */
@Component
public class LicenseVerifier {

    /**
     * The project's real Ed25519 public key - matches the private key held only as the
     * Cloudflare Worker's {@code LICENSE_SIGNING_KEY} secret (see
     * {@code license-server/README.md}). Any lease signed by anyone other than that private
     * key fails verification.
     */
    public static final String DEFAULT_PUBLIC_KEY_BASE64 =
            "MCowBQYDK2VwAyEABIwuRzOlFtBe8gSBwtGUEEs6rL7EAvsDwdOWhv5/jUU=";

    private final PublicKey publicKey;
    // Plain Gson has no built-in java.time support - the payload's issuedAt/expiresAt are
    // ISO-8601 strings (worker.js writes them via Date#toISOString), so Instant.parse is a
    // direct match on both sides.
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class,
                    (com.google.gson.JsonDeserializer<Instant>) (element, type, context) ->
                            Instant.parse(element.getAsString()))
            .create();

    public LicenseVerifier() {
        this(DEFAULT_PUBLIC_KEY_BASE64);
    }

    /** Package-visible-in-spirit constructor for tests to inject a matching test key pair -
     *  see {@code M14LicenseTest}'s {@code @Primary LicenseVerifier} bean override, the same
     *  pattern {@code TestAppPathsFactory} already establishes for {@code AppPaths}. */
    public LicenseVerifier(String publicKeyBase64) {
        this.publicKey = parsePublicKey(publicKeyBase64);
    }

    /** Mirrors the JSON shape {@code worker.js}'s {@code mintLease} produces. */
    public record LeasePayload(
            @SerializedName("licenseId") String licenseId,
            @SerializedName("shopName") String shopName,
            @SerializedName("fp") String fingerprint,
            @SerializedName("issuedAt") Instant issuedAt,
            @SerializedName("expiresAt") Instant expiresAt,
            @SerializedName("status") String status
    ) {
    }

    /** @return the verified payload, or empty if the token is malformed, signed by a
     *          different key, or has been tampered with in any way. Never throws for
     *          untrusted input - a bad lease is exactly the case this method exists to
     *          handle without crashing the caller. */
    public Optional<LeasePayload> verify(String leaseToken) {
        if (leaseToken == null || leaseToken.isBlank()) {
            return Optional.empty();
        }
        String[] parts = leaseToken.split("\\.", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            byte[] payloadBytes = base64UrlDecode(parts[0]);
            byte[] signatureBytes = base64UrlDecode(parts[1]);

            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(payloadBytes);
            if (!signature.verify(signatureBytes)) {
                return Optional.empty();
            }

            String json = new String(payloadBytes, StandardCharsets.UTF_8);
            return Optional.ofNullable(gson.fromJson(json, LeasePayload.class));
        } catch (GeneralSecurityException | RuntimeException e) {
            // RuntimeException covers malformed base64/JSON from a hand-edited or corrupted
            // row - never a reason to crash, just to treat the lease as invalid.
            return Optional.empty();
        }
    }

    private static PublicKey parsePublicKey(String base64) {
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            return keyFactory.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (NoSuchAlgorithmException | java.security.spec.InvalidKeySpecException e) {
            throw new IllegalStateException("Could not load the licence verification public key", e);
        }
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(padBase64(value));
    }

    private static String padBase64(String value) {
        int padding = (4 - value.length() % 4) % 4;
        return value + "=".repeat(padding);
    }
}
