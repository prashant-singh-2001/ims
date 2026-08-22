package com.piecetrack.service;

import com.piecetrack.money.Money;
import com.piecetrack.repository.ShopProfileRepository;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * FR-DOC-03: opens WhatsApp (desktop app or Web) addressed to a customer with a pre-filled
 * message, and simultaneously opens the PDF's containing folder so it can be attached by
 * hand - a free {@code wa.me} link cannot attach a file itself, a known limitation the SRS
 * accepts (fully automatic delivery would need the paid WhatsApp Business API).
 */
@Service
public class WhatsAppShareService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private final SettingsService settingsService;
    private final ShopProfileRepository shopProfileRepository;

    public WhatsAppShareService(SettingsService settingsService, ShopProfileRepository shopProfileRepository) {
        this.settingsService = settingsService;
        this.shopProfileRepository = shopProfileRepository;
    }

    /** Opens wa.me addressed to {@code phone} with the configured message template filled
     *  in (placeholders: customerName, invoiceNo, invoiceDate, grandTotal, shopName), then
     *  opens {@code pdfFile}'s containing folder so the owner can attach it in WhatsApp. */
    public void share(String customerName, String phone, String docNo, LocalDate docDate, Money amount,
                       Path pdfFile) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("This customer has no phone number to share to.");
        }
        String shopName = shopProfileRepository.find().map(p -> p.shopName()).orElse("");
        String message = settingsService.whatsAppMessageTemplate()
                .replace("{customerName}", customerName == null ? "" : customerName)
                .replace("{invoiceNo}", docNo)
                .replace("{invoiceDate}", docDate.format(DATE_FMT))
                .replace("{grandTotal}", amount.toDisplayString())
                .replace("{shopName}", shopName);

        String waPhone = toWhatsAppNumber(phone);
        String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
        try {
            openInBrowser("https://wa.me/" + waPhone + "?text=" + encoded);
            if (pdfFile != null && pdfFile.getParent() != null) {
                Desktop.getDesktop().open(pdfFile.getParent().toFile());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open WhatsApp or the invoice folder.", e);
        }
    }

    private static void openInBrowser(String url) throws IOException {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Built an invalid WhatsApp link.", e);
        }
    }

    /** Indian phone numbers are stored without a country code (FR-SAL-02) - wa.me needs the
     *  full international number with no leading '+' or '00'. */
    private static String toWhatsAppNumber(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() == 10 ? "91" + digits : digits;
    }
}
