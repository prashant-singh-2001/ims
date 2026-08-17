package com.furnitureims;

import com.furnitureims.config.SingleInstanceGuard;
import com.furnitureims.service.SetupService;
import com.furnitureims.ui.GlobalErrorHandler;
import com.furnitureims.ui.SceneRouter;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The JavaFX entry point. {@code init()} runs on a non-FX thread before {@code start()},
 * which is where the Spring context is built - by the time {@code start()} runs and needs
 * to load FXML through Spring-managed controllers, every bean is already available.
 * <p>
 * Not the process's {@code main()} - see {@link Launcher} for why they're split.
 */
public class FurnitureImsFxApp extends Application {

    private ConfigurableApplicationContext springContext;
    private SingleInstanceGuard instanceGuard;
    private Stage primaryStage;

    @Override
    public void init() {
        springContext = new SpringApplicationBuilder(FurnitureImsApplication.class)
                .headless(false)
                .run(getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        // NFR-11: binds directly to the FX Application Thread - every screen's event
        // handlers run here, and this is what catches whatever a local try/catch missed.
        GlobalErrorHandler.installOnCurrentThread();

        instanceGuard = springContext.getBean(SingleInstanceGuard.class);
        boolean acquiredLock = instanceGuard.acquire(() -> Platform.runLater(this::bringToFront));
        if (!acquiredLock) {
            // Another instance is already running and has just been signalled to come to
            // front (NFR-07) - this process has no database connection to open, so it exits.
            Platform.exit();
            return;
        }

        SceneRouter sceneRouter = springContext.getBean(SceneRouter.class);
        sceneRouter.attachTo(stage);

        SetupService setupService = springContext.getBean(SetupService.class);
        sceneRouter.show(setupService.isSetupComplete()
                ? "/fxml/login/login.fxml"
                : "/fxml/setup/setup-wizard.fxml");

        stage.setTitle("Furniture Shop Inventory Management");
        stage.setOnCloseRequest(event -> Platform.exit());
        stage.show();
    }

    private void bringToFront() {
        if (primaryStage != null) {
            primaryStage.setIconified(false);
            primaryStage.toFront();
            primaryStage.requestFocus();
        }
    }

    @Override
    public void stop() {
        if (instanceGuard != null) {
            instanceGuard.close();
        }
        if (springContext != null) {
            springContext.close();
        }
    }
}
