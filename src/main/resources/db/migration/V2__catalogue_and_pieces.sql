-- Milestone M2 (Catalogue and pieces): item models, the piece register and its movement
-- ledger, plus two tables that turned out to be needed now rather than in V1:
--   sequence_counter - gives piece tags a gap-tolerant per-model counter (FR-PIECE-02).
--   audit_log        - FR-PIECE-08 requires damage/write-off reasons to be audit-logged,
--                       even though the audit log *screen* (FR-SYS-03) is later scope.
-- Scope matches docs/04-roadmap.md M2 and docs/02-data-model.md sections 4.2, 4.3, 4.7.

CREATE TABLE sequence_counter (
    name                 TEXT NOT NULL,
    scope                TEXT NOT NULL DEFAULT '',
    next_value           INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (name, scope)
);

CREATE TABLE audit_log (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    logged_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    user_id              INTEGER REFERENCES app_user(id),
    action               TEXT NOT NULL,
    entity_type          TEXT NOT NULL,
    entity_id            INTEGER,
    before_json          TEXT,
    after_json           TEXT,
    note                 TEXT
);

CREATE TABLE category (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    name                 TEXT NOT NULL UNIQUE,
    is_active            INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1))
);

INSERT INTO category (name) VALUES
    ('Sofa'), ('Bed'), ('Dining'), ('Wardrobe'), ('Chair'), ('Table'), ('Mattress'), ('Other');

CREATE TABLE storage_location (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    name                 TEXT NOT NULL UNIQUE,
    is_active            INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1))
);

INSERT INTO storage_location (name) VALUES
    ('Showroom Floor'), ('Display Window'), ('Godown'), ('Workshop');

-- item_model holds no stock itself - "how many in stock" is always COUNT(*) over piece
-- rows in state IN_STOCK (FR-ITEM-05), never a stored counter here.
CREATE TABLE item_model (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    model_code           TEXT NOT NULL UNIQUE,
    model_name           TEXT NOT NULL,
    category_id          INTEGER NOT NULL REFERENCES category(id),
    hsn_code             TEXT NOT NULL,
    gst_rate             NUMERIC NOT NULL,
    length_cm            NUMERIC,
    width_cm             NUMERIC,
    height_cm            NUMERIC,
    material             TEXT,
    finish               TEXT,
    colour               TEXT,
    default_sale_price   INTEGER,
    is_active            INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    notes                TEXT,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

-- Photos are files under Photos/<item_model.id>/ (docs/02-data-model.md section 6);
-- relative_path is relative to that folder, not a blob - keeps the database small and is
-- why the backup archive (FR-BAK-03) must cover the Photos/ folder alongside the database.
CREATE TABLE item_photo (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    item_model_id        INTEGER NOT NULL REFERENCES item_model(id),
    relative_path        TEXT NOT NULL,
    sort_order           INTEGER NOT NULL DEFAULT 0,
    is_primary           INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1))
);

-- The piece register: one row per physical unit. state is the current-truth column;
-- stock_movement below is its append-only history - see docs/02-data-model.md section 3
-- for why both exist rather than just one.
CREATE TABLE piece (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    tag                  TEXT NOT NULL UNIQUE,
    item_model_id        INTEGER NOT NULL REFERENCES item_model(id),
    source_type          TEXT NOT NULL CHECK (source_type IN ('PURCHASE', 'OPENING_STOCK')),
    purchase_line_id     INTEGER,
    landed_cost          INTEGER NOT NULL,
    location_id          INTEGER REFERENCES storage_location(id),
    state                TEXT NOT NULL CHECK (state IN
                              ('IN_STOCK', 'SOLD', 'RETURNED_TO_SUPPLIER', 'DAMAGED', 'WRITTEN_OFF')),
    state_reason         TEXT,
    acquired_on          TEXT NOT NULL,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
);

CREATE INDEX idx_piece_state_model ON piece(state, item_model_id);
CREATE INDEX idx_piece_location_state ON piece(location_id, state);
CREATE INDEX idx_piece_acquired_on ON piece(acquired_on);

-- Append-only. No UPDATE or DELETE statement against this table is ever legitimate -
-- correcting history means adding a new row, never editing an old one.
CREATE TABLE stock_movement (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    piece_id             INTEGER NOT NULL REFERENCES piece(id),
    movement_type        TEXT NOT NULL CHECK (movement_type IN
                              ('RECEIPT', 'OPENING', 'SALE', 'SALES_RETURN', 'INVOICE_CANCELLED',
                               'PURCHASE_RETURN', 'DAMAGE', 'REPAIR', 'WRITE_OFF', 'LOCATION_CHANGE',
                               'TAG_RENAMED')),
    from_state           TEXT,
    to_state              TEXT,
    from_location_id     INTEGER REFERENCES storage_location(id),
    to_location_id       INTEGER REFERENCES storage_location(id),
    ref_type             TEXT,
    ref_id               INTEGER,
    moved_at             TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')),
    note                 TEXT
);

CREATE INDEX idx_stock_movement_piece ON stock_movement(piece_id, moved_at);
