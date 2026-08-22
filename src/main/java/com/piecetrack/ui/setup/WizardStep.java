package com.piecetrack.ui.setup;

/** Common contract for one page of the first-run wizard (docs/03-screens.md section 2.1). */
public interface WizardStep {

    /** @return null if this step's input is valid and ready to proceed, otherwise a
     *          user-facing message describing what to fix. Called when "Next" is pressed.
     *          Must not have side effects - the wizard shell only calls {@link #commit()}
     *          once this returns null. */
    String validate();

    /** Persists this step's data. Called by the wizard shell immediately after a null
     *  {@link #validate()}, and only then. Steps that persist as part of their own
     *  in-step interaction (e.g. an explicit "Create Account" button) can leave this as
     *  the default no-op. */
    default void commit() {
    }
}
