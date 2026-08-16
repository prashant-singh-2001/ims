-- Milestone M1 (Foundation): shop profile, the single owner login, and generic settings.
-- Scope matches docs/04-roadmap.md M1 and docs/02-data-model.md sections 4.1.
-- Later milestones add their own tables in their own versioned migration, per NFR-08 -
-- schema changes are forward-only and applied automatically at startup.

CREATE TABLE shop_profile (
    id                   INTEGER PRIMARY KEY CHECK (id = 1),
    shop_name            TEXT NOT NULL,
    address_line1        TEXT,
    address_line2        TEXT,
    city                 TEXT,
    pincode              TEXT,
    state_name           TEXT NOT NULL,
    state_code           TEXT NOT NULL,
    gstin                TEXT,
    registration_type    TEXT NOT NULL CHECK (registration_type IN ('REGULAR', 'COMPOSITION')),
    phone                TEXT,
    email                TEXT,
    logo_path            TEXT,
    invoice_declaration  TEXT,
    signature_text       TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- app_user.role exists from v1 so staff logins (v3, docs/04-roadmap.md) need no migration
-- later - see FR-AUTH-07. Only one OWNER row is ever created by the setup wizard in v1.
CREATE TABLE app_user (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    username             TEXT NOT NULL UNIQUE,
    password_hash        TEXT NOT NULL,
    recovery_code_hash   TEXT,
    role                 TEXT NOT NULL CHECK (role IN ('OWNER')),
    is_active            INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    last_login_at        TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- Generic key/value settings (FR-SYS-02): idle-lock timeout, backup schedule, invoice series
-- and everything else in Settings. Deliberately schemaless - settings change far more often
-- than the schema should.
CREATE TABLE app_setting (
    key                  TEXT PRIMARY KEY,
    value                TEXT,
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);
