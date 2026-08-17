package com.furnitureims.ui.backup;

import com.furnitureims.domain.BackupHistory;

/** Read-only row wrapper for the archive list (docs/03-screens.md section 9). */
public class BackupHistoryRow {

    private final BackupHistory history;

    public BackupHistoryRow(BackupHistory history) {
        this.history = history;
    }

    public BackupHistory getHistory() {
        return history;
    }

    public long getId() {
        return history.id();
    }

    public String getStartedAt() {
        return history.startedAt().toString().replace('T', ' ');
    }

    public String getBackupType() {
        return history.backupType().name();
    }

    public String getStatus() {
        return history.status().name();
    }

    public String getSize() {
        if (history.sizeBytes() == null) {
            return "-";
        }
        double mb = history.sizeBytes() / (1024.0 * 1024.0);
        return String.format("%.1f MB", mb);
    }

    public String getVerified() {
        return history.sha256() != null ? "Yes" : "No";
    }

    public String getLocation() {
        boolean local = history.localPath() != null;
        boolean drive = history.driveFileId() != null;
        if (local && drive) {
            return "Local + Drive";
        }
        if (drive) {
            return "Drive only";
        }
        if (local) {
            return "Local only";
        }
        return "-";
    }

    public boolean isRestorable() {
        return (history.status() == BackupHistory.Status.SUCCESS
                || history.status() == BackupHistory.Status.UPLOAD_PENDING)
                && (history.localPath() != null || history.driveFileId() != null);
    }
}
