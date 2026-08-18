package com.furnitureims.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * M12: resolves which {@link CloudBackupProvider} is active, and which one a given archive
 * actually went to - the two are not always the same, since {@code backup.provider} can be
 * switched after an archive was already uploaded under a different one (see {@code
 * BackupHistory#provider}). Spring injects every {@link CloudBackupProvider} bean (currently
 * {@link GoogleDriveService} and {@link OneDriveService}) into {@code providers} automatically.
 * <p>
 * Falls back to Google Drive - {@code settings.backup.provider}'s documented default - for an
 * unset, unknown or legacy-null id, rather than throwing: this keeps every install that
 * predates OneDrive support, and every {@code backup_history} row written before M12, resolving
 * to the provider they always meant.
 */
@Service
public class CloudProviders {

    private static final String FALLBACK_PROVIDER_ID = "GOOGLE_DRIVE";

    private final List<CloudBackupProvider> providers;
    private final SettingsService settingsService;

    public CloudProviders(List<CloudBackupProvider> providers, SettingsService settingsService) {
        this.providers = List.copyOf(providers);
        this.settingsService = settingsService;
    }

    /** The provider {@code backup.provider} currently points at - used for new uploads. */
    public CloudBackupProvider active() {
        return byId(settingsService.backupProvider());
    }

    /** The provider a specific archive actually went to - used to restore or delete it,
     *  regardless of which provider is active now. */
    public CloudBackupProvider byId(String providerId) {
        return findById(providerId).orElseGet(() -> findById(FALLBACK_PROVIDER_ID)
                .orElseThrow(() -> new IllegalStateException("No cloud backup providers are registered.")));
    }

    public List<CloudBackupProvider> all() {
        return providers;
    }

    private Optional<CloudBackupProvider> findById(String providerId) {
        if (providerId == null) {
            return Optional.empty();
        }
        return providers.stream().filter(p -> p.id().equals(providerId)).findFirst();
    }
}
