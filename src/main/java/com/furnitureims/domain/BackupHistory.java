package com.furnitureims.domain;

import java.time.LocalDateTime;

/**
 * One backup attempt (FR-BAK-01..16). Unlike every other domain record in this system,
 * rows here are exempt from the "nothing is hard-deleted" invariant - this is an
 * operational log of backup runs, not business data, and retention pruning (FR-BAK-09)
 * plus the archive list's own "Delete" action are both expected to remove rows outright.
 */
public record BackupHistory(
        long id,
        BackupType backupType,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Status status,
        String archiveName,
        Long sizeBytes,
        String sha256,
        String remoteFileId,
        String provider,
        String localPath,
        String errorMessage
) {
    public enum BackupType { DAILY, WEEKLY, MANUAL }

    /** UPLOAD_PENDING (FR-BAK-12): the encrypted archive exists on local disk - the Drive
     *  upload just hasn't happened yet, because the internet wasn't available. Not a
     *  failure. */
    public enum Status { SUCCESS, FAILED, UPLOAD_PENDING }
}
