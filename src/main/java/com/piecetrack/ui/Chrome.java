package com.piecetrack.ui;

/**
 * Whether a screen is shown inside the application shell (sidebar + top bar) or on its own
 * (M10).
 * <p>
 * Only two screens are {@link #NONE}: the login/lock screen and the first-run setup wizard.
 * Both are gates - showing navigation on them would offer the owner places they cannot go
 * yet.
 */
public enum Chrome {

    /** Sidebar and top bar visible; the screen fills the shell's content area. */
    SHELL,

    /** No sidebar, no top bar; the screen fills the whole window. */
    NONE
}
