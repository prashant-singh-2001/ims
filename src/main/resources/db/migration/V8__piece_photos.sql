-- Milestone M11 (Per-piece photos): a piece's actual physical condition can differ from
-- its siblings even though they share a model (FR-PIECE-10) - mirrors item_photo (V2,
-- section 4.2) one level down. relative_path is relative to Photos/pieces/<piece.id>/,
-- not Photos/<piece.id>/ - the item-model convention is a bare numeric folder directly
-- under Photos/, and a piece id can coincidentally equal an item model id, so the extra
-- "pieces" segment keeps the two namespaces from ever colliding (docs/02-data-model.md
-- section 6).
CREATE TABLE piece_photo (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    piece_id             INTEGER NOT NULL REFERENCES piece(id),
    relative_path        TEXT NOT NULL,
    sort_order           INTEGER NOT NULL DEFAULT 0,
    is_primary           INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1))
);

-- Deliberately *not* mirroring item_photo here: item_photo has no index on
-- item_model_id, but item_model_id's cardinality is bounded by the number of catalogue
-- models (dozens-to-low-hundreds), while piece_id's is bounded by the number of pieces -
-- the NFR-04 / M9 performance target is 20,000 of them. PiecePhotoRepository.findByPieceId
-- is the sole query pattern against this table, so indexing its one predicate column is
-- worth the (trivial) write/storage cost even though the sibling table skipped it.
CREATE INDEX idx_piece_photo_piece ON piece_photo(piece_id);
