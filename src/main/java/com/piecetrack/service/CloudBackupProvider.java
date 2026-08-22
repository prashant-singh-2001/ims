package com.piecetrack.service;

import java.nio.file.Path;

/**
 * FR-BAK-07/08: one connected cloud destination for encrypted backup archives. {@link
 * GoogleDriveService} and {@code OneDriveService} both implement this so {@code BackupService}
 * and {@code RestoreService} never need to know which provider is active - see {@code
 * CloudProviders} for how "active" is resolved.
 * <p>
 * Declares {@code throws Exception} rather than any provider-specific checked exceptions (e.g.
 * Google's {@code GeneralSecurityException}, an artifact of its transport, irrelevant to a
 * Graph-based implementation) - every caller already does {@code catch (Exception e)}.
 */
public interface CloudBackupProvider {

    /** Stable identifier persisted in {@code backup.provider} and {@code backup_history.provider}
     *  (e.g. {@code "GOOGLE_DRIVE"}, {@code "ONEDRIVE"}) - never shown to the user. */
    String id();

    /** User-facing name for this provider (e.g. {@code "Google Drive"}, {@code "OneDrive"}). */
    String displayName();

    boolean isConnected();

    /** Opens the browser for consent and stores the resulting refresh token. Blocks the calling
     *  thread until the owner finishes in the browser (or the flow fails). */
    void connect() throws Exception;

    void disconnect();

    UploadedFile upload(Path localFile, String remoteName) throws Exception;

    void download(String fileId, Path targetFile) throws Exception;

    void delete(String fileId) throws Exception;
}
