-- Milestone M15 (generic inventory system, "PieceTrack"): replaces item_model's six
-- hardcoded furniture-specific columns (length_cm, width_cm, height_cm, material, finish,
-- colour) with a user-defined attribute system, so a business can describe its own products
-- ("Warranty", "Voltage", "Carat") instead of a fixed furniture-shaped form.
--
-- The six columns are NOT dropped - SQLite cannot drop a column referenced by other
-- constraints without rebuilding the table, the same reasoning V9/V10 already establish for
-- this project. Instead this migration backfills: any item_model that already has a
-- non-null value in one of the six gets a matching attribute_definition created for it, with
-- every existing value copied across as item_model_attribute rows. On a fresh install no
-- item_model rows exist yet, so every INSERT ... SELECT below is a no-op; on the one live
-- shop's database, this preserves every dimension/material/finish/colour value it already
-- has, under the new generic mechanism, with nothing lost.

CREATE TABLE attribute_definition (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL UNIQUE,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_active     INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1))
);

CREATE TABLE item_model_attribute (
    item_model_id           INTEGER NOT NULL REFERENCES item_model(id),
    attribute_definition_id INTEGER NOT NULL REFERENCES attribute_definition(id),
    value                   TEXT,
    PRIMARY KEY (item_model_id, attribute_definition_id)
);

-- ---- Backfill: preserve a live shop's existing specification data ------------------------

INSERT INTO attribute_definition (name, display_order)
SELECT 'Length (cm)', 0 WHERE EXISTS (SELECT 1 FROM item_model WHERE length_cm IS NOT NULL);
INSERT INTO item_model_attribute (item_model_id, attribute_definition_id, value)
SELECT id, (SELECT id FROM attribute_definition WHERE name = 'Length (cm)'), CAST(length_cm AS TEXT)
FROM item_model WHERE length_cm IS NOT NULL;

INSERT INTO attribute_definition (name, display_order)
SELECT 'Width (cm)', 1 WHERE EXISTS (SELECT 1 FROM item_model WHERE width_cm IS NOT NULL);
INSERT INTO item_model_attribute (item_model_id, attribute_definition_id, value)
SELECT id, (SELECT id FROM attribute_definition WHERE name = 'Width (cm)'), CAST(width_cm AS TEXT)
FROM item_model WHERE width_cm IS NOT NULL;

INSERT INTO attribute_definition (name, display_order)
SELECT 'Height (cm)', 2 WHERE EXISTS (SELECT 1 FROM item_model WHERE height_cm IS NOT NULL);
INSERT INTO item_model_attribute (item_model_id, attribute_definition_id, value)
SELECT id, (SELECT id FROM attribute_definition WHERE name = 'Height (cm)'), CAST(height_cm AS TEXT)
FROM item_model WHERE height_cm IS NOT NULL;

INSERT INTO attribute_definition (name, display_order)
SELECT 'Material', 3 WHERE EXISTS (SELECT 1 FROM item_model WHERE material IS NOT NULL);
INSERT INTO item_model_attribute (item_model_id, attribute_definition_id, value)
SELECT id, (SELECT id FROM attribute_definition WHERE name = 'Material'), material
FROM item_model WHERE material IS NOT NULL;

INSERT INTO attribute_definition (name, display_order)
SELECT 'Finish', 4 WHERE EXISTS (SELECT 1 FROM item_model WHERE finish IS NOT NULL);
INSERT INTO item_model_attribute (item_model_id, attribute_definition_id, value)
SELECT id, (SELECT id FROM attribute_definition WHERE name = 'Finish'), finish
FROM item_model WHERE finish IS NOT NULL;

INSERT INTO attribute_definition (name, display_order)
SELECT 'Colour', 5 WHERE EXISTS (SELECT 1 FROM item_model WHERE colour IS NOT NULL);
INSERT INTO item_model_attribute (item_model_id, attribute_definition_id, value)
SELECT id, (SELECT id FROM attribute_definition WHERE name = 'Colour'), colour
FROM item_model WHERE colour IS NOT NULL;

-- length_cm/width_cm/height_cm/material/finish/colour on item_model are dead from here on -
-- ItemModelRepository stops reading and writing them, but see the comment above for why the
-- columns themselves stay.

-- ---- Cleanup: the furniture-flavoured seed data from V2, but only where genuinely unused --
-- A shop that has never referenced one of these rows (a fresh install) gets a clean slate;
-- a shop with real item models or pieces already pointing at one keeps it exactly as is.

DELETE FROM category WHERE name IN ('Sofa', 'Bed', 'Dining', 'Wardrobe', 'Chair', 'Table', 'Mattress')
  AND id NOT IN (SELECT DISTINCT category_id FROM item_model);

DELETE FROM storage_location WHERE name IN ('Showroom Floor', 'Display Window', 'Godown', 'Workshop')
  AND id NOT IN (
      SELECT location_id FROM piece WHERE location_id IS NOT NULL
      UNION SELECT from_location_id FROM stock_movement WHERE from_location_id IS NOT NULL
      UNION SELECT to_location_id FROM stock_movement WHERE to_location_id IS NOT NULL
  );

-- ---- Preserve the live shop's resolved Google Drive backup folder name -------------------
-- SettingsService.DEFAULT_DRIVE_FOLDER changed from "FurnitureShopBackups" to
-- "PieceTrackBackups" as part of this same rename. That default is read live at call time,
-- never persisted at setup, so a shop that never explicitly renamed its folder would
-- otherwise see its upload destination silently change on the next backup - forking a new,
-- differently-named Drive folder and leaving its existing backups behind in the old one.
-- Only fires for an install that already has a shop profile, i.e. genuinely mid-upgrade -
-- never a fresh install, since the setup wizard runs only after migrations complete.
INSERT INTO app_setting (key, value)
SELECT 'backup.drive_folder_name', 'FurnitureShopBackups'
WHERE EXISTS (SELECT 1 FROM shop_profile)
  AND NOT EXISTS (SELECT 1 FROM app_setting WHERE key = 'backup.drive_folder_name');
