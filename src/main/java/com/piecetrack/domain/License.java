package com.piecetrack.domain;

import java.time.Instant;

/**
 * This installation's licence row (M14, FR-LIC-01...06) - a singleton, exactly like
 * {@link ShopProfile}. {@code licenseId}/{@code leaseToken}/{@code leaseExpiresAt} are null
 * until the first successful {@code /activate} call; everything else is known as soon as the
 * activation key is typed into the wizard.
 *
 * @param leaseToken the raw signed lease as received from the server - verified fresh by
 *                    {@code LicenseVerifier} on every {@code state()} call rather than trusted
 *                    from storage, so a corrupted or hand-edited row can never grant access.
 */
public record License(
        long id,
        String licenseId,
        String activationKey,
        String shopName,
        String fingerprint,
        String volumeSerial,
        String leaseToken,
        Instant leaseExpiresAt,
        Instant lastContactAt,
        String lastContactResult,
        Instant clockWatermark,
        Instant activatedAt
) {
}
