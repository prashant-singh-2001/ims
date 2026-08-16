package com.furnitureims.service;

import com.furnitureims.domain.AppUser;
import com.furnitureims.repository.AppUserRepository;
import com.furnitureims.security.PasswordHasher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Login, failed-attempt throttling, and password/recovery-code changes for the single
 * OWNER account (FR-AUTH-02, FR-AUTH-03, FR-AUTH-06, FR-AUTH-08).
 * <p>
 * "Locking" the screen (FR-AUTH-04/05) is a UI-layer concept, not this service's - the
 * login/lock controller shows the same screen either way and calls {@link #login} to
 * unlock. This service only ever answers "is this password correct".
 */
@Service
public class AuthService {

    /** Delay once failures reach 4, 5, 6+ - the first 3 attempts have no delay (FR-AUTH-03).
     *  Throttling is in-memory only: a lockout that a restart clears is deliberate, since
     *  this is not meant to be a permanent lockout. */
    private static final Duration[] THROTTLE_DELAYS = {
            Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60)
    };

    private final AppUserRepository appUserRepository;
    private final PasswordHasher passwordHasher;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();

    public AuthService(AppUserRepository appUserRepository, PasswordHasher passwordHasher) {
        this.appUserRepository = appUserRepository;
        this.passwordHasher = passwordHasher;
    }

    /** Seconds still remaining before another login attempt is allowed, or 0 if none. */
    public long throttleSecondsRemaining() {
        int failures = consecutiveFailures.get();
        if (failures < 4) {
            return 0;
        }
        Duration delay = THROTTLE_DELAYS[Math.min(failures - 4, THROTTLE_DELAYS.length - 1)];
        Instant last = lastFailureAt.get();
        if (last == null) {
            return 0;
        }
        long remaining = delay.minus(Duration.between(last, Instant.now())).getSeconds();
        return Math.max(0, remaining);
    }

    public AppUser login(String username, String password) {
        if (throttleSecondsRemaining() > 0) {
            throw new AuthException("Please wait " + throttleSecondsRemaining() + "s before trying again");
        }
        AppUser user = appUserRepository.findByUsername(username).orElse(null);
        if (user == null || !user.active() || !passwordHasher.matches(password, user.passwordHash())) {
            consecutiveFailures.incrementAndGet();
            lastFailureAt.set(Instant.now());
            throw new AuthException("Incorrect username or password");
        }
        consecutiveFailures.set(0);
        appUserRepository.recordLogin(user.id());
        return user;
    }

    public void changePassword(long userId, String currentPassword, String newPassword) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        if (!passwordHasher.matches(currentPassword, user.passwordHash())) {
            throw new AuthException("Current password is incorrect");
        }
        appUserRepository.updatePasswordHash(userId, passwordHasher.hash(newPassword));
    }

    /** @return a freshly generated recovery code to show the owner - the one just used
     *          stops working immediately (FR-AUTH-08). */
    public String resetPasswordWithRecoveryCode(String username, String recoveryCode, String newPassword) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new AuthException("User not found"));
        if (!passwordHasher.matches(recoveryCode, user.recoveryCodeHash())) {
            throw new AuthException("Recovery code is incorrect");
        }
        appUserRepository.updatePasswordHash(user.id(), passwordHasher.hash(newPassword));
        String newRecoveryCode = passwordHasher.generateRecoveryCode();
        appUserRepository.updateRecoveryCodeHash(user.id(), passwordHasher.hash(newRecoveryCode));
        consecutiveFailures.set(0);
        return newRecoveryCode;
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
