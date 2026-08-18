package com.furnitureims.service;

import com.furnitureims.config.AppPaths;
import com.furnitureims.domain.Customer;
import com.furnitureims.domain.IndianState;
import com.furnitureims.domain.PurchaseBill;
import com.furnitureims.domain.PurchaseReturn;
import com.furnitureims.domain.PurchaseReturnLine;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.SalesLine;
import com.furnitureims.domain.SalesReturn;
import com.furnitureims.domain.SalesReturnLine;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.PurchaseReturnLineRepository;
import com.furnitureims.repository.PurchaseReturnRepository;
import com.furnitureims.repository.SalesInvoiceRepository;
import com.furnitureims.repository.SalesLineRepository;
import com.furnitureims.repository.SalesReturnLineRepository;
import com.furnitureims.repository.SalesReturnRepository;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.util.AmountInWords;
import com.furnitureims.util.FinancialYear;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the three printable documents FR-DOC-01/05 require - tax invoice, credit note,
 * debit note - as PDFs under {@link AppPaths#invoices()}/&lt;FY&gt;/, and records the path
 * on the owning row so it can be reopened without regenerating (FR-DOC-02). Deliberately
 * never called from inside {@code SalesInvoiceService.createInvoice} or the return
 * services' transactional methods - docs/03-screens.md 6.1 shows PDF generation as a
 * distinct step ("Then: PDF generated -&gt; ...") after the sale itself has already
 * committed, and file I/O has no business living inside a database transaction anyway.
 */
@Service
public class DocumentService {

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font HEADER_FONT = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font NORMAL_FONT = new Font(Font.HELVETICA, 9);
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 7);
    private static final Font BOLD_FONT = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private final SalesInvoiceService salesInvoiceService;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesLineRepository salesLineRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final SalesReturnLineRepository salesReturnLineRepository;
    private final PurchaseReturnRepository purchaseReturnRepository;
    private final PurchaseReturnLineRepository purchaseReturnLineRepository;
    private final PurchaseBillService purchaseBillService;
    private final SupplierService supplierService;
    private final CustomerService customerService;
    private final ItemModelService itemModelService;
    private final PieceService pieceService;
    private final PaymentService paymentService;
    private final ShopProfileRepository shopProfileRepository;
    private final SettingsService settingsService;
    private final AppPaths appPaths;

    public DocumentService(SalesInvoiceService salesInvoiceService, SalesInvoiceRepository salesInvoiceRepository,
                            SalesLineRepository salesLineRepository, SalesReturnRepository salesReturnRepository,
                            SalesReturnLineRepository salesReturnLineRepository,
                            PurchaseReturnRepository purchaseReturnRepository,
                            PurchaseReturnLineRepository purchaseReturnLineRepository,
                            PurchaseBillService purchaseBillService, SupplierService supplierService,
                            CustomerService customerService, ItemModelService itemModelService,
                            PieceService pieceService, PaymentService paymentService,
                            ShopProfileRepository shopProfileRepository, SettingsService settingsService,
                            AppPaths appPaths) {
        this.salesInvoiceService = salesInvoiceService;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesLineRepository = salesLineRepository;
        this.salesReturnRepository = salesReturnRepository;
        this.salesReturnLineRepository = salesReturnLineRepository;
        this.purchaseReturnRepository = purchaseReturnRepository;
        this.purchaseReturnLineRepository = purchaseReturnLineRepository;
        this.purchaseBillService = purchaseBillService;
        this.supplierService = supplierService;
        this.customerService = customerService;
        this.itemModelService = itemModelService;
        this.pieceService = pieceService;
        this.paymentService = paymentService;
        this.shopProfileRepository = shopProfileRepository;
        this.settingsService = settingsService;
        this.appPaths = appPaths;
    }

    /** Resolves a DB-stored relative pdf_path (relative to {@link AppPaths#root()}) to an
     *  absolute file path, for the UI to open/attach. */
    public Path resolve(String storedRelativePath) {
        return appPaths.root().resolve(storedRelativePath);
    }

    // ---- Tax invoice (FR-DOC-01/02/06) -------------------------------------------------

    /** Generates (or regenerates - FR-DOC-02) the tax invoice PDF and records its path. */
    public Path generateInvoicePdf(long invoiceId) {
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
        Customer customer = customerService.findById(invoice.customerId())
                .orElseThrow(() -> new IllegalStateException("Customer not found."));
        ShopProfile shop = shopProfile();
        List<SalesLine> lines = salesInvoiceService.linesFor(invoiceId);

        Path target = appPaths.invoices().resolve(invoice.financialYear())
                .resolve(sanitizeForFilename(invoice.invoiceNo()) + ".pdf");
        createParentDirs(target);

        Money balance = paymentService.invoiceBalance(invoiceId);
        Money paid = invoice.grandTotal().minus(balance);

        // M10: driven by whether THIS invoice actually carries tax, not the live GST
        // toggle - an invoice billed while GST was on must keep printing exactly as it did
        // the day it was issued, even if the shop later turns GST off (same reasoning as
        // InvoiceDetailController - regenerating an old PDF must never rewrite history).
        boolean hadTax = invoice.cgstAmount().isPositive() || invoice.sgstAmount().isPositive()
                || invoice.igstAmount().isPositive();

        try (FileOutputStream out = new FileOutputStream(target.toFile())) {
            Document document = new Document(PageSize.A4, 30, 30, 30, 30);
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(titleParagraph(!hadTax ? "INVOICE"
                    : shop.registrationType() == ShopProfile.RegistrationType.COMPOSITION
                            ? "BILL OF SUPPLY" : "TAX INVOICE"));
            document.add(shopAndDocHeader(shop, "Invoice No", invoice.invoiceNo(), invoice.invoiceDate(),
                    hadTax ? "Place of Supply: " + placeOfSupplyText(invoice.placeOfSupplyStateCode()) : null,
                    hadTax));
            document.add(customerBlock("Bill To", customer.name(), customer.addressLine1(), customer.addressLine2(),
                    customer.city(), customer.pincode(), customer.stateName(), customer.gstin(), hadTax));
            if (hadTax) {
                document.add(reverseChargeLine());
            }

            List<GroupedSalesLine> grouped = groupSalesLines(lines);
            document.add(lineItemsTable(grouped, hadTax));
            if (hadTax) {
                document.add(taxSummaryTable(taxByHsnRate(lines), invoice.interstate()));
            }
            document.add(invoiceTotalsTable(invoice.taxableValue(), invoice.cgstAmount(), invoice.sgstAmount(),
                    invoice.igstAmount(), invoice.roundOff(), invoice.grandTotal(), paid, balance, hadTax));
            document.add(amountInWordsParagraph(invoice.grandTotal()));
            document.add(declarationAndSignature(shop));

            document.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write invoice PDF: " + target, e);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to build invoice PDF for " + invoice.invoiceNo(), e);
        }

        salesInvoiceRepository.updatePdfPath(invoiceId, relativeToRoot(target));
        return target;
    }

    // ---- Credit note (FR-DOC-05) --------------------------------------------------------

    public Path generateCreditNotePdf(long salesReturnId) {
        SalesReturn salesReturn = salesReturnRepository.findById(salesReturnId)
                .orElseThrow(() -> new IllegalArgumentException("Sales return not found: " + salesReturnId));
        SalesInvoice invoice = salesInvoiceService.findById(salesReturn.salesInvoiceId())
                .orElseThrow(() -> new IllegalStateException("Invoice not found."));
        Customer customer = customerService.findById(invoice.customerId())
                .orElseThrow(() -> new IllegalStateException("Customer not found."));
        ShopProfile shop = shopProfile();
        List<SalesReturnLine> lines = salesReturnLineRepository.findBySalesReturnId(salesReturnId);

        String fy = FinancialYear.of(salesReturn.returnDate());
        Path target = appPaths.invoices().resolve(fy).resolve(sanitizeForFilename(salesReturn.creditNoteNo()) + ".pdf");
        createParentDirs(target);

        // M10: see the identical note in generateInvoicePdf - driven by this specific
        // return's own tax, not the live toggle.
        boolean hadTax = salesReturn.cgstAmount().isPositive() || salesReturn.sgstAmount().isPositive()
                || salesReturn.igstAmount().isPositive();

        try (FileOutputStream out = new FileOutputStream(target.toFile())) {
            Document document = new Document(PageSize.A4, 30, 30, 30, 30);
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(titleParagraph("CREDIT NOTE"));
            document.add(shopAndDocHeader(shop, "Credit Note No", salesReturn.creditNoteNo(), salesReturn.returnDate(),
                    "Against Invoice: " + invoice.invoiceNo() + " dated " + invoice.invoiceDate().format(DATE_FMT),
                    hadTax));
            document.add(customerBlock("Issued To", customer.name(), customer.addressLine1(), customer.addressLine2(),
                    customer.city(), customer.pincode(), customer.stateName(), customer.gstin(), hadTax));
            document.add(reasonParagraph(salesReturn.reason()));

            document.add(salesReturnLineItemsTable(lines, hadTax));
            document.add(noteTotalsTable(salesReturn.taxableValue(), salesReturn.cgstAmount(),
                    salesReturn.sgstAmount(), salesReturn.igstAmount(), salesReturn.totalAmount(), hadTax));
            document.add(amountInWordsParagraph(salesReturn.totalAmount()));
            document.add(declarationAndSignature(shop));

            document.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write credit note PDF: " + target, e);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to build credit note PDF for " + salesReturn.creditNoteNo(), e);
        }

        salesReturnRepository.updatePdfPath(salesReturnId, relativeToRoot(target));
        return target;
    }

    // ---- Debit note (FR-DOC-05) ----------------------------------------------------------

    public Path generateDebitNotePdf(long purchaseReturnId) {
        PurchaseReturn purchaseReturn = purchaseReturnRepository.findById(purchaseReturnId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase return not found: " + purchaseReturnId));
        PurchaseBill bill = purchaseBillService.findById(purchaseReturn.purchaseBillId())
                .orElseThrow(() -> new IllegalStateException("Purchase bill not found."));
        Supplier supplier = supplierService.findById(bill.supplierId())
                .orElseThrow(() -> new IllegalStateException("Supplier not found."));
        ShopProfile shop = shopProfile();
        List<PurchaseReturnLine> lines = purchaseReturnLineRepository.findByPurchaseReturnId(purchaseReturnId);

        String fy = FinancialYear.of(purchaseReturn.returnDate());
        Path target = appPaths.invoices().resolve(fy)
                .resolve(sanitizeForFilename(purchaseReturn.debitNoteNo()) + ".pdf");
        createParentDirs(target);

        // M10: see the identical note in generateInvoicePdf - driven by this specific
        // return's own tax, not the live toggle.
        boolean hadTax = purchaseReturn.cgstAmount().isPositive() || purchaseReturn.sgstAmount().isPositive()
                || purchaseReturn.igstAmount().isPositive();

        try (FileOutputStream out = new FileOutputStream(target.toFile())) {
            Document document = new Document(PageSize.A4, 30, 30, 30, 30);
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(titleParagraph("DEBIT NOTE"));
            document.add(shopAndDocHeader(shop, "Debit Note No", purchaseReturn.debitNoteNo(),
                    purchaseReturn.returnDate(),
                    "Against Bill: " + bill.supplierBillNo() + " dated " + bill.billDate().format(DATE_FMT),
                    hadTax));
            document.add(customerBlock("Issued To", supplier.name(), supplier.addressLine1(), supplier.addressLine2(),
                    supplier.city(), supplier.pincode(), supplier.stateName(), supplier.gstin(), hadTax));
            document.add(reasonParagraph(purchaseReturn.reason()));

            document.add(purchaseReturnLineItemsTable(lines, hadTax));
            document.add(noteTotalsTable(purchaseReturn.taxableValue(), purchaseReturn.cgstAmount(),
                    purchaseReturn.sgstAmount(), purchaseReturn.igstAmount(), purchaseReturn.totalAmount(), hadTax));
            document.add(amountInWordsParagraph(purchaseReturn.totalAmount()));
            document.add(declarationAndSignature(shop));

            document.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write debit note PDF: " + target, e);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to build debit note PDF for " + purchaseReturn.debitNoteNo(), e);
        }

        purchaseReturnRepository.updatePdfPath(purchaseReturnId, relativeToRoot(target));
        return target;
    }

    // ---- Shared building blocks -----------------------------------------------------------

    private ShopProfile shopProfile() {
        return shopProfileRepository.find()
                .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."));
    }

    private static Paragraph titleParagraph(String text) {
        Paragraph p = new Paragraph(text, TITLE_FONT);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingAfter(10);
        return p;
    }

    private PdfPTable shopAndDocHeader(ShopProfile shop, String docNoLabel, String docNo, LocalDate docDate,
                                        String thirdLine, boolean showGst) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[]{3, 2});
        } catch (DocumentException ignored) {
            // Column count already matches - cannot actually throw here.
        }
        table.setSpacingAfter(8);

        PdfPCell shopCell = borderless();
        if (shop.logoPath() != null && !shop.logoPath().isBlank()) {
            Path logoFile = appPaths.photos().resolve(shop.logoPath());
            if (Files.exists(logoFile)) {
                try {
                    Image logo = Image.getInstance(logoFile.toString());
                    logo.scaleToFit(70, 70);
                    shopCell.addElement(logo);
                } catch (Exception ignored) {
                    // A corrupt/unreadable logo file must never block document generation.
                }
            }
        }
        shopCell.addElement(new Paragraph(nullToDash(shop.shopName()), HEADER_FONT));
        String address = joinNonBlank(", ", shop.addressLine1(), shop.addressLine2(), shop.city(), shop.pincode());
        if (!address.isBlank()) {
            shopCell.addElement(new Paragraph(address, NORMAL_FONT));
        }
        if (showGst) {
            shopCell.addElement(new Paragraph("GSTIN: " + nullToDash(shop.gstin()), NORMAL_FONT));
        }
        shopCell.addElement(new Paragraph("State: " + nullToDash(shop.stateName())
                + (shop.stateCode() == null ? "" : " (" + shop.stateCode() + ")"), NORMAL_FONT));
        table.addCell(shopCell);

        PdfPCell docCell = borderless();
        docCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        docCell.addElement(rightAligned(docNoLabel + ": " + docNo, BOLD_FONT));
        docCell.addElement(rightAligned("Date: " + docDate.format(DATE_FMT), NORMAL_FONT));
        if (thirdLine != null) {
            docCell.addElement(rightAligned(thirdLine, NORMAL_FONT));
        }
        table.addCell(docCell);

        return table;
    }

    private static Paragraph rightAligned(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static String placeOfSupplyText(String stateCode) {
        try {
            return IndianState.byGstCode(stateCode).displayName() + " (" + stateCode + ")";
        } catch (IllegalArgumentException e) {
            return stateCode;
        }
    }

    private static PdfPTable customerBlock(String label, String name, String addr1, String addr2, String city,
                                            String pincode, String stateName, String gstin, boolean showGst) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        PdfPCell cell = new PdfPCell();
        cell.setPadding(6);
        cell.addElement(new Paragraph(label + ":", HEADER_FONT));
        cell.addElement(new Paragraph(nullToDash(name), BOLD_FONT));
        String address = joinNonBlank(", ", addr1, addr2, city, pincode);
        if (!address.isBlank()) {
            cell.addElement(new Paragraph(address, NORMAL_FONT));
        }
        if (stateName != null && !stateName.isBlank()) {
            cell.addElement(new Paragraph("State: " + stateName, NORMAL_FONT));
        }
        if (showGst) {
            cell.addElement(new Paragraph("GSTIN: " + (gstin == null || gstin.isBlank() ? "Unregistered" : gstin),
                    NORMAL_FONT));
        }
        table.addCell(cell);
        return table;
    }

    private static Paragraph reverseChargeLine() {
        Paragraph p = new Paragraph("Reverse charge applicable: No", NORMAL_FONT);
        p.setSpacingAfter(6);
        return p;
    }

    private static Paragraph reasonParagraph(String reason) {
        Paragraph p = new Paragraph("Reason: " + reason, NORMAL_FONT);
        p.setSpacingAfter(6);
        return p;
    }

    // ---- Invoice line grouping (FR-DOC-06) -----------------------------------------------

    private record GroupKey(long itemModelId, long unitPricePaisa, long discountPaisa, BigDecimal gstRate) {
    }

    private record GroupedSalesLine(String description, String hsn, int quantity, Money unitPrice, Money discount,
                                     BigDecimal gstRate, Money taxableValue, Money tax, Money lineTotal,
                                     List<String> pieceTags) {
    }

    private List<GroupedSalesLine> groupSalesLines(List<SalesLine> lines) {
        Map<GroupKey, List<SalesLine>> byKey = new LinkedHashMap<>();
        for (SalesLine line : lines) {
            GroupKey key = new GroupKey(line.itemModelId(), line.unitPrice().paisa(),
                    line.discountAmount().paisa(), line.gstRate());
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }
        List<GroupedSalesLine> result = new ArrayList<>();
        for (List<SalesLine> group : byKey.values()) {
            SalesLine first = group.get(0);
            Money taxable = Money.ZERO;
            Money tax = Money.ZERO;
            Money total = Money.ZERO;
            List<String> tags = new ArrayList<>();
            for (SalesLine l : group) {
                taxable = taxable.plus(l.taxableValue());
                tax = tax.plus(l.cgstAmount()).plus(l.sgstAmount()).plus(l.igstAmount());
                total = total.plus(l.lineTotal());
                pieceService.findById(l.pieceId()).ifPresent(p -> tags.add(p.tag()));
            }
            result.add(new GroupedSalesLine(first.descriptionSnapshot(), first.hsnSnapshot(), group.size(),
                    first.unitPrice(), first.discountAmount(), first.gstRate(), taxable, tax, total, tags));
        }
        return result;
    }

    /** @param showGst when false, drops HSN/GST%/Tax entirely - and Taxable Value too, since
     *                 with zero tax it is identical to Total and would just be a redundant
     *                 second copy of the same figure (M10). */
    private PdfPTable lineItemsTable(List<GroupedSalesLine> grouped, boolean showGst) {
        String[] headers = showGst
                ? new String[]{"#", "Description", "HSN", "Qty", "Unit", "Rate", "Discount", "Taxable Value", "GST %",
                        "Tax", "Total"}
                : new String[]{"#", "Description", "Qty", "Unit", "Rate", "Discount", "Total"};
        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);
        try {
            table.setWidths(showGst
                    ? new float[]{0.4f, 2.2f, 0.8f, 0.5f, 0.6f, 0.9f, 0.8f, 1.0f, 0.6f, 0.9f, 1.0f}
                    : new float[]{0.4f, 3.0f, 0.6f, 0.7f, 1.0f, 1.0f, 1.2f});
        } catch (DocumentException ignored) {
        }
        for (String h : headers) {
            table.addCell(headerCell(h));
        }

        boolean showTags = settingsService.showPieceTagsUnderGroupedLine();
        int i = 1;
        for (GroupedSalesLine g : grouped) {
            table.addCell(bodyCell(String.valueOf(i++), Element.ALIGN_CENTER));
            table.addCell(bodyCell(g.description(), Element.ALIGN_LEFT));
            if (showGst) {
                table.addCell(bodyCell(g.hsn(), Element.ALIGN_CENTER));
            }
            table.addCell(bodyCell(String.valueOf(g.quantity()), Element.ALIGN_CENTER));
            table.addCell(bodyCell("Nos", Element.ALIGN_CENTER));
            table.addCell(bodyCell(g.unitPrice().toDisplayString(), Element.ALIGN_RIGHT));
            table.addCell(bodyCell(g.discount().isZero() ? "-" : g.discount().toDisplayString(), Element.ALIGN_RIGHT));
            if (showGst) {
                table.addCell(bodyCell(g.taxableValue().toDisplayString(), Element.ALIGN_RIGHT));
                table.addCell(bodyCell(stripZeros(g.gstRate()) + "%", Element.ALIGN_CENTER));
                table.addCell(bodyCell(g.tax().toDisplayString(), Element.ALIGN_RIGHT));
            }
            table.addCell(bodyCell(g.lineTotal().toDisplayString(), Element.ALIGN_RIGHT));

            if (showTags && g.quantity() > 1 && !g.pieceTags().isEmpty()) {
                PdfPCell tagCell = new PdfPCell(new Phrase("Tags: " + String.join(", ", g.pieceTags()), SMALL_FONT));
                tagCell.setColspan(headers.length);
                tagCell.setPadding(2);
                table.addCell(tagCell);
            }
        }
        return table;
    }

    // ---- Return line items (one row per physical piece - no grouping needed) -------------

    private PdfPTable salesReturnLineItemsTable(List<SalesReturnLine> lines, boolean showGst) {
        PdfPTable table = returnTableSkeleton(showGst);
        for (SalesReturnLine line : lines) {
            String tag = pieceService.findById(line.pieceId()).map(p -> p.tag()).orElse("?");
            String description = salesLineRepository.findByPieceId(line.pieceId())
                    .map(SalesLine::descriptionSnapshot).orElse("?");
            String hsn = salesLineRepository.findByPieceId(line.pieceId())
                    .map(SalesLine::hsnSnapshot).orElse("");
            Money tax = line.cgstAmount().plus(line.sgstAmount()).plus(line.igstAmount());
            addReturnRow(table, tag, description, hsn, line.taxableValue(), tax, line.lineTotal(), showGst);
        }
        return table;
    }

    private PdfPTable purchaseReturnLineItemsTable(List<PurchaseReturnLine> lines, boolean showGst) {
        PdfPTable table = returnTableSkeleton(showGst);
        for (PurchaseReturnLine line : lines) {
            String tag = pieceService.findById(line.pieceId()).map(p -> p.tag()).orElse("?");
            String description = pieceService.findById(line.pieceId())
                    .flatMap(p -> itemModelService.findById(p.itemModelId()))
                    .map(m -> m.modelName()).orElse("?");
            String hsn = pieceService.findById(line.pieceId())
                    .flatMap(p -> itemModelService.findById(p.itemModelId()))
                    .map(m -> m.hsnCode()).orElse("");
            Money tax = line.cgstAmount().plus(line.sgstAmount()).plus(line.igstAmount());
            addReturnRow(table, tag, description, hsn, line.taxableValue(), tax, line.lineTotal(), showGst);
        }
        return table;
    }

    /** @param showGst when false, drops HSN/Taxable Value/Tax - Taxable Value is redundant
     *                 with Total once tax is zero (M10, same reasoning as lineItemsTable). */
    private static PdfPTable returnTableSkeleton(boolean showGst) {
        String[] headers = showGst
                ? new String[]{"Piece Tag", "Description", "HSN", "Taxable Value", "Tax", "Total"}
                : new String[]{"Piece Tag", "Description", "Total"};
        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);
        for (String h : headers) {
            table.addCell(headerCell(h));
        }
        return table;
    }

    private static void addReturnRow(PdfPTable table, String tag, String description, String hsn, Money taxable,
                                      Money tax, Money total, boolean showGst) {
        table.addCell(bodyCell(tag, Element.ALIGN_CENTER));
        table.addCell(bodyCell(description, Element.ALIGN_LEFT));
        if (showGst) {
            table.addCell(bodyCell(hsn, Element.ALIGN_CENTER));
            table.addCell(bodyCell(taxable.toDisplayString(), Element.ALIGN_RIGHT));
            table.addCell(bodyCell(tax.toDisplayString(), Element.ALIGN_RIGHT));
        }
        table.addCell(bodyCell(total.toDisplayString(), Element.ALIGN_RIGHT));
    }

    // ---- Tax summary, grouped by HSN and rate (FR-DOC-01) --------------------------------

    private record TaxGroupKey(String hsn, BigDecimal gstRate) {
    }

    private record TaxGroupRow(String hsn, BigDecimal gstRate, Money taxable, Money cgst, Money sgst, Money igst) {
    }

    private List<TaxGroupRow> taxByHsnRate(List<SalesLine> lines) {
        Map<TaxGroupKey, Money[]> byKey = new LinkedHashMap<>();
        for (SalesLine l : lines) {
            TaxGroupKey key = new TaxGroupKey(l.hsnSnapshot(), l.gstRate());
            Money[] agg = byKey.computeIfAbsent(key, k -> new Money[]{Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO});
            agg[0] = agg[0].plus(l.taxableValue());
            agg[1] = agg[1].plus(l.cgstAmount());
            agg[2] = agg[2].plus(l.sgstAmount());
            agg[3] = agg[3].plus(l.igstAmount());
        }
        List<TaxGroupRow> rows = new ArrayList<>();
        for (Map.Entry<TaxGroupKey, Money[]> e : byKey.entrySet()) {
            Money[] a = e.getValue();
            rows.add(new TaxGroupRow(e.getKey().hsn(), e.getKey().gstRate(), a[0], a[1], a[2], a[3]));
        }
        return rows;
    }

    private static PdfPTable taxSummaryTable(List<TaxGroupRow> rows, boolean interstate) {
        PdfPTable table = new PdfPTable(interstate ? 4 : 5);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);
        String[] headers = interstate
                ? new String[]{"HSN", "Taxable Value", "IGST Rate", "IGST Amount"}
                : new String[]{"HSN", "Taxable Value", "CGST", "SGST", "Total Tax"};
        for (String h : headers) {
            table.addCell(headerCell(h));
        }
        for (TaxGroupRow r : rows) {
            table.addCell(bodyCell(r.hsn(), Element.ALIGN_CENTER));
            table.addCell(bodyCell(r.taxable().toDisplayString(), Element.ALIGN_RIGHT));
            if (interstate) {
                table.addCell(bodyCell(stripZeros(r.gstRate()) + "%", Element.ALIGN_CENTER));
                table.addCell(bodyCell(r.igst().toDisplayString(), Element.ALIGN_RIGHT));
            } else {
                table.addCell(bodyCell(r.cgst().toDisplayString(), Element.ALIGN_RIGHT));
                table.addCell(bodyCell(r.sgst().toDisplayString(), Element.ALIGN_RIGHT));
                table.addCell(bodyCell(r.cgst().plus(r.sgst()).toDisplayString(), Element.ALIGN_RIGHT));
            }
        }
        return table;
    }

    // ---- Totals ---------------------------------------------------------------------------

    private static PdfPTable invoiceTotalsTable(Money taxable, Money cgst, Money sgst, Money igst, Money roundOff,
                                                 Money grandTotal, Money paid, Money balance, boolean showGst) {
        PdfPTable table = totalsSkeleton();
        addTotalRow(table, showGst ? "Taxable Value" : "Subtotal", taxable, false);
        if (cgst.isPositive() || sgst.isPositive()) {
            addTotalRow(table, "CGST", cgst, false);
            addTotalRow(table, "SGST", sgst, false);
        }
        if (igst.isPositive()) {
            addTotalRow(table, "IGST", igst, false);
        }
        addTotalRow(table, "Round Off", roundOff, false);
        addTotalRow(table, "Grand Total", grandTotal, true);
        addTotalRow(table, "Amount Paid", paid, false);
        addTotalRow(table, "Balance Due", balance, true);
        return table;
    }

    private static PdfPTable noteTotalsTable(Money taxable, Money cgst, Money sgst, Money igst, Money total,
                                              boolean showGst) {
        PdfPTable table = totalsSkeleton();
        addTotalRow(table, showGst ? "Taxable Value" : "Subtotal", taxable, false);
        if (cgst.isPositive() || sgst.isPositive()) {
            addTotalRow(table, "CGST", cgst, false);
            addTotalRow(table, "SGST", sgst, false);
        }
        if (igst.isPositive()) {
            addTotalRow(table, "IGST", igst, false);
        }
        addTotalRow(table, "Total Amount", total, true);
        return table;
    }

    private static PdfPTable totalsSkeleton() {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(55);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingAfter(8);
        return table;
    }

    private static void addTotalRow(PdfPTable table, String label, Money amount, boolean bold) {
        Font font = bold ? BOLD_FONT : NORMAL_FONT;
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPadding(3);
        PdfPCell amountCell = new PdfPCell(new Phrase(amount.toDisplayString(), font));
        amountCell.setBorder(Rectangle.NO_BORDER);
        amountCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        amountCell.setPadding(3);
        table.addCell(labelCell);
        table.addCell(amountCell);
    }

    private static Paragraph amountInWordsParagraph(Money amount) {
        Paragraph p = new Paragraph("Amount in Words: " + AmountInWords.toWords(amount), BOLD_FONT);
        p.setSpacingAfter(10);
        return p;
    }

    private static PdfPTable declarationAndSignature(ShopProfile shop) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[]{3, 2});
        } catch (DocumentException ignored) {
        }
        table.setSpacingBefore(16);

        PdfPCell declCell = borderless();
        String declaration = (shop.invoiceDeclaration() == null || shop.invoiceDeclaration().isBlank())
                ? "We declare that this document shows the actual price of the goods/services described "
                        + "and that all particulars are true and correct."
                : shop.invoiceDeclaration();
        declCell.addElement(new Paragraph("Declaration:", HEADER_FONT));
        declCell.addElement(new Paragraph(declaration, SMALL_FONT));
        table.addCell(declCell);

        PdfPCell sigCell = borderless();
        sigCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        sigCell.addElement(rightAligned("For " + nullToDash(shop.shopName()), NORMAL_FONT));
        sigCell.addElement(new Paragraph(" "));
        sigCell.addElement(new Paragraph(" "));
        String signature = (shop.signatureText() == null || shop.signatureText().isBlank())
                ? "Authorized Signatory" : shop.signatureText();
        sigCell.addElement(rightAligned(signature, NORMAL_FONT));
        table.addCell(sigCell);

        return table;
    }

    // ---- Small shared helpers -------------------------------------------------------------

    private static PdfPCell borderless() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(4);
        return cell;
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(4);
        cell.setBackgroundColor(new Color(230, 230, 230));
        return cell;
    }

    private static PdfPCell bodyCell(String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, NORMAL_FONT));
        cell.setHorizontalAlignment(align);
        cell.setPadding(3);
        return cell;
    }

    private static String stripZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String joinNonBlank(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(separator);
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static String sanitizeForFilename(String docNo) {
        return docNo.replace("/", "-");
    }

    private void createParentDirs(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create folder for " + target, e);
        }
    }

    private String relativeToRoot(Path target) {
        return appPaths.root().relativize(target).toString();
    }
}
