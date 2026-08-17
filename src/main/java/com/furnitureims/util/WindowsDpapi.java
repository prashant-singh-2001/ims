package com.furnitureims.util;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows DPAPI, scoped to the current Windows user account (FR-BAK-08) - used for both
 * the Google Drive refresh token and the backup password itself (see
 * {@code SettingsService}'s backup-password accessors for why the latter exists). Data
 * protected here can only be unprotected by the same Windows user on the same machine,
 * which is exactly the "not stored in recoverable form" bar FR-BAK-06 sets: a copy of this
 * value taken off the PC is useless without that specific Windows login session.
 * <p>
 * {@code jna-platform}'s {@code Crypt32Util} wraps {@code CryptProtectData}/
 * {@code CryptUnprotectData} directly - no hand-written JNI needed.
 */
public final class WindowsDpapi {

    private WindowsDpapi() {
    }

    /** Protects a UTF-8 string, returning it as base64 so it can sit in a TEXT column. */
    public static String protect(String plaintext) {
        byte[] protectedBytes = Crypt32Util.cryptProtectData(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(protectedBytes);
    }

    /** @throws IllegalStateException if the value cannot be unprotected - the usual cause
     *          is that it was protected under a different Windows user account or on a
     *          different machine. */
    public static String unprotect(String base64Protected) {
        try {
            byte[] plaintextBytes = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(base64Protected));
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Could not decrypt a value protected on this Windows account - it may have been "
                            + "protected under a different user or a different PC.", e);
        }
    }
}
