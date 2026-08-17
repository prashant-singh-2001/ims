package com.furnitureims.repository;

import java.time.LocalDate;

public record AuditLogSearchCriteria(
        LocalDate dateFrom,
        LocalDate dateTo,
        String action,
        String entityType
) {
    public static AuditLogSearchCriteria empty() {
        return new AuditLogSearchCriteria(null, null, null, null);
    }
}
