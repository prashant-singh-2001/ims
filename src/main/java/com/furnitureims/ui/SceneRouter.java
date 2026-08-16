package com.furnitureims.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;

/**
 * Owns the single {@link Stage} this desktop app ever shows. Screens are swapped in one
 * of two ways:
 * <ul>
 *   <li>{@link #show(String)} replaces the whole visible content - used for full
 *       navigation (setup wizard to login, login to the dashboard shell).</li>
 *   <li>{@link #showLockOverlay()} / {@link #hideLockOverlay()} layer the lock screen on
 *       top of whatever is currently showing, leaving it untouched underneath - this is
 *       what lets FR-AUTH-04 preserve an in-progress screen's state across an idle lock,
 *       rather than navigating away from it.</li>
 * </ul>
 * One window for the whole application lifetime is also what makes the single-instance
 * focus behaviour (NFR-07) simple: there is never more than one place to bring to front.
 * <p>
 * FXML controllers are resolved as Spring beans via {@link FXMLLoader#setControllerFactory},
 * so a controller declares its dependencies as constructor parameters like any other bean
 * instead of being wired by hand after {@code load()}.
 */
@Component
public class SceneRouter {

    private static final double WIDTH = 1024;
    private static final double HEIGHT = 720;

    private final ApplicationContext applicationContext;

    private Stage stage;
    private Scene scene;
    private StackPane rootStack;
    private StackPane contentLayer;

    /** Loaded lazily on first lock and reused after that - see the note on
     *  {@link #showLockOverlay()} for why reusing the same node (and the same singleton
     *  LoginController instance behind it) across every lock/unlock cycle is safe. */
    private Parent lockOverlayNode;

    public SceneRouter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void attachTo(Stage stage) {
        this.stage = stage;
        this.contentLayer = new StackPane();
        this.rootStack = new StackPane(contentLayer);
        this.scene = new Scene(rootStack, WIDTH, HEIGHT);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
    }

    /** Loads an FXML file from the classpath (e.g. "/fxml/login/login.fxml") and makes it
     *  the visible content of the single window, replacing whatever was there before. */
    public void show(String classpathFxml) {
        contentLayer.getChildren().setAll(load(classpathFxml));
    }

    /**
     * Shows the lock screen on top of the current content without disturbing it
     * underneath (FR-AUTH-04). The lock screen is the same login.fxml/LoginController as
     * the initial login screen, loaded once and cached here: after the very first call,
     * {@code LoginController}'s {@code @FXML} fields are permanently bound to this cached
     * node's controls rather than the full-screen login view's, which is safe precisely
     * because the full-screen login view is never shown again after the first successful
     * login of a session.
     */
    public void showLockOverlay() {
        if (lockOverlayNode == null) {
            lockOverlayNode = load("/fxml/login/login.fxml");
        }
        if (!rootStack.getChildren().contains(lockOverlayNode)) {
            rootStack.getChildren().add(lockOverlayNode);
        }
    }

    public void hideLockOverlay() {
        if (lockOverlayNode != null) {
            rootStack.getChildren().remove(lockOverlayNode);
        }
    }

    public boolean isLockOverlayVisible() {
        return lockOverlayNode != null && rootStack.getChildren().contains(lockOverlayNode);
    }

    private Parent load(String classpathFxml) {
        URL location = getClass().getResource(classpathFxml);
        if (location == null) {
            throw new IllegalStateException("FXML not found on classpath: " + classpathFxml);
        }
        FXMLLoader loader = new FXMLLoader(location);
        loader.setControllerFactory(applicationContext::getBean);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load screen: " + classpathFxml, e);
        }
    }

    public Stage stage() {
        return stage;
    }

    public Scene scene() {
        return scene;
    }
}
