package com.furnitureims.service;

import com.furnitureims.domain.AppUser;
import org.springframework.stereotype.Component;

/**
 * Holds the currently authenticated user for the life of the process. In v1 there is only
 * ever one possible user (the OWNER), so this is mostly forward-compatible scaffolding for
 * the staff-login roles planned in v3 (FR-AUTH-07) and for attributing audit log entries
 * (FR-SYS-03) once there is more than a login screen to audit.
 */
@Component
public class AppSession {

    private AppUser currentUser;

    public void setCurrentUser(AppUser user) {
        this.currentUser = user;
    }

    public AppUser currentUser() {
        return currentUser;
    }
}
