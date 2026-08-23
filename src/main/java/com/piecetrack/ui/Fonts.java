package com.piecetrack.ui;

import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads the app's bundled display typeface ("Jost*", SIL OFL 1.1 - see
 * {@code /fonts/OFL.txt}) into the JavaFX font registry so app.css can reference it by
 * family name. Safe to call more than once - {@link Font#loadFont} re-registering an
 * already-known face is a no-op, not a duplicate - so both {@code PieceTrackFxApp} (before
 * building the Scene) and {@code FxmlLoadSmokeTest} (so the smoke test renders against the
 * real font rather than silently falling back to the platform default) can call it freely.
 * <p>
 * Only Regular and Bold are bundled. Every screen in this app expresses hierarchy with
 * those two weights (see app.css's type scale) - a third weight would be dead resource
 * weight with no place using it. Both faces share the family name "Jost*" (the font's own
 * internal name, asterisk included), so {@code -fx-font-weight: bold} in CSS correctly
 * selects the bundled Bold face rather than a synthetically faux-bolded Regular one.
 */
public final class Fonts {

    private Fonts() {
    }

    public static void loadAll() {
        load("/fonts/Jost-Regular.ttf");
        load("/fonts/Jost-Bold.ttf");
    }

    private static void load(String resourcePath) {
        try (InputStream in = Fonts.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Font resource not found on classpath: " + resourcePath);
            }
            // The size argument only matters for the Font instance loadFont() returns
            // (unused here - CSS resizes everything); it does not affect registration.
            if (Font.loadFont(in, 12) == null) {
                throw new IllegalStateException("JavaFX could not parse font resource: " + resourcePath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
