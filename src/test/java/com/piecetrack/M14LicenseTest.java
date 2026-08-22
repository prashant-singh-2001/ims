package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.License;
import com.piecetrack.domain.LicenseState;
import com.piecetrack.repository.AuditLogRepository;
import com.piecetrack.repository.AuditLogSearchCriteria;
import com.piecetrack.repository.LicenseRepository;
import com.piecetrack.service.LicenseService;
import com.piecetrack.service.LicenseVerifier;
import com.piecetrack.util.MachineFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M14 (licensing, activation and remote kill switch, FR-LIC-01...06): exercises
 * {@link LicenseService#state()} against real leases signed with a test-only Ed25519 key
 * pair - no mocking, no network, matching every other test class in this suite. The signing
 * key pair lives only for the life of this test's Spring context (generated inside
 * {@link TestKeyConfig}'s {@code @Bean} method, before any {@code @Test} method runs), and
 * its matching public key is what the {@code @Primary LicenseVerifier} bean below verifies
 * against - the exact override pattern {@code TestAppPathsFactory} already establishes for
 * {@link AppPaths} in {@code M13BookingTest}.
 * <p>
 * Each test writes a {@link License} row directly via {@link LicenseRepository} rather than
 * going through {@link LicenseService#activate}, since activation is the one operation in
 * this milestone that genuinely requires network access - deliberately out of scope for an
 * offline test suite. What matters here is the state machine {@link LicenseService#state()}
 * computes from whatever lease is already on disk, which is exactly what a real installation
 * does on every launch with no connectivity at all.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import({M14LicenseTest.TestPathsConfig.class, M14LicenseTest.TestKeyConfig.class})
class M14LicenseTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-m14-test-"));
        }
    }

    @TestConfiguration
    static class TestKeyConfig {
        static KeyPair keyPair;

        @Bean
        @Primary
        LicenseVerifier testLicenseVerifier() throws NoSuchAlgorithmException {
            keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            return new LicenseVerifier(publicKeyBase64);
        }
    }

    @Autowired private LicenseService licenseService;
    @Autowired private LicenseRepository licenseRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbc;

    private static final String OWN_FINGERPRINT = MachineFingerprint.stableId();

    /** The Spring context (and its one temp SQLite file) is cached and reused across every
     *  test method in this class, but {@code license} is a singleton row every test writes -
     *  without this reset, whichever test runs first would leak its row into the others,
     *  the same way {@code M12CloudProviderTest} resets {@code backup.provider} per test. */
    @BeforeEach
    void clearLicenseRow() {
        jdbc.update("DELETE FROM license");
    }

    @Test
    void validInDateFingerprintMatchingLeaseIsActive() {
        saveLicense(mintLease(OWN_FINGERPRINT, Instant.now(), Instant.now().plusSeconds(20 * 86_400)),
                Instant.now().minusSeconds(3600));

        assertEquals(LicenseState.ACTIVE, licenseService.state());
    }

    @Test
    void leaseExpiringSoonIsGrace() {
        saveLicense(mintLease(OWN_FINGERPRINT, Instant.now(), Instant.now().plusSeconds(3 * 86_400)),
                Instant.now().minusSeconds(3600));

        assertEquals(LicenseState.GRACE, licenseService.state());
    }

    @Test
    void expiredLeaseIsWindDown() {
        saveLicense(mintLease(OWN_FINGERPRINT, Instant.now().minusSeconds(40 * 86_400), Instant.now().minusSeconds(86_400)),
                Instant.now().minusSeconds(3600));

        assertEquals(LicenseState.WIND_DOWN, licenseService.state());
    }

    @Test
    void leaseSignedByAWrongKeyIsWindDown() throws NoSuchAlgorithmException, GeneralSecurityException {
        KeyPair impostorKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String leaseSignedByImpostor = mintLeaseWithKey(OWN_FINGERPRINT, Instant.now(),
                Instant.now().plusSeconds(20 * 86_400), impostorKeyPair.getPrivate());
        saveLicense(leaseSignedByImpostor, Instant.now().minusSeconds(3600));

        assertEquals(LicenseState.WIND_DOWN, licenseService.state(),
                "a lease not signed by the server's real key must never be trusted");
    }

    @Test
    void leaseBoundToADifferentMachineIsWindDown() {
        saveLicense(mintLease("some-other-machines-fingerprint", Instant.now(), Instant.now().plusSeconds(20 * 86_400)),
                Instant.now().minusSeconds(3600));

        assertEquals(LicenseState.WIND_DOWN, licenseService.state(),
                "a genuinely-signed lease for a different machine must not activate this one");
    }

    @Test
    void tamperedPayloadWithStaleSignatureIsWindDown() {
        String genuineLease = mintLease(OWN_FINGERPRINT, Instant.now(), Instant.now().plusSeconds(20 * 86_400));
        String[] parts = genuineLease.split("\\.", 2);
        String tamperedPayload = new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8)
                .replace(OWN_FINGERPRINT, "a-different-fingerprint-entirely");
        String tamperedLease = base64UrlEncode(tamperedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[1];
        saveLicense(tamperedLease, Instant.now().minusSeconds(3600));

        assertEquals(LicenseState.WIND_DOWN, licenseService.state(),
                "changing the payload without re-signing must fail signature verification");
    }

    @Test
    void clockWatermarkFarAheadOfNowIsWindDown() {
        // Simulates a rolled-back system clock: the stored watermark is the highest instant
        // this installation has ever actually observed, so if "now" sits far behind it, the
        // clock has been wound back rather than genuinely being that far in the past.
        saveLicense(mintLease(OWN_FINGERPRINT, Instant.now(), Instant.now().plusSeconds(20 * 86_400)),
                Instant.now().plusSeconds(400 * 86_400));

        assertEquals(LicenseState.WIND_DOWN, licenseService.state());
    }

    @Test
    void noLicenseRowIsUnlicensed() {
        assertTrue(licenseRepository.find().isEmpty(), "a fresh test database must start with no licence row");
        assertEquals(LicenseState.UNLICENSED, licenseService.state());
    }

    @Test
    void aGenuineStateTransitionWritesAnAuditRow() {
        saveLicense(mintLease(OWN_FINGERPRINT, Instant.now(), Instant.now().plusSeconds(20 * 86_400)),
                Instant.now().minusSeconds(3600));
        assertEquals(LicenseState.ACTIVE, licenseService.state());

        saveLicense(mintLease(OWN_FINGERPRINT, Instant.now().minusSeconds(40 * 86_400), Instant.now().minusSeconds(86_400)),
                Instant.now().minusSeconds(3600));
        assertEquals(LicenseState.WIND_DOWN, licenseService.state());

        List<com.piecetrack.domain.AuditLog> rows = auditLogRepository.search(
                new AuditLogSearchCriteria(null, null, "LICENSE_STATE_CHANGED", "LICENSE"), 0);
        assertTrue(rows.stream().anyMatch(a -> a.note() != null && a.note().contains("ACTIVE")
                        && a.note().contains("WIND_DOWN")),
                "a genuine ACTIVE -> WIND_DOWN transition should write an audit row");
    }

    private void saveLicense(String leaseToken, Instant clockWatermark) {
        Instant now = Instant.now();
        licenseRepository.save(new License(1, "test-license-id", "TEST-ACTIVATION-KEY", "Test Shop",
                OWN_FINGERPRINT, null, leaseToken, now.plusSeconds(20 * 86_400), now, "OK",
                clockWatermark, now));
    }

    private static String mintLease(String fingerprint, Instant issuedAt, Instant expiresAt) {
        return mintLeaseWithKey(fingerprint, issuedAt, expiresAt, TestKeyConfig.keyPair.getPrivate());
    }

    private static String mintLeaseWithKey(String fingerprint, Instant issuedAt, Instant expiresAt,
                                            PrivateKey signingKey) {
        String json = "{"
                + "\"licenseId\":\"test-license-id\","
                + "\"shopName\":\"Test Shop\","
                + "\"fp\":\"" + fingerprint + "\","
                + "\"issuedAt\":\"" + issuedAt + "\","
                + "\"expiresAt\":\"" + expiresAt + "\","
                + "\"status\":\"ACTIVE\"}";
        byte[] payloadBytes = json.getBytes(StandardCharsets.UTF_8);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(signingKey);
            signature.update(payloadBytes);
            byte[] signatureBytes = signature.sign();
            return base64UrlEncode(payloadBytes) + "." + base64UrlEncode(signatureBytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64UrlDecode(String value) {
        int padding = (4 - value.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(value + "=".repeat(padding));
    }
}
