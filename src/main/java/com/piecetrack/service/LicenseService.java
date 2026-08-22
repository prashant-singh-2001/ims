package com.piecetrack.service;

import com.piecetrack.domain.License;
import com.piecetrack.domain.LicenseState;
import com.piecetrack.repository.LicenseRepository;
import com.piecetrack.util.MachineFingerprint;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Owns this installation's licence state (M14, FR-LIC-01...06): activating against the
 * licence server, silently renewing the lease in the background, and answering
 * {@link #state()} for every enforcement site ({@code SceneRouter}, {@code TopBar}, the
 * setup wizard).
 * <p>
 * {@link #state()} itself does no network I/O - it only verifies whatever lease is already
 * stored (see {@link LicenseVerifier}) against the current machine and the current clock.
 * That is what keeps the app working offline for the full lease window (NFR-06, as amended
 * by this milestone): a PC with no connectivity at all still answers {@code ACTIVE} right up
 * until the stored lease's {@code expiresAt}.
 */
@Service
public class LicenseService {

    /** Everything still works in this window before expiry, but a warning is shown - long
     *  enough that an owner with patchy connectivity notices before enforcement bites. */
    private static final Duration GRACE_WINDOW = Duration.ofDays(7);

    /** How far behind the highest-ever-observed instant the clock may legitimately drift
     *  (DST, a dead CMOS battery) before it is treated as a deliberate rollback attempt to
     *  keep an expired lease looking current. */
    private static final Duration CLOCK_ROLLBACK_TOLERANCE = Duration.ofHours(48);

    /** The watermark is only persisted when it would move forward by more than this - full
     *  48h+ precision doesn't need minute-level writes on every screen navigation. */
    private static final Duration WATERMARK_WRITE_THRESHOLD = Duration.ofMinutes(15);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final LicenseRepository licenseRepository;
    private final LicenseVerifier licenseVerifier;
    private final SettingsService settingsService;
    private final AuditLogService auditLogService;

    private volatile String cachedFingerprint;
    private volatile LicenseState lastObservedState;

    public LicenseService(LicenseRepository licenseRepository, LicenseVerifier licenseVerifier,
                           SettingsService settingsService, AuditLogService auditLogService) {
        this.licenseRepository = licenseRepository;
        this.licenseVerifier = licenseVerifier;
        this.settingsService = settingsService;
        this.auditLogService = auditLogService;
    }

    /**
     * The single source of truth every enforcement site reads. Audits a transition
     * (FR-SYS-03) exactly once, the first time it is observed - not on every call, since
     * {@code SceneRouter.navigate()} calls this on every screen change and an audit row per
     * navigation would drown out everything else in the log.
     */
    public synchronized LicenseState state() {
        LicenseState computed = computeState();
        if (lastObservedState != null && lastObservedState != computed) {
            auditLogService.record("LICENSE_STATE_CHANGED", "LICENSE", null,
                    "License state changed from " + lastObservedState + " to " + computed);
        }
        lastObservedState = computed;
        return computed;
    }

    private LicenseState computeState() {
        Optional<License> stored = licenseRepository.find();
        if (stored.isEmpty()) {
            return LicenseState.UNLICENSED;
        }
        License license = stored.get();
        Instant now = Instant.now();

        if (license.clockWatermark() != null && now.isBefore(license.clockWatermark().minus(CLOCK_ROLLBACK_TOLERANCE))) {
            return LicenseState.WIND_DOWN;
        }
        bumpWatermarkIfNeeded(license, now);

        return licenseVerifier.verify(license.leaseToken())
                .filter(lease -> ownFingerprint().equals(lease.fingerprint()))
                .map(lease -> classify(lease, now))
                .orElse(LicenseState.WIND_DOWN);
    }

    private LicenseState classify(LicenseVerifier.LeasePayload lease, Instant now) {
        if (lease.expiresAt() == null || now.isAfter(lease.expiresAt())) {
            return LicenseState.WIND_DOWN;
        }
        if (now.isAfter(lease.expiresAt().minus(GRACE_WINDOW))) {
            return LicenseState.GRACE;
        }
        return LicenseState.ACTIVE;
    }

    private void bumpWatermarkIfNeeded(License license, Instant now) {
        if (license.clockWatermark() == null || now.isAfter(license.clockWatermark().plus(WATERMARK_WRITE_THRESHOLD))) {
            licenseRepository.save(withClockWatermark(license, now));
        }
    }

    /**
     * Binds an activation key to this machine (FR-LIC-01/02). Runs on whatever thread the
     * caller is on - the setup wizard's step controller is expected to run this off the
     * JavaFX Application Thread, exactly the way {@code Step4GoogleDriveController} does for
     * the OAuth connect flow.
     *
     * @throws IllegalStateException the server rejected the key (unknown, revoked, or - the
     *         "I will know" case this milestone exists for - already bound to a different PC)
     */
    public void activate(String activationKey) throws IOException, InterruptedException {
        String fingerprint = ownFingerprint();
        String volumeSerial = MachineFingerprint.volumeSerial();

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("activationKey", activationKey.trim());
        requestBody.addProperty("fingerprint", fingerprint);
        requestBody.addProperty("volumeSerial", volumeSerial);
        requestBody.addProperty("machineName", localMachineName());

        JsonObject body = post("/activate", requestBody, "activation");
        if (body == null) {
            auditLogService.record("LICENSE_ACTIVATION_FAILED", "LICENSE", null, "No response from licence server");
            throw new IllegalStateException(
                    "Could not reach the licence server. Check the internet connection and try again.");
        }
        if (!body.has("lease")) {
            String message = body.has("error") ? body.get("error").getAsString() : "Activation was rejected.";
            auditLogService.record("LICENSE_ACTIVATION_FAILED", "LICENSE", null, message);
            throw new IllegalStateException(message);
        }

        String leaseToken = body.get("lease").getAsString();
        String shopName = licenseVerifier.verify(leaseToken)
                .map(LicenseVerifier.LeasePayload::shopName).orElse(null);
        Instant now = Instant.now();
        licenseRepository.save(new License(1, body.get("licenseId").getAsString(), activationKey.trim(), shopName,
                fingerprint, volumeSerial, leaseToken, Instant.parse(body.get("expiresAt").getAsString()),
                now, "OK", now, now));
        auditLogService.record("LICENSE_ACTIVATED", "LICENSE", null, "Activated licence " + body.get("licenseId"));
        lastObservedState = null;
    }

    /**
     * Silent background renewal (called by {@link LicenseScheduler}). Deliberately never
     * throws and never touches the stored lease on failure of any kind - a network hiccup, a
     * DNS failure, or the server being unreachable must never accelerate a wind-down beyond
     * what the already-issued lease's own expiry already governs. Only an explicit rejection
     * from the server (revoked, or bound elsewhere) is recorded, for the Settings screen to
     * surface as "last contact: rejected - <reason>".
     */
    public void refreshLease() {
        Optional<License> stored = licenseRepository.find();
        if (stored.isEmpty() || stored.get().licenseId() == null) {
            return;
        }
        License license = stored.get();
        String fingerprint = ownFingerprint();

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("licenseId", license.licenseId());
        requestBody.addProperty("fingerprint", fingerprint);
        requestBody.addProperty("machineName", localMachineName());

        JsonObject body;
        try {
            body = post("/lease", requestBody, "lease renewal");
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (body == null) {
            return;
        }
        if (!body.has("lease")) {
            String message = body.has("error") ? body.get("error").getAsString() : "renewal rejected";
            licenseRepository.save(withContactResult(license, Instant.now(), "REJECTED: " + message));
            return;
        }

        String leaseToken = body.get("lease").getAsString();
        String shopName = licenseVerifier.verify(leaseToken)
                .map(LicenseVerifier.LeasePayload::shopName).orElse(license.shopName());
        Instant now = Instant.now();
        licenseRepository.save(new License(1, license.licenseId(), license.activationKey(), shopName, fingerprint,
                MachineFingerprint.volumeSerial(), leaseToken, Instant.parse(body.get("expiresAt").getAsString()),
                now, "OK", license.clockWatermark(), license.activatedAt()));
    }

    private JsonObject post(String path, JsonObject requestBody, String operation) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settingsService.licenseServerUrl() + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.body() == null || response.body().isBlank()) {
            throw new IOException("The licence server returned an empty response for " + operation + ".");
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String ownFingerprint() {
        String value = cachedFingerprint;
        if (value == null) {
            value = MachineFingerprint.stableId();
            cachedFingerprint = value;
        }
        return value;
    }

    private static String localMachineName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static License withClockWatermark(License license, Instant watermark) {
        return new License(license.id(), license.licenseId(), license.activationKey(), license.shopName(),
                license.fingerprint(), license.volumeSerial(), license.leaseToken(), license.leaseExpiresAt(),
                license.lastContactAt(), license.lastContactResult(), watermark, license.activatedAt());
    }

    private static License withContactResult(License license, Instant contactedAt, String result) {
        return new License(license.id(), license.licenseId(), license.activationKey(), license.shopName(),
                license.fingerprint(), license.volumeSerial(), license.leaseToken(), license.leaseExpiresAt(),
                contactedAt, result, license.clockWatermark(), license.activatedAt());
    }
}
