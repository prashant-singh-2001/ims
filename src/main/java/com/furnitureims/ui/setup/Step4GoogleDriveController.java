package com.furnitureims.ui.setup;

import org.springframework.stereotype.Component;

/**
 * Setup wizard step 4: connect Google Drive for backups (FR-BAK-07, FR-BAK-08). Per
 * docs/03-screens.md section 2.1, this step is skippable - {@link #validate()} never
 * blocks finishing setup.
 * <p>
 * The "Connect Google Drive" button is intentionally disabled in this build: the OAuth
 * flow requires a Google Cloud project and a Desktop-app OAuth client that only you can
 * create (SRS section 5 lists the steps), and the backup pipeline it feeds doesn't exist
 * yet - that is milestone M8. Presenting a button that looked clickable but did nothing
 * would be worse than being upfront that this arrives later.
 */
@Component
public class Step4GoogleDriveController implements WizardStep {

    @Override
    public String validate() {
        return null;
    }
}
