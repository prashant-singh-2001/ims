package com.furnitureims.config;

import java.nio.file.Path;

/** Exposes AppPaths' package-private test constructor to test code outside this package,
 *  so tests can point the app data root at an isolated temp directory instead of the real
 *  %LOCALAPPDATA%\FurnitureIMS\. */
public final class TestAppPathsFactory {

    private TestAppPathsFactory() {
    }

    public static AppPaths create(Path root) {
        return new AppPaths(root);
    }
}
