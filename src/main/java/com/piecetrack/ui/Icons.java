package com.piecetrack.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Pictographic sidebar and status icons (soft-surface restyle) - stroke-based, drawn on a
 * 24x24 native coordinate space and scaled down to a consistent on-screen size, replacing
 * the abstract geometric shapes this app used before (square/circle/triangle/diamond gave
 * every button in a section only one of four silhouettes; a real pictogram per section is
 * both more recognisable and no longer colour-only for status, since each status keeps a
 * distinct shape too). Every icon is a real {@link SVGPath}, not a unicode glyph or an icon
 * font, so it scales crisply and takes its colour from CSS the same way every other visual
 * property in this app does - this class only ever decides which icon and its native
 * geometry, never colour; see app.css's "Icons" section for the actual stroke colour.
 * <p>
 * Each icon is wrapped in a {@link Group}: a bare {@code Shape}'s {@code layoutBounds} stay
 * at their unscaled native size even after {@code setScaleX/Y}, which would leave a button's
 * internal layout reserving 24x24 units of space for an icon rendering at 18x18. A
 * {@code Group}'s {@code layoutBounds} are computed from its children's already-transformed
 * bounds, so wrapping fixes that mismatch without hand-authoring geometry at display size.
 */
public final class Icons {

    private static final double NATIVE_SIZE = 24;
    private static final double DISPLAY_SIZE = 18;
    private static final double SCALE = DISPLAY_SIZE / NATIVE_SIZE;

    private Icons() {
    }

    /** One pictogram per {@link NavSection} - attach with {@code Button.setGraphic(...)},
     *  never by prefixing the button's text: {@code SceneRouterShellTest} matches nav
     *  buttons by their exact label ("Stock", "Sales", ...), and {@code setGraphic} leaves
     *  {@code getText()} untouched. */
    public static Node forSection(NavSection section) {
        String path = switch (section) {
            case NONE -> LAYOUT_GRID;
            case STOCK -> PACKAGE;
            case SALES -> RECEIPT;
            case BOOKINGS -> CALENDAR;
            case PURCHASES -> TRUCK;
            case PAYMENTS -> WALLET;
            case REPORTS -> BAR_CHART;
            case SETTINGS -> SETTINGS_GEAR;
        };
        return icon(path, "nav-icon");
    }

    /** Healthy / confirmed-good state (e.g. a fresh backup) - a filled check in a circle. */
    public static Node ok() {
        return icon(CHECK_CIRCLE, "status-icon", "status-icon-ok");
    }

    /** Needs attention soon, not yet urgent. */
    public static Node warning() {
        return icon(ALERT_TRIANGLE, "status-icon", "status-icon-warn");
    }

    /** Made but not yet confirmed. */
    public static Node pending() {
        return icon(CLOCK, "status-icon", "status-icon-pending");
    }

    /** A genuine problem. */
    public static Node danger() {
        return icon(ALERT_CIRCLE, "status-icon", "status-icon-danger");
    }

    private static Node icon(String pathData, String... styleClasses) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.getStyleClass().addAll(styleClasses);
        path.setScaleX(SCALE);
        path.setScaleY(SCALE);
        return new Group(path);
    }

    // 24x24 native coordinate space, stroke-only paths (fill stays transparent - see
    // app.css). Circles are drawn as two semicircle arcs rather than JavaFX's own Circle
    // shape, since SVGPath is the one node type CSS can style identically to every other
    // icon here with a single stroke rule.
    private static final String LAYOUT_GRID =
            "M3,3 H11 V11 H3 Z M13,3 H21 V11 H13 Z M3,13 H11 V21 H3 Z M13,13 H21 V21 H13 Z";
    private static final String PACKAGE =
            "M3,7 L12,3 L21,7 L21,17 L12,21 L3,17 Z M3,7 L12,11 L21,7 M12,11 L12,21";
    private static final String RECEIPT =
            "M5,3 H19 V19 L17,21 L15,19 L13,21 L11,19 L9,21 L7,19 L5,21 Z "
                    + "M8,8 H16 M8,12 H16 M8,16 H13";
    private static final String CALENDAR =
            "M4,5 H20 V20 H4 Z M8,3 V7 M16,3 V7 M4,10 H20";
    private static final String TRUCK =
            "M2,7 H14 V17 H2 Z M14,10 H18 L21,13 V17 H14 Z "
                    + "M8.8,19 A1.8,1.8 0 1,1 5.2,19 A1.8,1.8 0 1,1 8.8,19 "
                    + "M19.3,19 A1.8,1.8 0 1,1 15.7,19 A1.8,1.8 0 1,1 19.3,19";
    private static final String WALLET =
            "M3,7 H19 V19 H3 Z M17,11 H21 V15 H17 Z";
    private static final String BAR_CHART =
            "M3,21 H21 M7,17 V11 M12,17 V7 M17,17 V13";
    private static final String SETTINGS_GEAR =
            "M16,12 A4,4 0 1,1 8,12 A4,4 0 1,1 16,12 "
                    + "M12,5 V2 M12,19 V22 M19,12 H22 M2,12 H5 "
                    + "M17,7 L19,5 M7,7 L5,5 M17,17 L19,19 M7,17 L5,19";
    private static final String CHECK_CIRCLE =
            "M21,12 A9,9 0 1,1 3,12 A9,9 0 1,1 21,12 M8,12 L11,15 L16,9";
    private static final String ALERT_TRIANGLE =
            "M12,3 L22,20 H2 Z M12,9 V14 "
                    + "M12.6,17 A0.6,0.6 0 1,1 11.4,17 A0.6,0.6 0 1,1 12.6,17";
    private static final String ALERT_CIRCLE =
            "M21,12 A9,9 0 1,1 3,12 A9,9 0 1,1 21,12 M12,7 V13 "
                    + "M12.6,16.5 A0.6,0.6 0 1,1 11.4,16.5 A0.6,0.6 0 1,1 12.6,16.5";
    private static final String CLOCK =
            "M21,12 A9,9 0 1,1 3,12 A9,9 0 1,1 21,12 M12,7 V12 L16,14";
}
