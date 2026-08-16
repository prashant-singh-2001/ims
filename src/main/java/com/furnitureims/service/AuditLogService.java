package com.furnitureims.service;

import com.furnitureims.domain.AppUser;
import com.furnitureims.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

/** Thin convenience wrapper that fills in the current user (FR-SYS-03). */
@Service
public class AuditLogService {

    private final AuditLogRepository repository;
    private final AppSession session;

    public AuditLogService(AuditLogRepository repository, AppSession session) {
        this.repository = repository;
        this.session = session;
    }

    public void record(String action, String entityType, Long entityId, String note) {
        AppUser user = session.currentUser();
        repository.record(user == null ? null : user.id(), action, entityType, entityId, note);
    }
}
