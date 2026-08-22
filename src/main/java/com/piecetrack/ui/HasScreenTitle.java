package com.piecetrack.ui;

import javafx.beans.property.ReadOnlyStringProperty;

/**
 * Implemented by controllers whose title depends on which record they were opened for - an
 * item model editor showing "Edit: Oak Dining Chair", an invoice detail showing "Invoice
 * INV/25-26/0042" - and so cannot be expressed as {@link Route#title()}'s single static
 * string (M10).
 * <p>
 * {@link SceneRouter#navigate(Route)} checks for this after loading the controller: if
 * present, the shell top bar binds its title label to {@link #screenTitleProperty()} and
 * unbinds it on the next navigation; if absent, the top bar falls back to
 * {@link Route#title()}.
 */
public interface HasScreenTitle {

    ReadOnlyStringProperty screenTitleProperty();
}
