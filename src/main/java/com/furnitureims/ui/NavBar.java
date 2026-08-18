package com.furnitureims.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The application shell's persistent sidebar (M10). Deliberately built in plain Java, with
 * no FXML and no {@code initialize()} - this is the fix for a real hazard the rest of the
 * screens live with safely only by convention: every controller here is a Spring singleton
 * whose {@code @FXML} fields get rebound if its FXML is ever loaded a second time, silently
 * orphaning whichever node last held the binding (see {@code SceneRouter}'s lock-overlay
 * note for the one place that already had to reason carefully about this). A sidebar built
 * once in Java and never reloaded makes that failure mode impossible rather than merely
 * avoided.
 * <p>
 * One button per {@link NavSection}, each navigating to that section's own landing screen -
 * not an expandable sub-menu, since the screens within a section already link to their
 * siblings (the item-model list links to the piece register and to categories/locations,
 * for instance), so a second, parallel navigation tree would only duplicate that. Stock is
 * first: this is an inventory management system, and the piece register is the subject the
 * rest of the app describes.
 */
@Component
public class NavBar {

    private static final Map<NavSection, Route> LANDING = new EnumMap<>(NavSection.class);

    static {
        LANDING.put(NavSection.STOCK, Route.PIECE_REGISTER);
        LANDING.put(NavSection.SALES, Route.NEW_SALE);
        LANDING.put(NavSection.PURCHASES, Route.PURCHASE_BILL_LIST);
        LANDING.put(NavSection.PAYMENTS, Route.PAYMENT_LIST);
        LANDING.put(NavSection.REPORTS, Route.STOCK_REPORT);
        LANDING.put(NavSection.SETTINGS, Route.SETTINGS);
    }

    private final SceneRouter sceneRouter;

    private VBox view;
    private Button homeButton;
    private final Map<NavSection, Button> sectionButtons = new LinkedHashMap<>();

    public NavBar(SceneRouter sceneRouter) {
        this.sceneRouter = sceneRouter;
    }

    /** Builds the sidebar node on first call and returns the same instance forever after -
     *  never call this more than once per process; {@code SceneRouter.attachTo} is the only
     *  caller. */
    public Node view() {
        if (view == null) {
            view = build();
        }
        return view;
    }

    private VBox build() {
        VBox box = new VBox(4);
        box.setPadding(new Insets(12, 8, 12, 8));
        box.setPrefWidth(200);
        box.getStyleClass().add("nav-bar");

        homeButton = navButton("Dashboard", Route.DASHBOARD);
        box.getChildren().add(homeButton);
        box.getChildren().add(separator());

        for (NavSection section : NavSection.values()) {
            if (section == NavSection.NONE) {
                continue;
            }
            Button button = navButton(section.label(), LANDING.get(section));
            sectionButtons.put(section, button);
            box.getChildren().add(button);
        }

        return box;
    }

    /** The section's landing screen - the same route its sidebar button navigates to.
     *  Exposed so {@code SceneRouter} can build the Ctrl+1..6 keyboard shortcuts (NFR-14)
     *  from this exact map instead of a second, easily-drifting copy of it. */
    public Route landingFor(NavSection section) {
        return LANDING.get(section);
    }

    private Button navButton(String label, Route target) {
        Button button = new Button(label);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox.setVgrow(button, Priority.NEVER);
        // NFR-14: the billing screen must be operable entirely from the keyboard. Tabbing
        // out of a form must not land in the always-visible sidebar - Ctrl+1..6 (wired in
        // SceneRouter) is the keyboard route into navigation instead.
        button.setFocusTraversable(false);
        button.setOnAction(e -> sceneRouter.navigate(target));
        return button;
    }

    private static Separator separator() {
        Separator separator = new Separator();
        separator.setPadding(new Insets(4, 0, 4, 0));
        return separator;
    }

    /** Highlights whichever nav item corresponds to {@code route} - the Home button for
     *  {@link Route#DASHBOARD} specifically, or the button for {@code route.section()}
     *  otherwise. A piece detail screen (section STOCK) keeps Stock lit while drilling in,
     *  which falls out for free from every route already carrying the right section. */
    public void setActive(Route route) {
        homeButton.getStyleClass().remove("nav-button-active");
        sectionButtons.values().forEach(b -> b.getStyleClass().remove("nav-button-active"));

        if (route == Route.DASHBOARD) {
            homeButton.getStyleClass().add("nav-button-active");
            return;
        }
        Button active = sectionButtons.get(route.section());
        if (active != null) {
            active.getStyleClass().add("nav-button-active");
        }
    }
}
