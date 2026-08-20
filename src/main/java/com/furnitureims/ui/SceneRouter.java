package com.furnitureims.ui;

import com.furnitureims.domain.LicenseState;
import com.furnitureims.service.LicenseService;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
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
 *   <li>{@link #navigate(Route)} replaces the whole visible content - used for every
 *       screen-to-screen transition (setup wizard to login, login to the dashboard).</li>
 *   <li>{@link #showLockOverlay()} / {@link #hideLockOverlay()} layer the lock screen on
 *       top of whatever is currently showing, leaving it untouched underneath - this is
 *       what lets FR-AUTH-04 preserve an in-progress screen's state across an idle lock,
 *       rather than navigating away from it.</li>
 * </ul>
 * One window for the whole application lifetime is also what makes the single-instance
 * focus behaviour (NFR-07) simple: there is never more than one place to bring to front. It
 * is also load-bearing for {@code IdleLockManager}, which installs its activity filters on
 * {@link #scene()} exactly once at startup - a second {@code Scene} anywhere would silently
 * orphan idle-lock detection with no test failure, so nothing in this class may construct one.
 * <p>
 * FXML controllers are resolved as Spring beans via {@link FXMLLoader#setControllerFactory},
 * so a controller declares its dependencies as constructor parameters like any other bean
 * instead of being wired by hand after {@code load()}.
 * <p>
 * {@code navigate} takes a {@link Route} rather than a raw classpath string (M10) - every
 * screen this app has is one of the ~30 {@link Route} constants, which replaced roughly 60
 * hardcoded path literals (15 of them the single path to the dashboard, copy-pasted across
 * 13 different controllers). The shell (sidebar highlight, top-bar title, back chevron)
 * reads {@code currentRoute} after each navigation; see {@code AppShellController}.
 */
@Component
public class SceneRouter {

    /** The restore-down size. The app starts maximized (see {@code FurnitureImsFxApp}), so
     *  these are what the window becomes when the owner un-maximizes it - not the size they
     *  normally see. Kept comfortably above the minimum so restore-down never lands on a
     *  cramped window. */
    private static final double WIDTH = 1280;
    private static final double HEIGHT = 800;

    private final ApplicationContext applicationContext;
    private final LicenseService licenseService;

    private Stage stage;
    private Scene scene;
    private StackPane rootStack;
    private BorderPane shellRoot;
    private StackPane contentLayer;
    private NavBar navBar;
    private TopBar topBar;

    /** Loaded lazily on first lock and reused after that - see the note on
     *  {@link #showLockOverlay()} for why reusing the same node (and the same singleton
     *  LoginController instance behind it) across every lock/unlock cycle is safe. */
    private Parent lockOverlayNode;

    /** The route currently filling {@code contentLayer}, and the Spring-managed controller
     *  instance behind it. Read by the shell (sidebar highlight, top-bar title and back
     *  chevron) after every {@link #navigate(Route)} - {@code null} before the first call,
     *  which only ever happens before {@link #attachTo(Stage)} has even run. */
    private Route currentRoute;
    private Object currentController;

    public SceneRouter(ApplicationContext applicationContext, LicenseService licenseService) {
        this.applicationContext = applicationContext;
        this.licenseService = licenseService;
    }

    /**
     * Builds the shell exactly once for the process lifetime:
     * <pre>
     * rootStack : StackPane
     *   +-- shellRoot : BorderPane
     *   |     +-- left   : navBar.view()
     *   |     +-- top    : topBar.view()
     *   |     +-- center : contentLayer : StackPane   &lt;- every screen loads here
     *   +-- [lockOverlayNode]                          &lt;- added/removed on top, unchanged
     * </pre>
     * {@code navBar}/{@code topBar} are resolved via {@link ApplicationContext#getBean} here
     * rather than constructor-injected into this class - both of them need a
     * {@code SceneRouter} reference themselves (a nav button click navigates; the top bar's
     * back chevron does too), and every controller in this app already constructor-injects
     * {@code SceneRouter}, so the reverse edge would produce a
     * {@code BeanCurrentlyInCreationException} at context refresh. Resolving lazily here
     * breaks that cycle without breaking the one-way dependency anywhere else.
     */
    public void attachTo(Stage stage) {
        this.stage = stage;
        this.navBar = applicationContext.getBean(NavBar.class);
        this.topBar = applicationContext.getBean(TopBar.class);

        this.contentLayer = new StackPane();
        this.shellRoot = new BorderPane();
        shellRoot.getStyleClass().add("app-shell");
        shellRoot.setLeft(navBar.view());
        shellRoot.setTop(topBar.view());
        shellRoot.setCenter(contentLayer);

        this.rootStack = new StackPane(shellRoot);
        this.scene = new Scene(rootStack, WIDTH, HEIGHT);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        stage.setScene(scene);
        wireSectionKeyboardShortcuts();
    }

    /** NFR-14: the sidebar's buttons are all {@code focusTraversable(false)} (M10), so tabbing
     *  through a form like New Sale can never land in navigation - but that also means the
     *  keyboard needs some other way to reach the sidebar's destinations. Ctrl+1..7 covers the
     *  seven {@link NavSection}s in the same order they appear in the sidebar, read from
     *  {@link NavBar#landingFor} rather than a second copy of that ordering. A scene-level
     *  accelerator fires regardless of what currently has focus, and still goes through
     *  {@link #navigate(Route)}, so it is confirmed like any other navigation
     *  ({@link ConfirmsNavigation}). */
    private void wireSectionKeyboardShortcuts() {
        int digit = 1;
        for (NavSection section : NavSection.values()) {
            if (section == NavSection.NONE) {
                continue;
            }
            Route target = navBar.landingFor(section);
            KeyCombination shortcut = new KeyCodeCombination(KeyCode.valueOf("DIGIT" + digit),
                    KeyCombination.CONTROL_DOWN);
            scene.getAccelerators().put(shortcut, () -> navigate(target));
            digit++;
        }
    }

    /** Loads {@code route}'s FXML and makes it the visible content of the single window,
     *  replacing whatever was there before, then applies its {@link Chrome} - showing or
     *  hiding the sidebar and top bar, updating the active nav highlight, the top-bar title
     *  and its back chevron. Every screen-to-screen transition in the app goes through this
     *  one method - including a screen's own "Back" button - which is what makes it the one
     *  place {@link #confirmLeavingCurrentScreen()} needs to guard (M10). Does nothing if the
     *  owner cancels that confirmation: {@code currentRoute}/{@code currentController} are
     *  left exactly as they were, so the screen they were about to leave is still showing. */
    public void navigate(Route route) {
        if (route.requiresLicense() && licenseService.state() == LicenseState.WIND_DOWN) {
            showLicenseWindDownNotice();
            return;
        }
        if (!confirmLeavingCurrentScreen()) {
            return;
        }
        currentRoute = route;
        currentController = show(route.fxmlPath());
        applyChrome(route);
    }

    /** M14: the plain-language explanation NFR-11 requires when a write screen is refused -
     *  never a stack trace, and never phrased as an error the owner caused. Read-only
     *  wind-down means every already-recorded invoice, report and backup stays fully
     *  reachable; only creating a new one is blocked, so the message says exactly that. */
    private void showLicenseWindDownNotice() {
        Alert notice = new Alert(Alert.AlertType.WARNING);
        notice.setTitle("Licence Needs Attention");
        notice.setHeaderText("This copy of the app is in read-only mode.");
        notice.setContentText("All your existing records, reports and backups are still fully available, "
                + "but new invoices, bills and payments cannot be created until the licence is sorted out. "
                + "See Settings > Licence for details, or contact the supplier.");
        notice.showAndWait();
    }

    /** M10: asks before discarding unsaved work - see {@link ConfirmsNavigation}. Screens
     *  that don't hold that kind of state (almost everything - lists, reports, settings)
     *  implement nothing extra and are never prompted, exactly as before this hook existed. */
    private boolean confirmLeavingCurrentScreen() {
        if (!(currentController instanceof ConfirmsNavigation guard) || !guard.hasUnsavedWork()) {
            return true;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Unsaved Work");
        confirm.setHeaderText("This screen has unsaved work.");
        confirm.setContentText("Leaving now will discard it. Continue?");
        return confirm.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }

    private void applyChrome(Route route) {
        boolean shell = route.chrome() == Chrome.SHELL;
        // managed=false, not just visible=false: on LOGIN/SETUP_WIZARD this is what lets
        // contentLayer expand to fill the whole window instead of leaving an empty gutter
        // where the sidebar/top bar would have been - BorderPane treats an unmanaged child
        // as absent for layout purposes.
        navBar.view().setVisible(shell);
        navBar.view().setManaged(shell);
        topBar.view().setVisible(shell);
        topBar.view().setManaged(shell);

        if (!shell) {
            return;
        }
        navBar.setActive(route);
        topBar.setBackTarget(route.parent());
        topBar.refreshLicenseBanner();

        topBar.titleProperty().unbind();
        if (currentController instanceof HasScreenTitle hasTitle) {
            topBar.titleProperty().bind(hasTitle.screenTitleProperty());
        } else {
            topBar.titleProperty().set(route.title() == null ? "" : route.title());
        }
    }

    public Route currentRoute() {
        return currentRoute;
    }

    /** The Spring-managed controller instance behind {@link #currentRoute()} - what the
     *  shell inspects for an optional {@code HasScreenTitle} to bind the top-bar title to. */
    public Object currentController() {
        return currentController;
    }

    /** Loads an FXML file from the classpath and makes it the visible content of the single
     *  window. Private: {@link #navigate(Route)} is the only public way to change screens,
     *  which is what guarantees {@link #currentRoute()} always describes what is actually
     *  showing. Returns the controller Spring resolved for it. */
    private Object show(String classpathFxml) {
        LoadedScreen loaded = loadWithController(classpathFxml);
        contentLayer.getChildren().setAll(loaded.root());
        return loaded.controller();
    }

    /**
     * Shows the lock screen on top of the current content without disturbing it
     * underneath (FR-AUTH-04). The lock screen is the same login.fxml/LoginController as
     * the initial login screen, loaded once and cached here: after the very first call,
     * {@code LoginController}'s {@code @FXML} fields are permanently bound to this cached
     * node's controls rather than the full-screen login view's, which is safe precisely
     * because the full-screen login view is never shown again after the first successful
     * login of a session.
     * <p>
     * {@code shellRoot} is hidden with {@code setVisible(false)} - deliberately <em>not</em>
     * {@code setManaged(false)} (M10). Two bugs predate this and would otherwise persist
     * indefinitely once the sidebar made them permanent fixtures rather than a login screen
     * seen once at startup: the pre-M10 scrim was only 92% opaque, leaving a sliver of the
     * shop's data legible through the lock; and a node behind a {@code StackPane} overlay
     * stays focus-traversable and mouse-pickable by default, so Tab-Tab-Enter (or a stray
     * click landing through a translucent gap) could operate whatever screen the lock was
     * supposed to be hiding. An invisible node is excluded from both hit-testing and focus
     * traversal, closing both holes - but unlike an unmanaged one, it is still laid out, so
     * a half-entered New Sale, a scrolled TableView, or the wizard's step position all come
     * back exactly as they were the moment {@link #hideLockOverlay()} runs. Setting
     * {@code managed=false} instead would force a re-layout on restore and risk resetting
     * exactly the state FR-AUTH-04 requires to survive.
     */
    public void showLockOverlay() {
        if (lockOverlayNode == null) {
            lockOverlayNode = load("/fxml/login/login.fxml");
        }
        shellRoot.setVisible(false);
        if (!rootStack.getChildren().contains(lockOverlayNode)) {
            rootStack.getChildren().add(lockOverlayNode);
        }
    }

    public void hideLockOverlay() {
        if (lockOverlayNode != null) {
            rootStack.getChildren().remove(lockOverlayNode);
        }
        shellRoot.setVisible(true);
    }

    public boolean isLockOverlayVisible() {
        return lockOverlayNode != null && rootStack.getChildren().contains(lockOverlayNode);
    }

    private Parent load(String classpathFxml) {
        return loadWithController(classpathFxml).root();
    }

    private record LoadedScreen(Parent root, Object controller) {
    }

    private LoadedScreen loadWithController(String classpathFxml) {
        URL location = getClass().getResource(classpathFxml);
        if (location == null) {
            throw new IllegalStateException("FXML not found on classpath: " + classpathFxml);
        }
        FXMLLoader loader = new FXMLLoader(location);
        loader.setControllerFactory(applicationContext::getBean);
        try {
            Parent root = loader.load();
            return new LoadedScreen(root, loader.getController());
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
