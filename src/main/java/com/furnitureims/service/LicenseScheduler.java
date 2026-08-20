package com.furnitureims.service;

import com.furnitureims.repository.LicenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Renews the licence lease in the background so a shop never has to think about it (M14).
 * Gated on {@link LicenseRepository#exists()} - there is nothing to renew before the wizard's
 * activation step runs, mirroring exactly how {@link BackupScheduler} is gated on
 * {@code hasBackupPassword()} so it stays inert in every test that never activates a licence.
 * <p>
 * Hourly is deliberately much more frequent than the 30-day lease actually requires - the
 * cost of an extra HTTP round trip against a personal Cloudflare Worker is nothing, and
 * checking often is what makes a revoked PC notice quickly (within a lease-renewal cycle)
 * even though the read-only wind-down it triggers only fully bites once the last-held lease
 * itself expires.
 */
@Service
public class LicenseScheduler {

    private static final Logger log = LoggerFactory.getLogger(LicenseScheduler.class);

    private final LicenseService licenseService;
    private final LicenseRepository licenseRepository;

    public LicenseScheduler(LicenseService licenseService, LicenseRepository licenseRepository) {
        this.licenseService = licenseService;
        this.licenseRepository = licenseRepository;
    }

    @Scheduled(fixedRate = 3_600_000, initialDelay = 60_000)
    public void renewIfActivated() {
        if (!licenseRepository.exists()) {
            return;
        }
        try {
            licenseService.refreshLease();
        } catch (RuntimeException e) {
            // Never allowed to propagate - a scheduled task throwing would only log a scary
            // stack trace nobody asked for (NFR-11 territory even though nothing here reaches
            // the screen). refreshLease() itself already swallows the expected offline case;
            // this is a last-resort net for anything unexpected.
            log.warn("Licence lease renewal failed unexpectedly - will retry on the next cycle.", e);
        }
    }
}
