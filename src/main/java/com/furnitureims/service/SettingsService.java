package com.furnitureims.service;

import com.furnitureims.repository.AppSettingRepository;
import org.springframework.stereotype.Service;

/** Typed accessors over the generic app_setting key/value store (FR-SYS-02). */
@Service
public class SettingsService {

    private static final String IDLE_LOCK_MINUTES_KEY = "security.idle_lock_minutes";
    private static final int DEFAULT_IDLE_LOCK_MINUTES = 10;

    private final AppSettingRepository settings;

    public SettingsService(AppSettingRepository settings) {
        this.settings = settings;
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
    }

    public String emailSubjectTemplate() {
        return settings.getOrDefault(EMAIL_SUBJECT_TEMPLATE_KEY, DEFAULT_EMAIL_SUBJECT_TEMPLATE);
    }

    public void setEmailSubjectTemplate(String template) {
        settings.set(EMAIL_SUBJECT_TEMPLATE_KEY, template);
    }

    public String emailBodyTemplate() {
        return settings.getOrDefault(EMAIL_BODY_TEMPLATE_KEY, DEFAULT_EMAIL_BODY_TEMPLATE);
    }

    public void setEmailBodyTemplate(String template) {
        settings.set(EMAIL_BODY_TEMPLATE_KEY, template);
    }

    /** FR-DOC-03: placeholders {customerName}, {invoiceNo}, {invoiceDate}, {grandTotal},
     *  {shopName} - substituted by {@link com.furnitureims.service.WhatsAppShareService}. */
    public String whatsAppMessageTemplate() {
        return settings.getOrDefault(WHATSAPP_MESSAGE_TEMPLATE_KEY, DEFAULT_WHATSAPP_MESSAGE_TEMPLATE);
    }

    public void setWhatsAppMessageTemplate(String template) {
        settings.set(WHATSAPP_MESSAGE_TEMPLATE_KEY, template);
    }
}
