package com.furnitureims;

import com.furnitureims.ui.Route;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drift guard for {@link Route} (M10): every FXML file on the classpath must either be a
 * {@link Route}'s target, or be explicitly excluded here with a reason. Without this, a new
 * screen could ship reachable only by a raw, un-enumerated path - which would silently miss
 * the sidebar highlight, the shell chrome and the top-bar title with no test failure
 * anywhere else, since nothing else in the suite enumerates the classpath this way.
 */
class RouteCoverageTest {

    /** Loaded directly by {@link com.furnitureims.ui.setup.SetupWizardController#loadStep},
     *  not through {@link com.furnitureims.ui.SceneRouter} - the wizard shell itself
     *  ({@link Route#SETUP_WIZARD}) is the routed screen; its four step fragments are
     *  content injected into that screen's own {@code contentPane}, the same way a report's
     *  filter bar is part of the report screen rather than a screen of its own. */
    private static final Set<String> EXCLUDED = Set.of(
            "/fxml/setup/step1-shop-profile.fxml",
            "/fxml/setup/step2-owner-login.fxml",
            "/fxml/setup/step3-backup-password.fxml",
            "/fxml/setup/step4-google-drive.fxml"
    );

    @Test
    void everyFxmlOnTheClasspathIsARouteOrExplicitlyExcluded() throws IOException, URISyntaxException {
        Set<String> routedPaths = Stream.of(Route.values())
                .map(Route::fxmlPath)
                .collect(Collectors.toSet());

        for (String fxmlPath : listFxmlResources()) {
            boolean routed = routedPaths.contains(fxmlPath);
            boolean excluded = EXCLUDED.contains(fxmlPath);
            assertTrue(routed || excluded,
                    fxmlPath + " is neither a Route nor an explicit exclusion in RouteCoverageTest - "
                            + "a new screen must be added to Route, or excluded here with a reason.");
        }
    }

    @Test
    void everyRouteFxmlPathActuallyExistsOnTheClasspath() {
        for (Route route : Route.values()) {
            URL resource = getClass().getResource(route.fxmlPath());
            assertTrue(resource != null, "Route." + route.name() + " points at " + route.fxmlPath()
                    + ", which does not exist on the classpath.");
        }
    }

    private List<String> listFxmlResources() throws IOException, URISyntaxException {
        URL root = getClass().getResource("/fxml");
        assertTrue(root != null, "No /fxml resource root found on the test classpath.");

        List<String> found = new ArrayList<>();
        if ("jar".equals(root.getProtocol())) {
            String[] parts = root.toURI().toString().split("!");
            try (FileSystem fs = FileSystems.newFileSystem(URI.create(parts[0]), Map.of())) {
                walk(fs.getPath(parts[1]), found);
            }
        } else {
            walk(Path.of(root.toURI()), found);
        }
        return found;
    }

    private void walk(Path fxmlRoot, List<String> found) throws IOException {
        try (Stream<Path> paths = Files.walk(fxmlRoot)) {
            paths.filter(p -> p.toString().endsWith(".fxml")).forEach(p -> {
                String relative = fxmlRoot.relativize(p).toString().replace('\\', '/');
                found.add("/fxml/" + relative);
            });
        }
    }
}
