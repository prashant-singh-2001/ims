# Delivery Roadmap
## Furniture Shop Inventory Management System

Companion to `01-requirements.md`. Version 1.0, 15 August 2026.

---

## 1. Release shape

| Release | Theme | Contents |
|---|---|---|
| **v1** | Run the shop | Catalogue, piece register, purchases, GST sales, payments, documents, 3 reports, encrypted Drive backup |
| **v1.1** | Ease the month-end | GST period summary, CSV import, quotations |
| **v2** | After the sale | Customer CRM and follow-up, delivery and installation tracking |
| **v3** | Beyond one desk | Staff logins and roles on the shop LAN, multiple storage locations with transfers |

Only v1 is specified in detail. Later releases are directional — they exist here so v1's design does not paint them into a corner.

---

## 2. v1 build order

Sequenced by dependency, not by visibility. Each milestone ends with something demonstrable.

### M1 — Foundation
Spring Boot + JavaFX single-process shell, SQLite with Flyway, the money `BigDecimal` ↔ paisa converter, `jpackage` producing a Windows installer, application data folder layout, logging, single-instance guard.
Then: first-run wizard, shop profile, login, idle auto-lock, settings framework.

**Do one thing out of order here:** spike the Google OAuth desktop flow — Cloud project, consent screen, desktop client ID, a single test file uploaded with the `drive.file` scope. It is the only part of this system that depends on an external party's setup and approval behaviour, and discovering a problem with it in month four is far worse than in week two. The full backup pipeline still gets built in M8; this is only to prove the connection works.

*Satisfies:* FR-AUTH-01…08, FR-SYS-01, FR-SYS-02, NFR-01, 02, 03, 07, 08, 10.

### M2 — Catalogue and pieces
Categories, locations, item models with photos, the piece register with its state machine and movement ledger, opening stock entry, piece search and history.

At the end of M2 the shop's existing stock can be keyed in — which is the longest-lead activity for go-live, so it should start as early as possible, in parallel with the rest of the build.

*Satisfies:* FR-ITEM-01…05, FR-PIECE-01…09.

### M3 — Purchases
Suppliers with GSTIN validation, purchase bills, receipt creating pieces, landed-cost apportionment, purchase returns, receipt reversal, supplier dues.

*Satisfies:* FR-PUR-01…09.

### M4 — Sales
Billing screen, piece selection, pricing and discounts, GST computation with intra/inter-state split, gap-free invoice numbering, the atomic save, sales returns, cancellation.

Purchases come before sales for a reason: a sale needs pieces that already carry a real landed cost, otherwise profit cannot be tested against anything.

*Satisfies:* FR-SAL-01…12.

### M5 — Money
Customer receipts, supplier payments, allocations, computed balances, payment deletion with audit.

*Satisfies:* FR-PAY-01…06.

### M6 — Documents
GST invoice PDF with all mandatory fields, line grouping, amount in Indian words, credit and debit notes, PDF storage and regeneration, WhatsApp share, SMTP email with test-send.

*Satisfies:* FR-DOC-01…06.

### M7 — Reports and dashboard
Stock on hand and valuation with aging, sales and profit, outstanding dues and aging, CSV export, dashboard tiles.

*Satisfies:* FR-RPT-01…05.

### M8 — Backup and restore
The full pipeline: consistent snapshot, archive of database plus photos plus PDFs, AES-256-GCM encryption, Drive upload, verification, retention pruning, catch-up runs, offline queueing, status and warnings, local copies, and the restore flow with its safety copy and verification.

*Satisfies:* FR-BAK-01…16, NFR-15.

### M9 — Hardening and acceptance
Audit log across all consequential actions, performance pass against 20,000 pieces and 20,000 invoices with generated data, error-message review (no stack traces reach the screen), installer and upgrade test including a schema migration over live data, and a full run of the 20 acceptance tests in SRS §8.

**M9 is not optional polish.** A20 (performance at scale) and A17 (restore reproduces photos and PDFs) are the two tests most likely to fail late, and both are cheaper to fix before go-live than after.

*Satisfies:* FR-SYS-03, FR-SYS-04, NFR-04, 05, 09, 11, 14.

### M10 — UI modernization and optional GST *(post-v1)*

v1 (M1–M9) shipped and was verified against all 20 acceptance tests in SRS §8. M10 is a UI/UX-focused milestone delivered after that, not a v1.1/v2/v3 item from §5–7 below — it changes how the existing v1 functionality looks and is reached, and makes GST optional, without adding new inventory features.

Two problems drove it. First, the app looked dated: every control rendered as stock JavaFX "Modena", and there was no persistent navigation — 26 screens each hand-copied an identical header with its own Back button, 13 different controllers hardcoded the same dashboard path. Second, the app assumed every shop is GST-registered, which is not true at this scale of furniture retailer; a shop that isn't could not get past the setup wizard at all.

Delivered as one milestone, six workstreams, each verified before the next began:

- **Visual foundation** — [AtlantaFX](https://github.com/mkpaz/atlantafx) (Primer Light) as the base theme via `Application.setUserAgentStylesheet`, a token-based `app.css` with zero hardcoded hex colours, the window starting maximized.
- **App shell** — a persistent sidebar (Stock leads, matching the inventory-first goal) and top bar (title, back chevron, Lock Now) replacing the 26 per-screen headers; a `Route` enum replacing ~60 hardcoded FXML path literals with compile-time-checked navigation; the dashboard restructured stock-first with the redundant navigation rows removed (§1 and §3 of `03-screens.md` describe the result).
- **GST optional, service layer** (FR-SYS-05) — a Settings toggle; every GST-specific validation made conditional; `SalesInvoiceService.preview`/`PurchaseBillService.preview` — the two methods that cannot be bypassed — given a zero-tax branch. No schema migration: several of the affected columns (`hsn_code`, `gst_rate`, `place_of_supply_state_code`, `supplier.state_code`) are `NOT NULL` with no default, and SQLite cannot drop that constraint without rebuilding foreign-key-referenced tables, so GST-off writes sentinel values (blank HSN, 0% rate, the shop's own state code) instead.
- **GST optional, UI and documents** — GST-only fields hidden as a unit rather than one at a time (the totals grids in New Sale and Purchase Bill Entry used to interleave GST and non-GST cells at fixed positions); generated PDFs print a plain "INVOICE" with no GSTIN/HSN/tax section, driven per-document by whether that specific invoice actually carries tax rather than by the live toggle, so a document billed while GST was on never gets rewritten by a later toggle change.
- **Restyle** — the 111 inline `style=` attributes and 26 duplicated headers from before this milestone replaced with the new design system, mechanically, across every screen; table columns switched to `CONSTRAINED_RESIZE_POLICY` so they fill the now-maximized window instead of leaving dead space.
- **Hardening carried along with the shell change** — the idle lock's scrim, previously 92% opaque, made fully opaque (closing a real data-leak-through-the-lock-screen bug the persistent sidebar would otherwise have made permanent); a `ConfirmsNavigation` hook so leaving New Sale with an unsaved bill asks first, now that the sidebar offers roughly eight one-click ways off the screen instead of one; `Ctrl+1`…`Ctrl+6` sidebar shortcuts so tabbing through a form can never land in navigation (all nav buttons are `focusTraversable(false)`).

*Satisfies:* FR-SYS-05 (new); revises C-04/C-05 and partially resolves SRS open point §7.1 (see `01-requirements.md` §6–7); NFR-14 (keyboard operability, re-verified against the new sidebar).

### M11 — Per-piece photos *(post-v1)*

Extends FR-ITEM-02's per-model photo capability one level down: an owner can attach up to 3 condition photos (scuffs, wear, the specific unit a customer is looking at) directly to a `Piece` row, not just to its `ItemModel`. Two identical pieces of the same model can look different by the time either sells, and the item-model photo is a catalogue shot, not a record of one unit's actual condition.

Mirrors the item-model photo feature closely — same on-disk-not-blob storage, same 1600px downscale, same primary/remove card UI — kept as a separate `PiecePhotoService`/`PiecePhotoRepository` rather than folded into the existing (already large, widely depended-upon) `PieceService`. Stored under `Photos/pieces/<piece id>/` rather than the item-model convention's bare `Photos/<id>/`, since a piece id and an item model id are independent sequences that would otherwise collide. Reachable only from the piece detail screen, deliberately not a thumbnail column on the already-crowded piece register list. No change needed to backups, reports or invoices: `BackupService` already walks the whole `Photos/` tree generically, and neither `DocumentService` nor the report screens have ever referenced photos of any kind.

*Satisfies:* FR-PIECE-10 (new).

### M12 — OneDrive backup destination *(post-v1)*

Backups had reached only Google Drive, and getting there cost the shop owner a five-step Google Cloud Console setup (create project → enable Drive API → configure consent screen → create a Desktop OAuth client → paste client ID *and secret* into Settings) — the single biggest barrier to backups actually being switched on. OneDrive removes that barrier almost entirely: it ships pre-installed on Windows 10/11 (this app's only platform), and Microsoft treats desktop apps as *public clients* — no client secret — so a single app registration owned by the project ships inside the app, and every shop owner just signs into their own Microsoft account.

A `CloudBackupProvider` interface was extracted from the existing `GoogleDriveService` (which leaked zero Google-SDK types into its public API, confirming the extraction was low-risk) and implemented a second time by `OneDriveService`, built entirely on the JDK — `java.net.http.HttpClient`, `com.sun.net.httpserver.HttpServer` for the OAuth loopback receiver, `java.security.MessageDigest` for PKCE — deliberately not MSAL4J or the Microsoft Graph SDK, which would have pulled Nimbus JOSE+JWT, Kiota and Reactor into the packaged runtime for four REST calls this class makes directly. One provider is active at a time (`backup.provider`, defaulting to `GOOGLE_DRIVE` for back-compat); a `CloudProviders` registry resolves "active" for new uploads and "by id" for restoring or pruning an archive uploaded under a provider that is no longer the active one — `backup_history` gained a `provider` column (V9) alongside renaming `drive_file_id` to the provider-neutral `remote_file_id`.

A real pre-existing bug was found and fixed along the way: `SettingsService.clearGoogleRefreshToken()` wrote a NULL-valued settings row rather than deleting it, which made `AppSettingRepository.get()` throw `NullPointerException` on the next read — so clicking "Disconnect Google Account" threw in the running app. Fixed with `AppSettingRepository.delete(key)`, used for clearing, plus a defensive null-filter in `get`; OneDrive's disconnect would otherwise have inherited the identical defect.

*Satisfies:* FR-BAK-17 (new); revises FR-BAK-07/08/09/13.

### M13 — Bookings and delivery tracking *(post-v1)*

The shop bills at the counter and pieces go `SOLD` immediately, but furniture rarely leaves the showroom the same day — the app had no record of whether a sold piece had actually reached the customer, only the fact that it had been billed. A **booking is an existing `ACTIVE` invoice viewed by which of its items are still undelivered**, not a new pre-invoice entity: the billing, advance-payment and GST work is already done by the time an invoice exists, so this milestone is only the fulfilment fact left over afterwards.

One nullable column, `sales_line.delivered_at` (V10) — a `sales_line` is already exactly one piece per line (FR-SAL-01), so a per-line stamp is a per-piece stamp with no new table. Booking status (Pending / Partly delivered / Delivered) is computed at read time by a new `BookingRepository`/`BookingService`, the same way customer and supplier balances are computed rather than stored. A cancelled invoice is never a booking, and a piece returned before delivery is excluded from its invoice's counts entirely, so a partial return can never leave a booking stuck short of Delivered.

A new **Bookings** sidebar section (list → detail, mirroring the existing Invoices screens) sits between Sales and Purchases, which shifted every `Ctrl+N` keyboard shortcut after Sales by one — `SceneRouterShellTest` and the relevant Javadoc in `SceneRouter`/`NavBar` were updated to match.

Deliberately narrow: scheduled delivery date, vehicle/driver, installation status, customer sign-off, a dashboard tile, and the `AWAITING_DELIVERY` piece state all stay deferred to v2 (§6) — `Piece.State` and `StockMovement.Type` are untouched by this milestone.

*Satisfies:* FR-SAL-13 (new).

---

## 3. Indicative effort

Rough relative sizing for one developer familiar with Spring and JavaFX. **These are estimates for sequencing, not commitments** — no schedule has been agreed.

| Milestone | Size | Risk |
|---|---|---|
| M1 Foundation | L | Medium — packaging and the OAuth spike are the unknowns |
| M2 Catalogue and pieces | M | Low |
| M3 Purchases | M | Low, except cost apportionment arithmetic |
| M4 Sales | L | Medium — GST correctness and transactional integrity |
| M5 Money | S | Low |
| M6 Documents | M | Medium — PDF layout always takes longer than expected |
| M7 Reports | M | Low |
| M8 Backup and restore | L | **High — the most failure-prone part of the system** |
| M9 Hardening | M | Medium |
| M10 UI modernization + optional GST *(post-v1)* | L | Medium — mechanical in volume (26 screens, 111 inline styles) but the GST-off sentinel-value approach and the shell's Spring bean-cycle avoidance both needed care |
| M11 Per-piece photos *(post-v1)* | S | Low — mechanical mirror of an already-proven M2 pattern |
| M12 OneDrive backup destination *(post-v1)* | M | Low–Medium — mechanical against a proven pattern (`GoogleDriveService` → `CloudBackupProvider`), but hand-rolled OAuth+PKCE and a schema rename carry more risk than M11 did |
| M13 Bookings and delivery tracking *(post-v1)* | S | Low — one nullable column against a proven list→detail pattern; the only real risk is the Ctrl+N shortcut shift from the new sidebar section |

M8 carries the highest risk in the project despite being conceptually simple, because it combines an external API, OAuth token lifecycle, encryption, scheduling on a machine that gets switched off, and a restore path that is only exercised on the worst day the shop will ever have. It deserves the most testing per line of code of anything here.

---

## 4. Invariants that no later phase may break

These hold for v1 and must survive every future release. They are the guarantees the data model exists to provide.

1. **One piece, one active sale.** `sales_line.piece_id` is unique at the database level. Multi-user access in v3 makes this harder, not optional.
2. **Invoice numbers are consecutive, unique per financial year, and never reused** — including for cancelled invoices.
3. **`cost_at_sale` and the invoice snapshot columns are never recomputed.** A past invoice and a past profit figure mean whatever they meant on the day they were created.
4. **The stock movement ledger is append-only.** No feature may update or delete a movement row.
5. **Nothing is hard-deleted.** Cancel, discontinue, write off, soft-delete.
6. **Every backup archive contains the database, the photos and the invoice PDFs**, and every archive format change bumps the manifest version so old archives stay restorable.
7. **Migrations are forward-only** and run automatically, with an automatic local backup taken first.
8. **The application works offline.** No feature may make billing, receiving or reporting depend on connectivity.

---

## 5. v1.1 — Ease the month-end

- **GST period summary report** — output tax on sales and input tax on purchases for a period, grouped by HSN and rate, for the CA. *This is currently the only item deferred against your stated GST scope; see SRS §7.5. It moves into v1 on your word — the data is already captured in v1 either way, so only the screen is missing.*
- **CSV import** for item models and opening stock, for bulk entry and for correcting a keying-in mistake at scale.
- **Quotations** — a priced estimate for a customer that converts into an invoice, reserving no stock.

## 6. v2 — After the sale

- **Customer records and follow-up** — a proper customer master with purchase history, enquiry log, and reminders for pending payments.
- **Delivery and installation logistics** — *(narrowed by M13, post-v1)* the basic delivered/not-delivered fact per item is already tracked (FR-SAL-13, the Bookings screen). What remains here is the logistics layer on top: scheduled delivery date, vehicle and driver, installation status and customer sign-off.

This introduces a piece state between `IN_STOCK` and `SOLD` — `AWAITING_DELIVERY` — which is precisely why the state machine is defined in one place in v1 rather than scattered through the code. M13 deliberately left this state untouched so it stays available here.

## 7. v3 — Beyond one desk

- **Staff logins and roles** — owner sees costs, margins and reports; staff can bill and receive stock but not see purchase costs. The `app_user.role` column exists from v1 for this.
- **Shop LAN access** — the first genuinely structural change: SQLite gives way to a server database, and every optimistic assumption about single-user access needs revisiting. Not a small change, and the v1 design does not pretend otherwise; it only avoids making it harder than it has to be.
- **Multiple storage locations with transfers** — godown-to-showroom movement as a tracked document rather than a location edit.
