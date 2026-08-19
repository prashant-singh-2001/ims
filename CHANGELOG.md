# Changelog

All notable changes to this project are documented here. Format loosely
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

No version of this project has been tagged or released yet — everything
below has landed on `main` but ships under the working version
`0.1.0-SNAPSHOT`. Entries are grouped by the roadmap milestone that produced
them (see [`docs/04-roadmap.md`](docs/04-roadmap.md) for full scope and FR-ID
traceability), not by release date.

## [Unreleased]

### M13 — Bookings and delivery tracking

- Bookings tab (FR-SAL-13): a new sidebar section listing every `ACTIVE` invoice with at
  least one undelivered item - opening one shows its items with a per-piece Delivered
  checkbox and a "Mark All Delivered" action. A booking is simply an existing invoice viewed
  this way, not a new entity - no new tables, no new piece state, no stock-reservation logic.
- One nullable column, `sales_line.delivered_at` (V10) - a `sales_line` is already exactly
  one piece per line (FR-SAL-01), so a per-line stamp is a per-piece stamp. Booking status
  (Pending / Partly delivered / Delivered) is derived at read time by the new
  `BookingRepository`/`BookingService`, never stored, the same way customer and supplier
  balances are computed rather than stored.
- A cancelled invoice is never a booking, and a piece returned before delivery is excluded
  from its invoice's delivered/total counts entirely, so a partial return can never leave a
  booking stuck at "partly delivered" forever.
- Un-ticking Delivered is always allowed - correcting a mis-tick is fulfilment data, not an
  invoice edit, so FR-SAL-12's no-edit-after-save rule does not apply here. Both directions
  are audit-logged (`DELIVERY_MARKED` / `DELIVERY_UNMARKED`, FR-SYS-03).
- The new Bookings sidebar section sits between Sales and Purchases, shifting every
  `Ctrl+N` keyboard shortcut after Sales by one - `Ctrl+6` now reaches Reports instead of
  `Ctrl+5`.

### M12 — OneDrive backup destination

- OneDrive as a second backup destination (FR-BAK-17), alongside Google Drive - one active
  at a time, switchable from Settings and from the first-run wizard's step 4. OneDrive ships
  with a built-in Azure app registration (desktop apps are Microsoft "public clients" - no
  client secret to protect), so connecting it is just "Connect" -> sign in -> consent, with
  no per-shop setup step the way Google Drive still needs.
- A `CloudBackupProvider` interface, extracted from the existing `GoogleDriveService` with no
  behavior change, now implemented a second time by a new `OneDriveService` built entirely on
  the JDK (`java.net.http.HttpClient`, `com.sun.net.httpserver.HttpServer` for the OAuth
  loopback receiver, `MessageDigest` for PKCE) - no new Maven dependency. A `CloudProviders`
  registry resolves which provider is active for new uploads, and which one a specific
  archive actually went to for restore/prune, falling back to Google Drive for any
  unset/unknown/legacy-null value.
- `backup_history.drive_file_id` renamed to the provider-neutral `remote_file_id`, plus a new
  `provider` column (V9 migration), backfilled to `GOOGLE_DRIVE` for existing rows with a
  remote copy - so switching the active destination never orphans archives uploaded under
  the previous one.
- Fixed a pre-existing bug: disconnecting Google Drive threw a `NullPointerException`, because
  clearing the stored refresh token wrote a NULL-valued settings row instead of deleting it,
  and reading a NULL-valued row crashed on `Optional`/stream `findFirst()`. Added
  `AppSettingRepository.delete(key)` and a defensive null-filter in `get`.
- Packaging: added the `java.net.http` module to the jlink runtime image, needed by
  `OneDriveService` but easy to miss since nothing in this app used `java.net.http` before now
  - a gap that would only have surfaced as a packaged-runtime `NoClassDefFoundError`, never
  under `mvn javafx:run`.

### M11 — Per-piece photos

- Per-piece photos (FR-PIECE-10): the piece detail screen gained a Photos section — up to
  3 images per physical piece, documenting that specific unit's condition (scuffs, wear)
  independently of its item model's own catalogue photos (FR-ITEM-02). Same on-disk storage
  and 1600px downscale as the existing item-model photo feature; stored under
  `Photos/pieces/<piece id>/` to avoid colliding with `Photos/<item model id>/`. Included in
  the backup archive automatically — no `BackupService` change needed, since it already
  walks the whole `Photos/` tree generically.

### M10 — UI modernization and optional GST

- Adopted AtlantaFX (Primer Light) as the base theme in place of stock
  JavaFX Modena, with a token-based `app.css` design system replacing the
  old 11-rule stylesheet; the window now starts maximized.
- Replaced the 26 screens' individually hand-copied headers with a
  persistent shell: a sidebar (Stock leads, matching the inventory-first
  goal) and a top bar with a back chevron and Lock Now, navigated through a
  compile-time-checked `Route` enum instead of ~60 hardcoded FXML path
  strings.
- Dashboard restructured stock-first: stock value, pieces in stock, and
  aging now lead; today's sales and money-in/out moved below.
- GST made optional (FR-SYS-05): a Settings toggle, asked up front in the
  setup wizard; every GST-specific validation (HSN, GST rate, supplier
  state, GSTIN) becomes conditional; sales and purchase previews branch to
  a zero-tax path; GST-off writes sentinel values (blank HSN, 0% rate, the
  shop's own state code) rather than requiring a schema migration, since
  several affected columns are `NOT NULL` with no default.
- GST-only fields and totals hidden as a unit across New Sale, Purchase
  Bill Entry, the item model and supplier screens, invoice detail, and the
  sales/profit report; generated PDFs print a plain "INVOICE" with no
  GSTIN/HSN/tax section when GST is off — driven per-document by whether
  that specific invoice actually carries tax, so a document billed while
  GST was on is never retroactively rewritten by a later toggle change.
- Fixed a real data-leak-through-the-lock-screen bug found along the way:
  the idle lock scrim was 92% opaque, not fully opaque.
- Added a confirmation prompt before leaving New Sale with an unsaved
  bill, now that the sidebar offers several one-click ways off the screen
  instead of one; added `Ctrl+1`…`Ctrl+6` sidebar shortcuts so tabbing
  through a form can never land in navigation.
- Replaced 111 inline `style=` attributes with the new design system's CSS
  classes; table columns switched to fill the maximized window instead of
  leaving dead space.

### M9 — Hardening and acceptance

- Audit log now covers every consequential action FR-SYS-03 names — invoice
  created/cancelled, price overrides, payments recorded, settings changed
  (secrets redacted), restore performed — with before/after values, plus a
  viewer screen with date/action filters and CSV export.
- Global uncaught-exception handling on both the JavaFX Application Thread
  and the JVM default, so nothing unanticipated ever reaches the owner as a
  raw stack trace or a silent crash.
- Automatic local safety backup taken before any schema migration that runs
  over existing data (never on a fresh install).
- Fixed a real N+1 query pattern in the outstanding-dues report that made it
  take 180+ seconds at 20,000-invoice scale; now under a second.
- Windows installer packaging wired up end-to-end: `jlink` produces a trimmed
  custom runtime, `jpackage` wraps it with the application into a native
  installer — no separate Java installation needed on the shop PC. Fixed a
  dependency-resolution bug along the way where the JavaFX libraries were
  silently packaging as empty placeholder jars.
- Full run of the SRS's 20 acceptance tests, with a traceability table added
  to `docs/01-requirements.md` §8 mapping each one to its verifying test (or
  marking it manual-only, with why).

### M8 — Backup and restore

- Encrypted backup pipeline: consistent database snapshot, packaged with
  photos and invoice PDFs, AES-256-GCM encrypted, uploaded to a dedicated
  Google Drive folder (OAuth 2.0 desktop flow, `drive.file` scope only).
- Scheduled daily/weekly backups with catch-up runs if the PC was off at the
  scheduled time, offline queueing when there's no connectivity, retention
  pruning, and a visible dashboard status.
- Explicit, staged restore flow: download → decrypt → verify → preview →
  confirm → safety-copy current data → swap in the restored data.

### M7 — Reports and dashboard

- Stock valuation and aging, sales and profit (computed from each sold
  piece's actual recorded cost), outstanding dues and aging, CSV export.
- Real dashboard with drill-through tiles into the underlying reports.

### M6 — Documents

- GST tax invoice / bill-of-supply PDF generation (format depends on the
  shop's GST registration type), credit and debit notes, amount spelled out
  in Indian lakh/crore words.
- WhatsApp share via deep link, SMTP email delivery with a test-send option.

### M5 — Money

- Customer receipts and supplier payments, split across multiple invoices or
  bills or left on account, with every balance in the system computed live
  rather than stored.
- Advance payment at the moment of billing.

### M4 — Sales

- GST billing screen: piece selection, pricing and discounts, automatic
  CGST/SGST vs. IGST computation from place of supply, gap-free
  financial-year invoice numbering, sales returns, cancellation.

### M3 — Purchases

- Supplier master with GSTIN, purchase bill entry, exact landed-cost
  apportionment across the pieces a bill creates, purchase returns.

### M2 — Catalogue and pieces

- Categories, storage locations, furniture models with photos, and the piece
  register — every physical unit individually tagged, costed, and tracked
  through its own state machine.

### M1 — Foundation

- Spring Boot + JavaFX single-process application shell, SQLite with Flyway
  migrations, first-run setup wizard, password login with idle auto-lock,
  application data layout under `%LOCALAPPDATA%\FurnitureIMS\`.
