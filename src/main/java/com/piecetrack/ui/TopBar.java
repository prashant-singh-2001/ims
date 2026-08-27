package com.piecetrack.ui;

import com.piecetrack.domain.LicenseState;
import com.piecetrack.service.LicenseService;
import com.piecetrack.ui.login.IdleLockManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.springframework.stereotype.Component;

/**
 * The application shell's persistent top bar (M10): the current screen's title, a back
 * chevron to its {@link Route#parent()} when it has one, and the one action that used to
 * live on the dashboard's own header and now belongs everywhere - Lock Now (FR-AUTH-05).
 * Built in plain Java for the same reason as {@link NavBar} - see its class Javadoc.
 * <p>
 * {@link #titleProperty()} is a plain writable property rather than something this class
 * decides on its own: {@code SceneRouter.navigate} is what knows whether the just-loaded
 * controller implements {@link HasScreenTitle} and should be bound instead of set - see the
 * note there on why the property is always unbound before either.
 */
@Component
public class TopBar {

    private final SceneRouter sceneRouter;
    private final IdleLockManager idleLockManager;
    private final LicenseService licenseService;

    private HBox view;
    private Button backButton;
    private Label titleLabel;
    private Label licenseWarningLabel;
    private final StringProperty title = new SimpleStringProperty("");

    private Route backTarget;

    public TopBar(SceneRouter sceneRouter, IdleLockManager idleLockManager, LicenseService licenseService) {
        this.sceneRouter = sceneRouter;
        this.idleLockManager = idleLockManager;
        this.licenseService = licenseService;
    }

    /** Builds the top bar node on first call and returns the same instance forever after -
     *  never call this more than once per process; {@code SceneRouter.attachTo} is the only
     *  caller. */
    public Node view() {
        if (view == null) {
            view = build();
        }
        return view;
    }

    private HBox build() {
        backButton = new Button("← Back");
        backButton.getStyleClass().add("top-bar-back-button");
        backButton.setFocusTraversable(false);
        backButton.setVisible(false);
        backButton.setManaged(false);
        backButton.setOnAction(e -> {
            if (backTarget != null) {
                sceneRouter.navigate(backTarget);
            }
        });

        titleLabel = new Label();
        titleLabel.getStyleClass().add("top-bar-title");
        titleLabel.textProperty().bind(title);

        licenseWarningLabel = new Label();
        licenseWarningLabel.setGraphicTextGap(8);
        licenseWarningLabel.setVisible(false);
        licenseWarningLabel.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button lockNowButton = new Button("Lock Now");
        lockNowButton.setFocusTraversable(false);
        lockNowButton.setOnAction(e -> idleLockManager.lockNow());

        HBox box = new HBox(12, backButton, titleLabel, spacer, licenseWarningLabel, lockNowButton);
        box.getStyleClass().add("top-bar");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(12, 16, 12, 16));
        return box;
    }

    public StringProperty titleProperty() {
        return title;
    }

    /** @param parent the route the back chevron returns to, or {@code null} to hide it - a
     *                screen reachable directly from the sidebar needs no way back. */
    public void setBackTarget(Route parent) {
        this.backTarget = parent;
        backButton.setVisible(parent != null);
        backButton.setManaged(parent != null);
    }

    /**
     * Re-reads {@link LicenseService#state()} and updates the persistent banner (M14).
     * Called by {@link SceneRouter} after every shell navigation - cheap (no network, see
     * {@code LicenseService#state()}'s own Javadoc), so re-checking on each screen change is
     * simpler than wiring a separate polling timer and catches a state change (e.g. a
     * background renewal completing) within one navigation of it happening.
     */
    public void refreshLicenseBanner() {
        LicenseState state = licenseService.state();
        String text = switch (state) {
            case GRACE -> "Licence renewal needed soon - see Settings > Licence";
            case WIND_DOWN -> "Read-only mode - see Settings > Licence";
            case ACTIVE, UNLICENSED -> null;
        };
        licenseWarningLabel.getStyleClass().removeAll("text-warning", "text-danger");
        if (text != null) {
            boolean windDown = state == LicenseState.WIND_DOWN;
            licenseWarningLabel.getStyleClass().add(windDown ? "text-danger" : "text-warning");
            licenseWarningLabel.setGraphic(windDown ? Icons.danger() : Icons.warning());
        } else {
            licenseWarningLabel.setGraphic(null);
        }
        licenseWarningLabel.setText(text == null ? "" : text);
        licenseWarningLabel.setVisible(text != null);
        licenseWarningLabel.setManaged(text != null);
    }
}
