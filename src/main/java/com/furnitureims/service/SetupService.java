package com.furnitureims.service;

import com.furnitureims.domain.ShopProfile;
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

    private final ShopProfileRepository shopProfileRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordHasher passwordHasher;

    public SetupService(ShopProfileRepository shopProfileRepository,
                         AppUserRepository appUserRepository,
                         PasswordHasher passwordHasher) {
        this.shopProfileRepository = shopProfileRepository;
        this.appUserRepository = appUserRepository;
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
}
