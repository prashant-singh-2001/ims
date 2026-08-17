# Changelog

All notable changes to this project are documented here. Format loosely
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

No version of this project has been tagged or released yet — everything
below has landed on `main` but ships under the working version
`0.1.0-SNAPSHOT`. Entries are grouped by the roadmap milestone that produced
them (see [`docs/04-roadmap.md`](docs/04-roadmap.md) for full scope and FR-ID
traceability), not by release date.

## [Unreleased]

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
