package com.furnitureims.ui.shell;

import com.furnitureims.domain.AppUser;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.AppSession;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.ui.login.IdleLockManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import org.springframework.stereotype.Component;

/**
 * Stands in for the real dashboard (FR-RPT-05), which is milestone M7 per
 * docs/04-roadmap.md - stock value, sales totals and backup status don't exist yet because
 * nothing that produces them has been built. This screen exists so login has somewhere to
 * land and so the idle-lock and manual-lock behaviour (FR-AUTH-04/05) has a real screen to
 * be tested against, rather than being demoed against a rectangle.
 */
@Component
public class DashboardPlaceholderController {

    private final AppSession appSession;
    private final ShopProfileRepository shopProfileRepository;
    private final IdleLockManager idleLockManager;
    private final SceneRouter sceneRouter;

    @FXML private Label welcomeLabel;

    public DashboardPlaceholderController(AppSession appSession,
                                           ShopProfileRepository shopProfileRepository,
                                           IdleLockManager idleLockManager,
                                           SceneRouter sceneRouter) {
        this.appSession = appSession;
        this.shopProfileRepository = shopProfileRepository;
        this.idleLockManager = idleLockManager;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        AppUser user = appSession.currentUser();
        String shopName = shopProfileRepository.find().map(profile -> profile.shopName()).orElse("your shop");
        String username = user == null ? "" : user.username();
        welcomeLabel.setText("Welcome back, " + username + " - " + shopName);
    }

    @FXML
    private void onLockNowClicked() {
        idleLockManager.lockNow();
    }

    @FXML
    private void onItemModelsClicked() {
        sceneRouter.show("/fxml/catalogue/item-model-list.fxml");
    }

    @FXML
    private void onPieceRegisterClicked() {
        sceneRouter.show("/fxml/catalogue/piece-register.fxml");
    }

    @FXML
    private void onCategoriesLocationsClicked() {
        sceneRouter.show("/fxml/catalogue/categories-locations.fxml");
    }
}
