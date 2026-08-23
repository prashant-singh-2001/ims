package com.piecetrack.ui;

import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

/**
 * Geometric marks (Bauhaus: square, circle, triangle, diamond) used in place of an icon
 * font - this app has none, and a hand-drawn primitive is more in keeping with the
 * aesthetic than importing a library for ten small marks. Every mark is a real
 * {@link Shape}, not a unicode glyph, so it scales crisply under any point size and takes
 * its colour from CSS the same way every other visual property in this app does: this
 * class only ever decides geometry (square vs circle vs triangle vs diamond, filled vs
 * outline), never colour - see app.css's "Marks" section for the actual fills/strokes.
 */
public final class Marks {

    private static final double SIZE = 10;

    private Marks() {
    }

    /** One mark per {@link NavSection}: filled for the first four sections, outlined for
     *  the rest, so seven sidebar buttons read as distinct shapes rather than "four
     *  squares and three circles" at a glance. Attach with {@code Button.setGraphic(...)},
     *  never by prefixing the button's text - {@code SceneRouterShellTest} matches nav
     *  buttons by their exact label ("Stock", "Sales", ...), and {@code setGraphic} leaves
     *  {@code getText()} untouched. */
    public static Shape forSection(NavSection section) {
        Shape shape = switch (section) {
            case STOCK, PAYMENTS -> square();
            case SALES, REPORTS -> circle();
            case BOOKINGS, SETTINGS -> triangle();
            case PURCHASES, NONE -> diamond();
        };
        shape.getStyleClass().add("nav-mark");
        if (section == NavSection.PAYMENTS || section == NavSection.REPORTS || section == NavSection.SETTINGS) {
            shape.getStyleClass().add("nav-mark-outline");
        }
        return shape;
    }

    /** Healthy / confirmed-good state (e.g. a fresh backup) - a filled circle. */
    public static Shape ok() {
        return statusMark(circle(), "status-mark-ok");
    }

    /** Needs attention soon, not yet urgent - a filled triangle. */
    public static Shape warning() {
        return statusMark(triangle(), "status-mark-warn");
    }

    /** Made but not yet confirmed - a filled circle, same shape as {@link #ok()} since
     *  both are affirmative/non-problem states, distinguished only by colour (orange vs
     *  blue). */
    public static Shape pending() {
        return statusMark(circle(), "status-mark-pending");
    }

    /** A genuine problem - a filled square. */
    public static Shape danger() {
        return statusMark(square(), "status-mark-danger");
    }

    private static Shape statusMark(Shape shape, String modifier) {
        shape.getStyleClass().addAll("status-mark", modifier);
        return shape;
    }

    private static Rectangle square() {
        return new Rectangle(SIZE, SIZE);
    }

    private static Circle circle() {
        return new Circle(SIZE / 2);
    }

    private static Polygon triangle() {
        return new Polygon(SIZE / 2, 0, SIZE, SIZE, 0, SIZE);
    }

    private static Polygon diamond() {
        return new Polygon(SIZE / 2, 0, SIZE, SIZE / 2, SIZE / 2, SIZE, 0, SIZE / 2);
    }
}
