package com.furnitureims.ui;

/**
 * The top-level areas of the application, in the order they appear in the sidebar (M10).
 * <p>
 * {@link #STOCK} is deliberately first: this is an inventory management system, and the
 * piece register is the subject the rest of the app describes. Sales, purchases and money
 * are what <em>happen to</em> stock.
 * <p>
 * {@link #NONE} covers screens that sit outside the navigation model entirely - the login
 * screen, the setup wizard, and the dashboard, which is an overview of every section rather
 * than a member of one.
 */
public enum NavSection {

    STOCK("Stock"),
    SALES("Sales"),
    BOOKINGS("Bookings"),
    PURCHASES("Purchases"),
    PAYMENTS("Payments"),
    REPORTS("Reports"),
    SETTINGS("Settings"),
    NONE("");

    private final String label;

    NavSection(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
