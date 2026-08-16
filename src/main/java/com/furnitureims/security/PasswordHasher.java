package com.furnitureims.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Login passwords and the recovery code are hashed with bcrypt at cost 12 - the
 * "Argon2id, or bcrypt cost >= 12" half of FR-AUTH-02 that doesn't need an extra
 * BouncyCastle dependency on the classpath. Never store or log a plain-text password or
 * recovery code anywhere (NFR-10) - only what this class produces.
 */
@Component
public class PasswordHasher {

    private static final int BCRYPT_STRENGTH = 12;
    // No 0/O/1/I - the owner copies this by hand onto paper (FR-AUTH-08).
    private static final String RECOVERY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_CODE_LENGTH = 16;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    private final SecureRandom random = new SecureRandom();

    public String hash(String plainText) {
        return encoder.encode(plainText);
    }

    public boolean matches(String plainText, String hash) {
        if (plainText == null || hash == null) {
            return false;
        }
        return encoder.matches(plainText, hash);
    }

    /** e.g. "7K4H-QX2P-9MRT-3VDC" - shown to the owner exactly once, at setup. */
    public String generateRecoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(RECOVERY_CODE_ALPHABET.charAt(random.nextInt(RECOVERY_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
