package com.piecetrack.domain;

import java.time.LocalDateTime;

/**
 * The owner's login (docs/02-data-model.md 4.1). Exactly one row exists in v1 - see
 * FR-AUTH-07: {@code role} exists now so staff logins (v3) need no schema change later.
 */
public record AppUser(
        long id,
        String username,
        String passwordHash,
        String recoveryCodeHash,
        Role role,
        boolean active,
        LocalDateTime lastLoginAt
) {
    public enum Role { OWNER }
}
