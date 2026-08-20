package com.furnitureims.service;

import com.furnitureims.repository.AppSettingRepository;
import com.furnitureims.security.PasswordHasher;
import com.furnitureims.util.WindowsDpapi;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/** Typed accessors over the generic app_setting key/value store (FR-SYS-02). */
@Service
public class SettingsService {

    private static final String IDLE_LOCK_MINUTES_KEY = "security.idle_lock_minutes";
    private static final int DEFAULT_IDLE_LOCK_MINUTES = 10;
    private static final String REDACTED = "(redacted)";

    private final AppSettingRepository settings;
    private final PasswordHasher passwordHasher;
    private final AuditLogService auditLogService;

    public SettingsService(AppSettingRepository settings, PasswordHasher passwordHasher,
                            AuditLogService auditLogService) {
        this.settings = settings;
        this.passwordHasher = passwordHasher;
        this.auditLogService = auditLogService;
    }

    /** FR-SYS-03: every settings change is audited under one action name, with the changed
     *  key and its new value - {@code value} must already be redacted by the caller for
     *  anything secret (passwords, client secrets), matching NFR-10's "never logged" rule
     *  for the same category of data. */
    private void auditSettingChanged(String key, String value) {
        auditLogService.record("SETTING_CHANGED", "APP_SETTING", null, null, Map.of("key", key, "value", value));
    }

    /** FR-AUTH-04: default 10 minutes, valid range 1-120, or 0 meaning "never". */
    public int idleLockMinutes() {
        return settings.get(IDLE_LOCK_MINUTES_KEY)
                .map(Integer::parseInt)
                .orElse(DEFAULT_IDLE_LOCK_MINUTES);
    }

    public void setIdleLockMinutes(int minutes) {
        if (minutes < 0 || minutes > 120) {
            throw new IllegalArgumentException("Idle lock minutes must be between 0 (never) and 120");
        }
        settings.set(IDLE_LOCK_MINUTES_KEY, Integer.toString(minutes));
        auditSettingChanged(IDLE_LOCK_MINUTES_KEY, Integer.toString(minutes));
    }

    // ---- Tax / GST (M10) ----------------------------------------------------------------

    private static final String GST_ENABLED_KEY = "tax.gst_enabled";

    /** M10: whether this shop is GST-registered. Defaults to on - the only value that keeps
     *  every pre-M10 computation in {@code SalesInvoiceService.preview} and
     *  {@code PurchaseBillService.preview} byte-identical for installations that predate this
     *  toggle. A shop that turns it off gets plain bills with zero tax everywhere instead of a
     *  schema change: the GST-specific columns this touches (HSN code, GST rate, place of
     *  supply, GSTIN, state code) are all {@code NOT NULL} with no default and SQLite cannot
     *  drop that constraint without rebuilding several foreign-key-referenced tables, so
     *  GST-off writes sentinel values into them instead of relaxing the schema. */
    public boolean isGstEnabled() {
        return Boolean.parseBoolean(settings.getOrDefault(GST_ENABLED_KEY, "true"));
    }

    public void setGstEnabled(boolean enabled) {
        settings.set(GST_ENABLED_KEY, Boolean.toString(enabled));
        auditSettingChanged(GST_ENABLED_KEY, Boolean.toString(enabled));
    }

    // ---- Documents (FR-DOC-01..06) -----------------------------------------------------

    private static final String SHOW_PIECE_TAGS_KEY = "document.show_piece_tags";
    private static final String SMTP_HOST_KEY = "email.smtp_host";
    private static final String SMTP_PORT_KEY = "email.smtp_port";
    private static final String SMTP_USE_TLS_KEY = "email.use_tls";
    private static final String SMTP_USERNAME_KEY = "email.username";
    private static final String SMTP_APP_PASSWORD_KEY = "email.app_password";
    private static final String EMAIL_FROM_NAME_KEY = "email.from_name";
    private static final String EMAIL_SUBJECT_TEMPLATE_KEY = "email.subject_template";
    private static final String EMAIL_BODY_TEMPLATE_KEY = "email.body_template";
    private static final String WHATSAPP_MESSAGE_TEMPLATE_KEY = "whatsapp.message_template";

    private static final String DEFAULT_EMAIL_SUBJECT_TEMPLATE = "Invoice {invoiceNo} from {shopName}";
    private static final String DEFAULT_EMAIL_BODY_TEMPLATE = """
            Dear {customerName},

            Please find attached invoice {invoiceNo} dated {invoiceDate} for {grandTotal}.

            Thank you for your business.

            {shopName}""";
    private static final String DEFAULT_WHATSAPP_MESSAGE_TEMPLATE = "Hello {customerName}, your invoice "
            + "{invoiceNo} dated {invoiceDate} for {grandTotal} from {shopName} is ready. "
            + "Thank you for shopping with us!";

    public record SmtpSettings(String host, int port, boolean useTls, String username, String appPassword,
                                String fromName) {
    }

    /** FR-DOC-06: whether individual piece tags print beneath a grouped line. Defaults to
     *  on, since traceability to the physical piece is this system's whole premise. */
    public boolean showPieceTagsUnderGroupedLine() {
        return Boolean.parseBoolean(settings.getOrDefault(SHOW_PIECE_TAGS_KEY, "true"));
    }

    public void setShowPieceTagsUnderGroupedLine(boolean show) {
        settings.set(SHOW_PIECE_TAGS_KEY, Boolean.toString(show));
        auditSettingChanged(SHOW_PIECE_TAGS_KEY, Boolean.toString(show));
    }

    /** FR-DOC-04: SMTP host/port/credentials are user-configurable at runtime, not static
     *  Spring properties, so {@link com.furnitureims.service.EmailService} builds a
     *  {@code JavaMailSenderImpl} from this at send time rather than relying on
     *  autoconfiguration. */
    public SmtpSettings smtpSettings() {
        return new SmtpSettings(
                settings.getOrDefault(SMTP_HOST_KEY, ""),
                Integer.parseInt(settings.getOrDefault(SMTP_PORT_KEY, "587")),
                Boolean.parseBoolean(settings.getOrDefault(SMTP_USE_TLS_KEY, "true")),
                settings.getOrDefault(SMTP_USERNAME_KEY, ""),
                settings.getOrDefault(SMTP_APP_PASSWORD_KEY, ""),
                settings.getOrDefault(EMAIL_FROM_NAME_KEY, ""));
    }

    public void setSmtpSettings(SmtpSettings s) {
        settings.set(SMTP_HOST_KEY, s.host() == null ? "" : s.host());
        settings.set(SMTP_PORT_KEY, Integer.toString(s.port()));
        settings.set(SMTP_USE_TLS_KEY, Boolean.toString(s.useTls()));
        settings.set(SMTP_USERNAME_KEY, s.username() == null ? "" : s.username());
        settings.set(SMTP_APP_PASSWORD_KEY, s.appPassword() == null ? "" : s.appPassword());
        settings.set(EMAIL_FROM_NAME_KEY, s.fromName() == null ? "" : s.fromName());
        auditSettingChanged(SMTP_HOST_KEY, "host=" + s.host() + ", port=" + s.port() + ", useTls=" + s.useTls()
                + ", username=" + s.username() + ", appPassword=" + REDACTED);
    }

    public String emailSubjectTemplate() {
        return settings.getOrDefault(EMAIL_SUBJECT_TEMPLATE_KEY, DEFAULT_EMAIL_SUBJECT_TEMPLATE);
    }

    public void setEmailSubjectTemplate(String template) {
        settings.set(EMAIL_SUBJECT_TEMPLATE_KEY, template);
        auditSettingChanged(EMAIL_SUBJECT_TEMPLATE_KEY, template);
    }

    public String emailBodyTemplate() {
        return settings.getOrDefault(EMAIL_BODY_TEMPLATE_KEY, DEFAULT_EMAIL_BODY_TEMPLATE);
    }

    public void setEmailBodyTemplate(String template) {
        settings.set(EMAIL_BODY_TEMPLATE_KEY, template);
        auditSettingChanged(EMAIL_BODY_TEMPLATE_KEY, template);
    }

    /** FR-DOC-03: placeholders {customerName}, {invoiceNo}, {invoiceDate}, {grandTotal},
     *  {shopName} - substituted by {@link com.furnitureims.service.WhatsAppShareService}. */
    public String whatsAppMessageTemplate() {
        return settings.getOrDefault(WHATSAPP_MESSAGE_TEMPLATE_KEY, DEFAULT_WHATSAPP_MESSAGE_TEMPLATE);
    }

    public void setWhatsAppMessageTemplate(String template) {
        settings.set(WHATSAPP_MESSAGE_TEMPLATE_KEY, template);
        auditSettingChanged(WHATSAPP_MESSAGE_TEMPLATE_KEY, template);
    }

    // ---- Backup (FR-BAK-01..16) -----------------------------------------------------------

    private static final String BACKUP_PASSWORD_VERIFIER_KEY = "backup.password_verifier_hash";
    private static final String BACKUP_PASSWORD_DPAPI_KEY = "backup.password_dpapi_protected";
    private static final String BACKUP_DAILY_TIME_KEY = "backup.daily_time";
    private static final String BACKUP_WEEKLY_DAY_KEY = "backup.weekly_day";
    private static final String BACKUP_RETENTION_DAILY_KEY = "backup.retention_daily";
    private static final String BACKUP_RETENTION_WEEKLY_KEY = "backup.retention_weekly";
    private static final String BACKUP_DRIVE_FOLDER_KEY = "backup.drive_folder_name";
    private static final String GOOGLE_CLIENT_ID_KEY = "backup.google_client_id";
    private static final String GOOGLE_CLIENT_SECRET_KEY = "backup.google_client_secret";
    private static final String GOOGLE_REFRESH_TOKEN_DPAPI_KEY = "backup.google_refresh_token_dpapi_protected";
    private static final String BACKUP_PROVIDER_KEY = "backup.provider";
    private static final String ONEDRIVE_CLIENT_ID_KEY = "backup.onedrive_client_id";
    private static final String ONEDRIVE_REFRESH_TOKEN_DPAPI_KEY = "backup.onedrive_refresh_token_dpapi_protected";

    private static final String DEFAULT_DAILY_TIME = "21:30";
    private static final String DEFAULT_WEEKLY_DAY = "SUNDAY";
    private static final int DEFAULT_RETENTION_DAILY = 14;
    private static final int DEFAULT_RETENTION_WEEKLY = 12;
    private static final String DEFAULT_DRIVE_FOLDER = "FurnitureShopBackups";
    private static final String DEFAULT_BACKUP_PROVIDER = "GOOGLE_DRIVE";

    /** Sets both the bcrypt verifier used at setup/change time to confirm the owner typed
     *  the password correctly, and a Windows-DPAPI-protected copy of the password itself
     *  (see {@link WindowsDpapi}) - the second is what lets the nightly scheduled backup
     *  (FR-BAK-01) run with nobody present to type anything, without violating FR-BAK-06's
     *  "never stored in recoverable form": a DPAPI-protected value is only unprotectable by
     *  this same Windows user on this same PC, the same trust boundary FR-BAK-08 already
     *  uses for the Drive refresh token. */
    public void setBackupPassword(String password) {
        settings.set(BACKUP_PASSWORD_VERIFIER_KEY, passwordHasher.hash(password));
        settings.set(BACKUP_PASSWORD_DPAPI_KEY, WindowsDpapi.protect(password));
        auditSettingChanged(BACKUP_PASSWORD_VERIFIER_KEY, REDACTED);
    }

    public boolean hasBackupPassword() {
        return settings.get(BACKUP_PASSWORD_VERIFIER_KEY).isPresent();
    }

    public boolean verifyBackupPassword(String password) {
        return settings.get(BACKUP_PASSWORD_VERIFIER_KEY)
                .map(hash -> passwordHasher.matches(password, hash))
                .orElse(false);
    }

    /** For the unattended scheduled backup only - every interactive flow (Backup Now,
     *  restore) should have the owner type the password instead, since a restore may be
     *  against an *older* archive that used a since-changed password (screens.md: "Change
     *  backup password re-encrypts nothing retroactively"). */
    public Optional<String> currentBackupPasswordForScheduledRun() {
        return settings.get(BACKUP_PASSWORD_DPAPI_KEY).map(WindowsDpapi::unprotect);
    }

    public String backupDailyTime() {
        return settings.getOrDefault(BACKUP_DAILY_TIME_KEY, DEFAULT_DAILY_TIME);
    }

    public void setBackupDailyTime(String time) {
        settings.set(BACKUP_DAILY_TIME_KEY, time);
        auditSettingChanged(BACKUP_DAILY_TIME_KEY, time);
    }

    public String backupWeeklyDay() {
        return settings.getOrDefault(BACKUP_WEEKLY_DAY_KEY, DEFAULT_WEEKLY_DAY);
    }

    public void setBackupWeeklyDay(String day) {
        settings.set(BACKUP_WEEKLY_DAY_KEY, day);
        auditSettingChanged(BACKUP_WEEKLY_DAY_KEY, day);
    }

    public int backupRetentionDaily() {
        return Integer.parseInt(settings.getOrDefault(BACKUP_RETENTION_DAILY_KEY,
                String.valueOf(DEFAULT_RETENTION_DAILY)));
    }

    public void setBackupRetentionDaily(int count) {
        settings.set(BACKUP_RETENTION_DAILY_KEY, String.valueOf(count));
        auditSettingChanged(BACKUP_RETENTION_DAILY_KEY, String.valueOf(count));
    }

    public int backupRetentionWeekly() {
        return Integer.parseInt(settings.getOrDefault(BACKUP_RETENTION_WEEKLY_KEY,
                String.valueOf(DEFAULT_RETENTION_WEEKLY)));
    }

    public void setBackupRetentionWeekly(int count) {
        settings.set(BACKUP_RETENTION_WEEKLY_KEY, String.valueOf(count));
        auditSettingChanged(BACKUP_RETENTION_WEEKLY_KEY, String.valueOf(count));
    }

    public String backupDriveFolderName() {
        return settings.getOrDefault(BACKUP_DRIVE_FOLDER_KEY, DEFAULT_DRIVE_FOLDER);
    }

    public void setBackupDriveFolderName(String folderName) {
        settings.set(BACKUP_DRIVE_FOLDER_KEY, folderName);
        auditSettingChanged(BACKUP_DRIVE_FOLDER_KEY, folderName);
    }

    /** The SRS is explicit these come from the owner's own Google Cloud project
     *  (docs/01-requirements.md section 5) - never hardcoded, since a client secret baked
     *  into a distributed desktop app would be exposed to every installation anyway. */
    public Optional<String> googleClientId() {
        return settings.get(GOOGLE_CLIENT_ID_KEY);
    }

    public Optional<String> googleClientSecret() {
        return settings.get(GOOGLE_CLIENT_SECRET_KEY);
    }

    public void setGoogleOAuthClient(String clientId, String clientSecret) {
        settings.set(GOOGLE_CLIENT_ID_KEY, clientId);
        settings.set(GOOGLE_CLIENT_SECRET_KEY, clientSecret);
        auditSettingChanged(GOOGLE_CLIENT_ID_KEY, "clientId=" + clientId + ", clientSecret=" + REDACTED);
    }

    public boolean isGoogleDriveConnected() {
        return settings.get(GOOGLE_REFRESH_TOKEN_DPAPI_KEY).isPresent();
    }

    public Optional<String> googleRefreshToken() {
        return settings.get(GOOGLE_REFRESH_TOKEN_DPAPI_KEY).map(WindowsDpapi::unprotect);
    }

    public void setGoogleRefreshToken(String refreshToken) {
        settings.set(GOOGLE_REFRESH_TOKEN_DPAPI_KEY, WindowsDpapi.protect(refreshToken));
    }

    public void clearGoogleRefreshToken() {
        settings.delete(GOOGLE_REFRESH_TOKEN_DPAPI_KEY);
    }

    /** M12: which {@code CloudBackupProvider} is active - see {@code CloudProviders}. Defaults
     *  to Google Drive so an install that predates OneDrive support is unaffected. */
    public String backupProvider() {
        return settings.getOrDefault(BACKUP_PROVIDER_KEY, DEFAULT_BACKUP_PROVIDER);
    }

    public void setBackupProvider(String providerId) {
        settings.set(BACKUP_PROVIDER_KEY, providerId);
        auditSettingChanged(BACKUP_PROVIDER_KEY, providerId);
    }

    /** M12: unlike Google, OneDrive's client ID has a built-in default (a single Azure app
     *  registration owned by the project - see {@code OneDriveService}), since a public
     *  client needs no secret and can safely ship inside the app. {@code defaultClientId}
     *  lets a shop paste in its own registration instead, without needing a settings-UI
     *  field for the common case. */
    public String oneDriveClientId(String defaultClientId) {
        return settings.getOrDefault(ONEDRIVE_CLIENT_ID_KEY, defaultClientId);
    }

    public boolean isOneDriveConnected() {
        return settings.get(ONEDRIVE_REFRESH_TOKEN_DPAPI_KEY).isPresent();
    }

    public Optional<String> oneDriveRefreshToken() {
        return settings.get(ONEDRIVE_REFRESH_TOKEN_DPAPI_KEY).map(WindowsDpapi::unprotect);
    }

    public void setOneDriveRefreshToken(String refreshToken) {
        settings.set(ONEDRIVE_REFRESH_TOKEN_DPAPI_KEY, WindowsDpapi.protect(refreshToken));
    }

    public void clearOneDriveRefreshToken() {
        settings.delete(ONEDRIVE_REFRESH_TOKEN_DPAPI_KEY);
    }

    private static final String LICENSE_SERVER_URL_KEY = "license.server_url";

    private static final String DEFAULT_LICENSE_SERVER_URL =
            "https://furniture-ims-license-server.leanbow.workers.dev";

    /** M14: where {@code LicenseService} sends {@code /activate} and {@code /lease} requests.
     *  A settings key rather than a hardcoded constant so a self-hosted or replaced server
     *  never needs a new build, mirroring why {@link #oneDriveClientId} is overridable. */
    public String licenseServerUrl() {
        return settings.getOrDefault(LICENSE_SERVER_URL_KEY, DEFAULT_LICENSE_SERVER_URL);
    }

    public void setLicenseServerUrl(String url) {
        settings.set(LICENSE_SERVER_URL_KEY, url);
        auditSettingChanged(LICENSE_SERVER_URL_KEY, url);
    }
}
