package com.furnitureims.ui;

/**
 * Implemented by a controller whose screen can hold unsaved work that a navigation away would
 * silently discard (M10). {@link SceneRouter#navigate(Route)} consults this before tearing
 * down the current screen and, when it reports {@code true}, confirms with the owner first.
 * <p>
 * Before the persistent sidebar (M10 Workstream B), the only way off a screen like New Sale
 * was its own single "Back" button. The sidebar now offers roughly eight one-click ways away
 * from any screen, which turned what used to be a rare, deliberate action into something a
 * stray click during a busy counter transaction can trigger - this hook is what keeps that
 * from silently destroying a half-entered bill.
 */
public interface ConfirmsNavigation {

    boolean hasUnsavedWork();
}
