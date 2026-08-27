package com.piecetrack;

import atlantafx.base.theme.PrimerLight;
import com.piecetrack.config.SingleInstanceGuard;
import com.piecetrack.service.SetupService;
import com.piecetrack.ui.Fonts;
import com.piecetrack.ui.GlobalErrorHandler;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
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
public class PieceTrackFxApp extends Application {

    private ConfigurableApplicationContext springContext;
    private SingleInstanceGuard instanceGuard;
    private Stage primaryStage;

    @Override
    public void init() {
        springContext = new SpringApplicationBuilder(PieceTrackApplication.class)
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

        applyBaseTheme();

        SceneRouter sceneRouter = springContext.getBean(SceneRouter.class);
        sceneRouter.attachTo(stage);

        SetupService setupService = springContext.getBean(SetupService.class);
        sceneRouter.navigate(setupService.isSetupComplete() ? Route.LOGIN : Route.SETUP_WIZARD);

        stage.setTitle("PieceTrack");
        // A minimum size floor so the layouts can't be shrunk until content clips - before
        // M10 there was none. Note these include the window decorations, so the usable
        // client area is a little smaller.
        stage.setMinWidth(1100);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(event -> Platform.exit());
        // setMaximized must be called AFTER show(): called before, the native peer doesn't
        // exist yet and Windows silently drops the request, leaving the stage sized to its
        // content's preferred size instead (confirmed against a packaged jpackage app-image,
        // not just mvn javafx:run - the two launch paths behave differently here). Calling it
        // immediately after show() does produce a brief visible jump from restore-down size
        // to full screen, but a working maximized window beats a broken unmaximized one.
        stage.show();
        stage.setMaximized(true);
    }

    /**
     * Replaces JavaFX's stock "Modena" user-agent stylesheet with AtlantaFX's Primer Light
     * (M10). Deliberately called here, from {@code start()}, rather than anywhere else:
     * <ul>
     *   <li>Not from {@code init()} - that runs on the JavaFX-Launcher thread, and this call
     *       mutates the global {@code StyleManager} and re-applies CSS across every Scene.</li>
     *   <li>Not after {@code stage.show()} - it would work, but the owner would see a visible
     *       Modena-to-Primer flash on a slow shop PC.</li>
     *   <li>Not from {@code Launcher.main()} - the toolkit isn't up yet.</li>
     * </ul>
     * Being an {@code Application}-level stylesheet rather than a Scene one is also what
     * makes it reach the {@code Alert}/{@code Dialog} instances created in Java, which build
     * their own Scene on show and so never picked up our scene-level {@code app.css}.
     * <p>
     * The theme CSS lives inside the atlantafx jar, which in the packaged fat jar resolves
     * to a Spring Boot {@code nested:} URL - a path {@code mvn javafx:run} never exercises.
     * If that ever fails to load, the fallback is to copy the stylesheet out to a file under
     * {@code AppPaths} at startup and hand this method a {@code file:} URL instead.
     */
    private void applyBaseTheme() {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        // The bundled display face (Bauhaus restyle) - registered here, before app.css's
        // -fx-font-family reference is ever resolved against a Scene, for the same
        // flash-of-unstyled-content reason the theme itself is applied at this point.
        Fonts.loadAll();
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
