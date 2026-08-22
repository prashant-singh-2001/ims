package com.piecetrack.service;

import com.piecetrack.domain.AppUser;
import com.piecetrack.repository.AuditLogRepository;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;

/**
 * Thin convenience wrapper that fills in the current user (FR-SYS-03). Before/after values
 * are recorded as JSON where the caller has them to give - "where applicable" per the FR
 * text, so simple actions (a payment deletion with just a reason) still use the note-only
 * overload rather than being forced to invent a before/after pair.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository repository;
    private final AppSession session;
    private final Gson gson = new Gson();

    public AuditLogService(AuditLogRepository repository, AppSession session) {
        this.repository = repository;
        this.session = session;
    }

    public void record(String action, String entityType, Long entityId, String note) {
        AppUser user = session.currentUser();
        repository.record(user == null ? null : user.id(), action, entityType, entityId, note);
    }

    /** @param before the entity's state beforehand, or {@code null} for a creation
     *  @param after  the entity's state afterwards, or {@code null} for a deletion/cancellation
     *                whose "before" state alone is the interesting record */
    public void record(String action, String entityType, Long entityId, Object before, Object after) {
        AppUser user = session.currentUser();
        String beforeJson = before == null ? null : gson.toJson(before);
        String afterJson = after == null ? null : gson.toJson(after);
        repository.record(user == null ? null : user.id(), action, entityType, entityId, beforeJson, afterJson, null);
    }
}
