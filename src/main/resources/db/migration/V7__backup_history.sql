-- Milestone M8 (Backup and restore): one row per backup attempt.
-- Scope matches docs/04-roadmap.md M8 and docs/02-data-model.md's backup_history section.
--
-- UPLOAD_PENDING is what makes FR-BAK-12 (offline tolerance) work: the archive already
-- exists, encrypted, on local disk - the upload simply hasn't happened yet - so a missing
-- internet connection is never recorded as a failure, only as "not uploaded yet".
CREATE TABLE backup_history (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    backup_type          TEXT NOT NULL CHECK (backup_type IN ('DAILY', 'WEEKLY', 'MANUAL')),
    started_at           TEXT NOT NULL,
    finished_at          TEXT,
    status               TEXT NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'UPLOAD_PENDING')),
    archive_name         TEXT,
    size_bytes           INTEGER,
    sha256               TEXT,
    drive_file_id        TEXT,
    local_path           TEXT,
    error_message        TEXT
);

CREATE INDEX idx_backup_history_started ON backup_history(started_at);
CREATE INDEX idx_backup_history_status ON backup_history(status);
