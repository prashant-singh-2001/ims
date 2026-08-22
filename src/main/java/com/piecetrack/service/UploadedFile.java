package com.piecetrack.service;

/**
 * What a {@link CloudBackupProvider} hands back after putting an archive in the cloud. The
 * {@code id} is the provider's own opaque handle for that file - a Google Drive file id or a
 * Microsoft Graph item id - and is what gets stored in {@code backup_history.remote_file_id}
 * so the archive can later be downloaded, deleted or pruned.
 * <p>
 * Promoted out of {@code GoogleDriveService} in M12: it never contained anything
 * Google-specific, and a shared provider interface needs a shared return type.
 */
public record UploadedFile(String id, String name, long sizeBytes) {
}
