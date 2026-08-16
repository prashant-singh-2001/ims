package com.furnitureims.service;

import com.furnitureims.domain.ShopProfile;
import com.furnitureims.repository.AppSettingRepository;
import com.furnitureims.repository.AppUserRepository;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.security.PasswordHasher;
import org.springframework.stereotype.Service;

/**
 * Drives the first-run wizard (FR-AUTH-01, FR-SYS-01, FR-BAK-06; screens in
 * docs/03-screens.md section 2.1). The app is not considered set up - and
 * {@link #isSetupComplete()} gates every other screen - until a shop profile and the
 * owner account both exist.
 */
@Service
public class SetupService {

    private static final String BACKUP_PASSWORD_VERIFIER_KEY = "backup.password_verifier_hash";

    private final ShopProfileRepository shopProfileRepository;
    private final AppUserRepository appUserRepository;
    private final AppSettingRepository appSettingRepository;
    private final PasswordHasher passwordHasher;

    public SetupService(ShopProfileRepository shopProfileRepository,
                         AppUserRepository appUserRepository,
                         AppSettingRepository appSettingRepository,
                         PasswordHasher passwordHasher) {
        this.shopProfileRepository = shopProfileRepository;
        this.appUserRepository = appUserRepository;
        this.appSettingRepository = appSettingRepository;
        this.passwordHasher = passwordHasher;
    }

    public boolean isSetupComplete() {
        return shopProfileRepository.exists() && appUserRepository.exists();
    }

    public void saveShopProfile(ShopProfile profile) {
        shopProfileRepository.save(profile);
    }

    /** @return the plain-text recovery code - shown to the owner exactly once, with an
     *          instruction to write it down and keep it off this PC. Only its hash is
     *          persisted (FR-AUTH-08). */
    public String createOwnerAccount(String username, String password) {
        if (appUserRepository.exists()) {
            throw new IllegalStateException("An owner account already exists");
        }
        String recoveryCode = passwordHasher.generateRecoveryCode();
        appUserRepository.create(username, passwordHasher.hash(password), passwordHasher.hash(recoveryCode));
        return recoveryCode;
    }

    /**
     * Stores only a bcrypt verifier for the backup password, never the password itself
     * (FR-BAK-06), so a later screen can confirm a re-entered password is correct.
     * <p>
     * Deliberately out of scope here: deriving the actual AES encryption key (Argon2id,
     * per-archive salt) and how an unattended scheduled backup obtains that key without the
     * owner retyping a password every night. Those are milestone M8 concerns
     * (docs/04-roadmap.md). Recording the verifier now just means the wizard need not be
     * repeated once M8 lands.
     */
    public void recordBackupPasswordVerifier(String backupPassword) {
        appSettingRepository.set(BACKUP_PASSWORD_VERIFIER_KEY, passwordHasher.hash(backupPassword));
    }
}
