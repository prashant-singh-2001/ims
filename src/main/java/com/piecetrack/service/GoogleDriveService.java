package com.piecetrack.service;

import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

/**
 * FR-BAK-07/08: Google Drive access via the OAuth 2.0 desktop loopback flow, scoped to the
 * {@code drive.file} grant only - this app can only see files it created itself, never the
 * rest of the owner's Drive. The client ID/secret are never hardcoded: the SRS is explicit
 * that a Google Cloud project and OAuth client are a prerequisite only the owner can create
 * (docs/01-requirements.md section 5), so they come from {@link SettingsService} instead.
 * <p>
 * The refresh token is the only thing persisted (via DPAPI, see
 * {@link SettingsService#setGoogleRefreshToken}); every {@link Drive} client used here is
 * built fresh from it, so there is no long-lived credential cache to go stale mid-session.
 */
@Service
public class GoogleDriveService implements CloudBackupProvider {

    private static final String APPLICATION_NAME = "PieceTrack";
    private static final List<String> SCOPES = Collections.singletonList(
            "https://www.googleapis.com/auth/drive.file");
    private static final String TOKEN_SERVER_URL = "https://oauth2.googleapis.com/token";
    private static final String AUTH_SERVER_URL = "https://accounts.google.com/o/oauth2/auth";

    private final SettingsService settingsService;
    private final AuditLogService auditLogService;

    public GoogleDriveService(SettingsService settingsService, AuditLogService auditLogService) {
        this.settingsService = settingsService;
        this.auditLogService = auditLogService;
    }

    @Override
    public String id() {
        return "GOOGLE_DRIVE";
    }

    @Override
    public String displayName() {
        return "Google Drive";
    }

    @Override
    public boolean isConnected() {
        return settingsService.isGoogleDriveConnected();
    }

    /** Opens the browser once for consent and stores the resulting refresh token. Blocks
     *  the calling thread until the owner finishes in the browser (or the flow fails) - the
     *  UI runs this off the JavaFX Application Thread. */
    @Override
    public void connect() throws IOException, GeneralSecurityException {
        String clientId = requireClientId();
        String clientSecret = requireClientSecret();

        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setAuthUri(AUTH_SERVER_URL)
                .setTokenUri(TOKEN_SERVER_URL);
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets().setInstalled(details);

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                transport, jsonFactory, clientSecrets, SCOPES)
                .setDataStoreFactory(new MemoryDataStoreFactory())
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(0).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("owner");

        if (credential.getRefreshToken() == null) {
            throw new IllegalStateException("Google did not return a long-lived grant - open "
                    + "https://myaccount.google.com/permissions, remove this app's access, and connect again "
                    + "(Google only issues a refresh token on the first consent for a given app).");
        }
        settingsService.setGoogleRefreshToken(credential.getRefreshToken());
        auditLogService.record("SETTING_CHANGED", "GOOGLE_DRIVE", null, "Connected to Google Drive");
    }

    @Override
    public void disconnect() {
        settingsService.clearGoogleRefreshToken();
        auditLogService.record("SETTING_CHANGED", "GOOGLE_DRIVE", null, "Disconnected from Google Drive");
    }

    /** FR-BAK-07: finds (or creates, on first use) the dedicated backup folder. */
    private String resolveFolderId(Drive drive) throws IOException {
        String folderName = settingsService.backupDriveFolderName();
        FileList result = drive.files().list()
                .setQ("name = '" + folderName.replace("'", "\\'")
                        + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();
        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }
        File folderMetadata = new File();
        folderMetadata.setName(folderName);
        folderMetadata.setMimeType("application/vnd.google-apps.folder");
        File created = drive.files().create(folderMetadata).setFields("id").execute();
        return created.getId();
    }

    @Override
    public UploadedFile upload(Path localFile, String remoteName) throws IOException, GeneralSecurityException {
        Drive drive = driveClient();
        String folderId = resolveFolderId(drive);
        File metadata = new File();
        metadata.setName(remoteName);
        metadata.setParents(Collections.singletonList(folderId));
        FileContent content = new FileContent("application/octet-stream", localFile.toFile());
        File uploaded = drive.files().create(metadata, content).setFields("id, name, size").execute();
        return new UploadedFile(uploaded.getId(), uploaded.getName(),
                uploaded.getSize() == null ? 0 : uploaded.getSize());
    }

    @Override
    public void download(String fileId, Path targetFile) throws IOException, GeneralSecurityException {
        Drive drive = driveClient();
        try (OutputStream out = Files.newOutputStream(targetFile)) {
            drive.files().get(fileId).executeMediaAndDownloadTo(out);
        }
    }

    @Override
    public void delete(String fileId) throws IOException, GeneralSecurityException {
        driveClient().files().delete(fileId).execute();
    }

    /** Builds a fresh {@link Drive} client from the stored refresh token - see this class's
     *  Javadoc for why nothing is cached across calls. */
    private Drive driveClient() throws IOException, GeneralSecurityException {
        String clientId = requireClientId();
        String clientSecret = requireClientSecret();
        String refreshToken = settingsService.googleRefreshToken().orElseThrow(() ->
                new IllegalStateException("Google Drive is not connected - connect it under Settings > Backup."));

        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        Credential credential = new Credential.Builder(com.google.api.client.auth.oauth2.BearerToken
                .authorizationHeaderAccessMethod())
                .setTransport(transport)
                .setJsonFactory(jsonFactory)
                .setTokenServerUrl(new GenericUrl(TOKEN_SERVER_URL))
                .setClientAuthentication(new ClientParametersAuthentication(clientId, clientSecret))
                .build();
        credential.setRefreshToken(refreshToken);

        if (!credential.refreshToken()) {
            throw new IllegalStateException(
                    "Google Drive access has expired or was revoked - reconnect it under Settings > Backup.");
        }

        return new Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private String requireClientId() {
        return settingsService.googleClientId()
                .orElseThrow(() -> new IllegalStateException(
                        "Enter your Google OAuth client ID first (Settings > Backup)."));
    }

    private String requireClientSecret() {
        return settingsService.googleClientSecret()
                .orElseThrow(() -> new IllegalStateException(
                        "Enter your Google OAuth client secret first (Settings > Backup)."));
    }
}
