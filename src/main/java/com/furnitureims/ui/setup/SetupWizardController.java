package com.furnitureims.ui.setup;

import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 * Shell for the four-step first-run wizard (FR-AUTH-01; docs/03-screens.md section 2.1).
 * Owns step navigation only; each step owns its own fields, validation and persistence
 * via {@link WizardStep}. On "Next", the current step is validated, then (only if valid)
 * committed, before the shell advances - see {@link WizardStep} for why those are split.
 */
@Component
public class SetupWizardController {

    private record LoadedStep(Parent view, WizardStep controller) {
    }

    private static final String[] STEP_FXML = {
            "/fxml/setup/step1-shop-profile.fxml",
            "/fxml/setup/step2-owner-login.fxml",
            "/fxml/setup/step3-backup-password.fxml",
            "/fxml/setup/step4-google-drive.fxml"
    };

    private final ApplicationContext applicationContext;
    private final SceneRouter sceneRouter;

    @FXML private StackPane contentPane;
    @FXML private Label stepIndicatorLabel;
    @FXML private Label errorLabel;
    @FXML private Button backButton;
    @FXML private Button nextButton;

    private List<LoadedStep> steps;
    private int currentIndex = 0;

    public SetupWizardController(ApplicationContext applicationContext, SceneRouter sceneRouter) {
        this.applicationContext = applicationContext;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        steps = Arrays.stream(STEP_FXML).map(this::loadStep).toList();
        showStep(0);
    }

    private LoadedStep loadStep(String classpathFxml) {
        URL location = getClass().getResource(classpathFxml);
        if (location == null) {
            throw new IllegalStateException("FXML not found on classpath: " + classpathFxml);
        }
        FXMLLoader loader = new FXMLLoader(location);
        loader.setControllerFactory(applicationContext::getBean);
        try {
            Parent view = loader.load();
            return new LoadedStep(view, loader.getController());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load wizard step: " + classpathFxml, e);
        }
    }

    private void showStep(int index) {
        currentIndex = index;
        contentPane.getChildren().setAll(steps.get(index).view());
        stepIndicatorLabel.setText("Step " + (index + 1) + " of " + steps.size());
        errorLabel.setText("");
        backButton.setDisable(index == 0);
        nextButton.setText(index == steps.size() - 1 ? "Finish" : "Next");
    }

    @FXML
    private void onBackClicked() {
        if (currentIndex > 0) {
            showStep(currentIndex - 1);
        }
    }

    @FXML
    private void onNextClicked() {
        WizardStep current = steps.get(currentIndex).controller();
        String error = current.validate();
        if (error != null) {
            errorLabel.setText(error);
            return;
        }
        current.commit();

        if (currentIndex == steps.size() - 1) {
            sceneRouter.navigate(Route.LOGIN);
        } else {
            showStep(currentIndex + 1);
        }
    }
}
