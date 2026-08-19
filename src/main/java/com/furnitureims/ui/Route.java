package com.furnitureims.ui;

/**
 * Every screen in the application, replacing the ~60 hardcoded FXML path string literals
 * that were scattered across 29 controllers before M10 (15 of them the single path
 * {@code "/fxml/shell/dashboard.fxml"}, duplicated in 13 different files).
 * <p>
 * Each route carries what {@link SceneRouter#navigate(Route)} needs to show it correctly:
 * which {@link NavSection} to highlight in the sidebar, whether it gets shell {@link Chrome}
 * at all, a static title (or {@code null} when the screen supplies its own - see
 * {@code HasScreenTitle}), and its {@link #parent()} for the shell's back chevron.
 * <p>
 * {@code parent} is stored as the target enum constant's name rather than a direct
 * reference, because enum constants cannot forward-reference one another during
 * initialization; {@link #parent()} resolves it lazily via {@link Route#valueOf}, which is
 * safe once the class has finished loading.
 * <p>
 * Not every screen has a meaningful parent. List/report/dashboard/settings screens are
 * reachable directly from the sidebar and have none. Detail and sub-screens return to
 * whichever screen their own "Back" button already targeted before M10 - preserved exactly,
 * including one rough edge worth knowing about: {@link #CUSTOMER_RECEIPT},
 * {@link #SUPPLIER_PAYMENT} and {@link #BACKUP_SETTINGS} all return to {@link #DASHBOARD}
 * rather than their own section's list screen (payment-list, payment-list, settings), which
 * predates this enum and is simply carried forward rather than silently "fixed" as part of
 * what is otherwise a pure refactor.
 */
public enum Route {

    // ---- No chrome: the two gates before the app is usable at all ---------------------
    LOGIN("/fxml/login/login.fxml", NavSection.NONE, Chrome.NONE, null, null),
    SETUP_WIZARD("/fxml/setup/setup-wizard.fxml", NavSection.NONE, Chrome.NONE, null, null),

    // ---- Shell: overview -----------------------------------------------------------
    DASHBOARD("/fxml/shell/dashboard.fxml", NavSection.NONE, Chrome.SHELL, "Dashboard", null),

    // ---- Shell: Stock (STOCK is first - this is an inventory system) ------------------
    ITEM_MODEL_LIST("/fxml/catalogue/item-model-list.fxml", NavSection.STOCK, Chrome.SHELL,
            "Item Models", null),
    ITEM_MODEL_EDITOR("/fxml/catalogue/item-model-editor.fxml", NavSection.STOCK, Chrome.SHELL,
            null, "ITEM_MODEL_LIST"),
    PIECE_REGISTER("/fxml/catalogue/piece-register.fxml", NavSection.STOCK, Chrome.SHELL,
            "Piece Register", null),
    PIECE_DETAIL("/fxml/catalogue/piece-detail.fxml", NavSection.STOCK, Chrome.SHELL,
            "Piece Detail", "PIECE_REGISTER"),
    OPENING_STOCK_ENTRY("/fxml/catalogue/opening-stock-entry.fxml", NavSection.STOCK, Chrome.SHELL,
            "Opening Stock Entry", "PIECE_REGISTER"),
    CATEGORIES_LOCATIONS("/fxml/catalogue/categories-locations.fxml", NavSection.STOCK, Chrome.SHELL,
            "Categories & Locations", "DASHBOARD"),

    // ---- Shell: Sales -------------------------------------------------------------
    NEW_SALE("/fxml/sales/new-sale.fxml", NavSection.SALES, Chrome.SHELL, "New Sale", "INVOICE_LIST"),
    INVOICE_LIST("/fxml/sales/invoice-list.fxml", NavSection.SALES, Chrome.SHELL, "Invoices", null),
    INVOICE_DETAIL("/fxml/sales/invoice-detail.fxml", NavSection.SALES, Chrome.SHELL,
            null, "INVOICE_LIST"),
    SALES_RETURN("/fxml/sales/sales-return.fxml", NavSection.SALES, Chrome.SHELL,
            null, "INVOICE_LIST"),

    // ---- Shell: Bookings (M13) -------------------------------------------------------
    BOOKING_LIST("/fxml/booking/booking-list.fxml", NavSection.BOOKINGS, Chrome.SHELL, "Bookings", null),
    BOOKING_DETAIL("/fxml/booking/booking-detail.fxml", NavSection.BOOKINGS, Chrome.SHELL,
            null, "BOOKING_LIST"),

    // ---- Shell: Purchases ----------------------------------------------------------
    SUPPLIER_LIST("/fxml/purchase/supplier-list.fxml", NavSection.PURCHASES, Chrome.SHELL,
            "Suppliers", null),
    SUPPLIER_EDITOR("/fxml/purchase/supplier-editor.fxml", NavSection.PURCHASES, Chrome.SHELL,
            null, "SUPPLIER_LIST"),
    PURCHASE_BILL_LIST("/fxml/purchase/purchase-bill-list.fxml", NavSection.PURCHASES, Chrome.SHELL,
            "Purchase Bills", null),
    PURCHASE_BILL_ENTRY("/fxml/purchase/purchase-bill-entry.fxml", NavSection.PURCHASES, Chrome.SHELL,
            null, "PURCHASE_BILL_LIST"),
    PURCHASE_RETURN("/fxml/purchase/purchase-return.fxml", NavSection.PURCHASES, Chrome.SHELL,
            null, "PURCHASE_BILL_LIST"),

    // ---- Shell: Payments ------------------------------------------------------------
    PAYMENT_LIST("/fxml/payment/payment-list.fxml", NavSection.PAYMENTS, Chrome.SHELL,
            "Payments", null),
    CUSTOMER_RECEIPT("/fxml/payment/customer-receipt.fxml", NavSection.PAYMENTS, Chrome.SHELL,
            "Customer Receipt", "DASHBOARD"),
    SUPPLIER_PAYMENT("/fxml/payment/supplier-payment.fxml", NavSection.PAYMENTS, Chrome.SHELL,
            "Supplier Payment", "DASHBOARD"),

    // ---- Shell: Reports ------------------------------------------------------------
    STOCK_REPORT("/fxml/reports/stock-report.fxml", NavSection.REPORTS, Chrome.SHELL,
            "Stock Report", null),
    SALES_PROFIT_REPORT("/fxml/reports/sales-profit-report.fxml", NavSection.REPORTS, Chrome.SHELL,
            "Sales and Profit Report", null),
    DUES_REPORT("/fxml/reports/dues-report.fxml", NavSection.REPORTS, Chrome.SHELL,
            "Dues and Aging Report", null),

    // ---- Shell: Settings ------------------------------------------------------------
    SETTINGS("/fxml/settings/settings.fxml", NavSection.SETTINGS, Chrome.SHELL, "Settings", null),
    AUDIT_LOG("/fxml/settings/audit-log.fxml", NavSection.SETTINGS, Chrome.SHELL,
            "Audit Log", "SETTINGS"),
    BACKUP_SETTINGS("/fxml/backup/backup-settings.fxml", NavSection.SETTINGS, Chrome.SHELL,
            "Backup and Restore", "DASHBOARD");

    private final String fxmlPath;
    private final NavSection section;
    private final Chrome chrome;
    private final String title;
    private final String parentName;

    Route(String fxmlPath, NavSection section, Chrome chrome, String title, String parentName) {
        this.fxmlPath = fxmlPath;
        this.section = section;
        this.chrome = chrome;
        this.title = title;
        this.parentName = parentName;
    }

    public String fxmlPath() {
        return fxmlPath;
    }

    public NavSection section() {
        return section;
    }

    public Chrome chrome() {
        return chrome;
    }

    /** Static title for this route, or {@code null} when the controller supplies its own
     *  (an edit/detail screen whose title depends on which record was opened). */
    public String title() {
        return title;
    }

    /** The screen the shell's back chevron returns to, or {@code null} if this route is
     *  reachable directly from the sidebar and needs no way back. */
    public Route parent() {
        return parentName == null ? null : Route.valueOf(parentName);
    }

    /** Resolves a classpath FXML path back to the route that serves it, for code that only
     *  has the path (screens not yet migrated to {@code navigate(Route)}, and the classpath
     *  drift test). */
    public static Route byPath(String fxmlPath) {
        for (Route route : values()) {
            if (route.fxmlPath.equals(fxmlPath)) {
                return route;
            }
        }
        throw new IllegalArgumentException("No Route registered for " + fxmlPath);
    }
}
