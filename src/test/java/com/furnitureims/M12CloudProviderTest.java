package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.repository.AppSettingRepository;
import com.furnitureims.service.CloudBackupProvider;
import com.furnitureims.service.CloudProviders;
import com.furnitureims.service.GoogleDriveService;
import com.furnitureims.service.OneDriveService;
import com.furnitureims.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M12 (OneDrive as an alternative backup destination): {@link CloudProviders}'
 * resolution rules, and the disconnect NPE fix ({@code AppSettingRepository.delete}) that
 * both {@link GoogleDriveService} and {@link OneDriveService} now rely on.
 * <p>
 * Neither provider's actual OAuth/network path is exercised here, for the same reason
 * {@code M8BackupTest}'s class Javadoc gives for Google: no real credentials exist in this
 * environment. What's tested is everything around that boundary - which provider a setting
 * or a stored archive resolves to, and that connect/disconnect never throws before a real
 * network call would even happen.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(M12CloudProviderTest.TestPathsConfig.class)
class M12CloudProviderTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-m12-test-"));
        }
    }

    @Autowired private CloudProviders cloudProviders;
    @Autowired private SettingsService settingsService;
    @Autowired private GoogleDriveService googleDriveService;
    @Autowired private OneDriveService oneDriveService;
    @Autowired private AppSettingRepository appSettingRepository;

    /** All tests in this class share one Spring context and one on-disk database (the
     *  established pattern - see this class's own Javadoc and {@code M8BackupTest}'s), so
     *  {@code backup.provider} must be put back to genuinely unset before each test - JUnit
     *  does not guarantee method execution order, and a value another test left behind would
     *  otherwise make {@link #activeDefaultsToGoogleDriveOnAFreshInstall} flaky. */
    @BeforeEach
    void resetBackupProviderSetting() {
        appSettingRepository.delete("backup.provider");
    }

    @Test
    void activeDefaultsToGoogleDriveOnAFreshInstall() {
        assertEquals("GOOGLE_DRIVE", cloudProviders.active().id(),
                "a fresh install (no backup.provider setting written yet) must resolve to Google Drive, "
                        + "so pre-M12 installs are unaffected");
    }

    @Test
    void switchingBackupProviderChangesWhatActiveReturns() {
        settingsService.setBackupProvider("ONEDRIVE");
        assertEquals("ONEDRIVE", cloudProviders.active().id());

        settingsService.setBackupProvider("GOOGLE_DRIVE");
        assertEquals("GOOGLE_DRIVE", cloudProviders.active().id());
    }

    @Test
    void byIdResolvesAnArchiveToTheProviderItWasActuallyUploadedUnderNotTheCurrentlyActiveOne() {
        settingsService.setBackupProvider("ONEDRIVE");

        CloudBackupProvider resolved = cloudProviders.byId("GOOGLE_DRIVE");

        assertEquals("GOOGLE_DRIVE", resolved.id(),
                "byId must resolve by the id it was asked for, ignoring what's currently active");
        assertSame(googleDriveService, resolved);
    }

    @Test
    void anUnknownOrLegacyNullProviderIdFallsBackToGoogleDriveRatherThanThrowing() {
        assertEquals("GOOGLE_DRIVE", cloudProviders.byId(null).id(),
                "backup_history rows written before M12 have a null provider column - "
                        + "they must still resolve to the provider they always meant");
        assertEquals("GOOGLE_DRIVE", cloudProviders.byId("SOME_FUTURE_PROVIDER_NOT_YET_SUPPORTED").id());
    }

    @Test
    void disconnectingGoogleDriveClearsTheTokenAndReportsNotConnectedWithoutThrowing() {
        settingsService.setGoogleRefreshToken("fake-refresh-token-for-test");
        assertTrue(googleDriveService.isConnected());

        googleDriveService.disconnect();

        assertFalse(googleDriveService.isConnected());
    }

    @Test
    void disconnectingOneDriveClearsTheTokenAndReportsNotConnectedWithoutThrowing() {
        settingsService.setOneDriveRefreshToken("fake-refresh-token-for-test");
        assertTrue(oneDriveService.isConnected());

        oneDriveService.disconnect();

        assertFalse(oneDriveService.isConnected());
    }
}
