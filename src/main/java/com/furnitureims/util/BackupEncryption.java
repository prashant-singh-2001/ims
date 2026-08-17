package com.furnitureims.util;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

/**
 * FR-BAK-05: encrypts a backup archive with AES-256-GCM, keyed by PBKDF2WithHmacSHA256 at
 * {@value #PBKDF2_ITERATIONS} iterations (the FR explicitly allows this as an alternative
 * to Argon2id) over a random per-archive salt - both built into the JDK, no extra crypto
 * library needed. The header (format marker, iteration count, salt, IV) is written in the
 * clear before the ciphertext, exactly as the FR requires ("only the format version, KDF
 * parameters and salt shall be readable in the clear") - GCM's own authentication tag is
 * what turns a wrong password into a clean, detectable failure rather than silent garbage.
 */
public final class BackupEncryption {

    private static final String FORMAT_MAGIC = "FIMSBAK1";
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private BackupEncryption() {
    }

    /** Thrown when decryption fails because of a wrong password (GCM tag mismatch) rather
     *  than some other I/O problem - callers use this to show a clean, specific message. */
    public static class WrongPasswordException extends RuntimeException {
        public WrongPasswordException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static void encryptFile(Path plainFile, Path encryptedFile, char[] password) {
        try {
            byte[] salt = randomBytes(SALT_LENGTH_BYTES);
            byte[] iv = randomBytes(GCM_IV_LENGTH_BYTES);
            SecretKeySpec key = deriveKey(password, salt, PBKDF2_ITERATIONS);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            try (OutputStream rawOut = Files.newOutputStream(encryptedFile);
                 DataOutputStream header = new DataOutputStream(rawOut)) {
                header.write(FORMAT_MAGIC.getBytes(StandardCharsets.US_ASCII));
                header.writeInt(PBKDF2_ITERATIONS);
                header.writeInt(salt.length);
                header.write(salt);
                header.writeInt(iv.length);
                header.write(iv);
                header.flush();

                try (CipherOutputStream cipherOut = new CipherOutputStream(rawOut, cipher);
                     InputStream in = Files.newInputStream(plainFile)) {
                    in.transferTo(cipherOut);
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt backup archive.", e);
        }
    }

    public static void decryptFile(Path encryptedFile, Path plainFile, char[] password) {
        try (InputStream rawIn = Files.newInputStream(encryptedFile);
             DataInputStream header = new DataInputStream(rawIn)) {
            byte[] magic = new byte[FORMAT_MAGIC.length()];
            header.readFully(magic);
            if (!FORMAT_MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Not a recognized backup archive format.");
            }
            int iterations = header.readInt();
            byte[] salt = new byte[header.readInt()];
            header.readFully(salt);
            byte[] iv = new byte[header.readInt()];
            header.readFully(iv);

            SecretKeySpec key = deriveKey(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            try (CipherInputStream cipherIn = new CipherInputStream(rawIn, cipher);
                 OutputStream out = Files.newOutputStream(plainFile)) {
                cipherIn.transferTo(out);
            }
        } catch (IOException e) {
            if (isBadTag(e)) {
                throw new WrongPasswordException(
                        "Incorrect backup password, or the archive is corrupted.", e);
            }
            throw new IllegalStateException("Failed to decrypt backup archive.", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt backup archive.", e);
        }
    }

    /** {@link CipherInputStream} wraps the {@link BadPaddingException} GCM throws on a tag
     *  mismatch inside an {@link IOException} - this unwraps it regardless of exactly how
     *  deep the JDK's own stream implementation buries it. */
    private static boolean isBadTag(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof BadPaddingException) {
                return true;
            }
        }
        return false;
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
