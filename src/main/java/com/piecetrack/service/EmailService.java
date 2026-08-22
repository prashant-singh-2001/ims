package com.piecetrack.service;

import com.piecetrack.money.Money;
import com.piecetrack.repository.ShopProfileRepository;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * FR-DOC-04: sends a generated document as an email attachment through the owner's own
 * SMTP account. Host/port/credentials are configured at runtime on the settings screen,
 * not as static Spring properties, so a {@link JavaMailSenderImpl} is built fresh from
 * {@link SettingsService} at send time - this app never relies on Spring Boot's
 * static-properties mail autoconfiguration.
 */
@Service
public class EmailService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private final SettingsService settingsService;
    private final ShopProfileRepository shopProfileRepository;

    public EmailService(SettingsService settingsService, ShopProfileRepository shopProfileRepository) {
        this.settingsService = settingsService;
        this.shopProfileRepository = shopProfileRepository;
    }

    /** @throws IllegalStateException with a message safe to show the owner directly -
     *  FR-DOC-04 requires success/failure to be reported clearly, never as a raw stack
     *  trace. Placeholders in the subject/body templates: customerName, invoiceNo,
     *  invoiceDate, grandTotal, shopName. */
    public void sendDocument(String toAddress, String customerName, String docNo, LocalDate docDate, Money amount,
                              Path pdfFile) {
        SettingsService.SmtpSettings smtp = requireConfiguredSmtp();
        String shopName = shopProfileRepository.find().map(p -> p.shopName()).orElse("");
        String subject = fillTemplate(settingsService.emailSubjectTemplate(), customerName, docNo, docDate, amount,
                shopName);
        String body = fillTemplate(settingsService.emailBodyTemplate(), customerName, docNo, docDate, amount,
                shopName);
        send(smtp, toAddress, subject, body, pdfFile);
    }

    /** For the settings screen's "Send test email" button - no template substitution, no
     *  attachment, just proof the SMTP settings actually work end to end. */
    public void sendTestEmail(String toAddress) {
        SettingsService.SmtpSettings smtp = requireConfiguredSmtp();
        send(smtp, toAddress, "Test email from PieceTrack",
                "This is a test email confirming your SMTP settings are working correctly.", null);
    }

    private SettingsService.SmtpSettings requireConfiguredSmtp() {
        SettingsService.SmtpSettings smtp = settingsService.smtpSettings();
        if (smtp.host() == null || smtp.host().isBlank()) {
            throw new IllegalStateException("SMTP is not configured yet - set it up under Settings > Email.");
        }
        return smtp;
    }

    private static void send(SettingsService.SmtpSettings smtp, String toAddress, String subject, String body,
                              Path attachment) {
        if (toAddress == null || toAddress.isBlank()) {
            throw new IllegalArgumentException("This customer has no email address on file.");
        }
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(smtp.host());
        mailSender.setPort(smtp.port());
        mailSender.setUsername(smtp.username());
        mailSender.setPassword(smtp.appPassword());
        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", Boolean.toString(smtp.useTls()));

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, attachment != null);
            helper.setTo(toAddress);
            helper.setFrom(smtp.username(), smtp.fromName() == null || smtp.fromName().isBlank()
                    ? smtp.username() : smtp.fromName());
            helper.setSubject(subject);
            helper.setText(body);
            if (attachment != null) {
                helper.addAttachment(attachment.getFileName().toString(), new File(attachment.toString()));
            }
            mailSender.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send email: " + rootCauseMessage(e), e);
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : t.getMessage();
    }

    private static String fillTemplate(String template, String customerName, String docNo, LocalDate docDate,
                                        Money amount, String shopName) {
        return template
                .replace("{customerName}", customerName == null ? "" : customerName)
                .replace("{invoiceNo}", docNo)
                .replace("{invoiceDate}", docDate.format(DATE_FMT))
                .replace("{grandTotal}", amount.toDisplayString())
                .replace("{shopName}", shopName);
    }
}
