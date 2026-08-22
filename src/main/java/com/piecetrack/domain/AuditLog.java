package com.piecetrack.domain;

import java.time.LocalDateTime;

/**
 * One append-only audit entry (FR-SYS-03). {@code username} is a denormalized join for
 * display convenience - the row itself keys on {@code userId}, which stays valid even if
 * the user is later renamed.
 */
public record AuditLog(
        long id,
        LocalDateTime loggedAt,
        Long userId,
        String username,
        String action,
        String entityType,
        Long entityId,
        String beforeJson,
        String afterJson,
        String note
) {
}
