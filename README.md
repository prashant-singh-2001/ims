# Furniture Shop Inventory Management System

![Java 25](https://img.shields.io/badge/Java-25-orange)
![Windows 10%2F11](https://img.shields.io/badge/platform-Windows%2010%2F11-blue)
![License: Proprietary](https://img.shields.io/badge/license-proprietary-red)
![Status: In development](https://img.shields.io/badge/status-in%20development-yellow)
![Release: v0.1.0](https://img.shields.io/badge/release-v0.1.0-blue)

A Windows desktop application for a furniture retail shop: piece-level inventory
tracking, GST-compliant sales invoicing, purchase and payment management, and
encrypted backups to Google Drive. Built as a single-process Spring Boot + JavaFX
application backed by an embedded SQLite database — no server, no separate
database install, works fully offline except for backup upload, email, and
WhatsApp share.

## Why piece-level tracking

Most inventory tools count "4 dining chairs." This one tracks four *specific*
chairs — each with its own tag, its own recorded purchase cost, and its own
location — because that is what makes real profit-per-sale possible instead of
an averaged guess. It shapes the entire data model: see
[`docs/02-data-model.md`](docs/02-data-model.md) for the piece lifecycle state
machine this is built around.

## Features

- **Catalogue & piece register** — furniture models with photos, dimensions,
  materials; every physical unit individually tagged, costed, and tracked
  through its own lifecycle (in stock → sold / returned / damaged / written off).
- **Purchases** — supplier bills with GSTIN, landed-cost apportionment across
  received pieces, purchase returns.
- **GST sales invoicing** — CGST/SGST/IGST computed automatically from place of
  supply, gap-free financial-year invoice numbering, discounts, sales returns,
  cancellation.
- **Payments** — customer receipts and supplier payments, advance-at-billing,
  computed (never stored) outstanding balances, aging.
- **Documents** — GST invoice / credit note / debit note PDFs, shared via a
  WhatsApp deep link or SMTP email, with the total spelled out in Indian
  lakh/crore words.
- **Reports & dashboard** — stock valuation and aging, sales and profit
  (computed from each piece's actual landed cost, not an average), outstanding
  dues and aging, CSV export.
- **Encrypted backup & restore** — scheduled and manual backups (database,
  photos, and invoice PDFs together) encrypted with AES-256-GCM and uploaded to
  a dedicated Google Drive folder, with retention pruning, offline queueing,
  catch-up runs, and an explicit, staged restore flow.
- **Audit log** — every consequential action (invoices, cancellations, price
  overrides, payments, settings changes, restores) recorded with before/after
  values, viewable and exportable, never editable from within the app.

Full requirement-by-requirement detail is in [`docs/01-requirements.md`](docs/01-requirements.md).

## Tech stack

| | |
|---|---|
| Language / runtime | Java 25 |
| Application framework | Spring Boot 4.1 (DI, transactions, scheduling) + JavaFX 25 (UI), one process |
| Database | SQLite (`org.xerial:sqlite-jdbc`) via plain `JdbcTemplate`, schema versioned with Flyway |
| PDF generation | OpenPDF |
| Backup encryption | PBKDF2WithHmacSHA256 + AES/GCM/NoPadding (JDK-native, no external crypto library) |
| Cloud backup | Google Drive API v3, OAuth 2.0 desktop loopback flow, `drive.file` scope only |
| Windows credential storage | Windows DPAPI via JNA |
| Packaging | `jlink` (trimmed custom runtime) + `jpackage` (native Windows installer, no separate Java install needed) |
| Build | Maven |

## Requirements

- **To run the packaged app:** Windows 10 or 11, 64-bit. Nothing else — the
  installer bundles its own Java runtime.
- **To build from source:** JDK 25 and Maven. A Windows machine is required to
  build the Windows installer itself (`jpackage` produces a platform-native
  artifact); building an MSI additionally requires the
  [WiX Toolset](https://wixtoolset.org) on the build machine.

## Getting started

```bash
git clone https://github.com/prashant-singh-2001/furniture-ims.git
cd furniture-ims
```

Run it during development (fastest loop, no packaging step):

```bash
mvn javafx:run
```

Run the automated test suite:

```bash
mvn clean test
```

Build a runnable executable jar:

```bash
mvn clean package
```

Build the Windows installer (jlink runtime + jpackage), from PowerShell:

```powershell
.\scripts\package-windows.ps1
```

This produces an MSI under `target\installer\` (pass `-InstallerType app-image`
to build a runnable folder instead, which needs no WiX Toolset — useful for a
quick local check of the packaging pipeline itself). See the script's own
header comment for exactly what each stage does and why.

A pre-built installer is published on the
[Releases page](https://github.com/prashant-singh-2001/furniture-ims/releases/tag/v0.1.0)
(`.exe`, built automatically by `.github/workflows/release.yml` on every tag
push) — building from source is only needed for development or if you want a
newer commit than the latest tag.

### One-time setup for Google Drive backup

The backup feature needs a Google Cloud project and OAuth client that only the
project owner can create — this is a prerequisite, not something the app can
provision itself. The five steps are in
[`docs/01-requirements.md` §5](docs/01-requirements.md#5-technical-direction).

## Documentation

| Document | Contents |
|---|---|
| [`docs/01-requirements.md`](docs/01-requirements.md) | The SRS — scope, ~90 numbered functional and non-functional requirements, technical direction, and the 20 acceptance tests that define "done" |
| [`docs/02-data-model.md`](docs/02-data-model.md) | Tables, keys, indexes, the piece lifecycle state machine |
| [`docs/03-screens.md`](docs/03-screens.md) | Every screen — fields, actions, validations, and which requirements it satisfies |
| [`docs/04-roadmap.md`](docs/04-roadmap.md) | Build order, milestone scope, invariants no later phase may break, what's deferred to v1.1/v2/v3 |

## Testing

87 automated tests across 15 test classes — real SQLite temp databases and
real FXML loading on the JavaFX Application Thread, not mocks, for exactly the
kind of wiring and arithmetic bugs a mock would paper over. One test class
seeds 20,000+ pieces and invoices directly to verify report and search
performance at realistic shop scale.

```bash
mvn clean test
```

## Project status

v1, as scoped in [`docs/04-roadmap.md`](docs/04-roadmap.md), is complete:
catalogue, purchases, GST sales, payments, documents, reports, encrypted
backup/restore, and hardening (audit log, performance verification, the
installer, and the full SRS acceptance run).

Two post-v1 milestones have since shipped: **M10** re-themed the app on
AtlantaFX with a persistent navigation shell and made GST registration
optional (a shop that isn't GST-registered can turn tax off entirely), and
**M11** added per-piece condition photos on the piece detail screen,
independent of each item model's own catalogue photos. `v0.1.0` is the first
tagged release, built and published automatically by
[`.github/workflows/release.yml`](.github/workflows/release.yml); every push
and pull request against `main` also runs the full suite via
[`.github/workflows/ci.yml`](.github/workflows/ci.yml).

v1.1 (GST period summary, CSV import, quotations) and v2/v3 are directional
only — see the roadmap for what's deferred and why.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Security

See [`SECURITY.md`](SECURITY.md) for how to report a vulnerability.

## License

All rights reserved — see [`LICENSE`](LICENSE). This repository is public for
visibility; it is not licensed for reuse, modification, or redistribution.

## Contact

Open a [GitHub issue](https://github.com/prashant-singh-2001/furniture-ims/issues)
or reach the maintainer at [@prashant-singh-2001](https://github.com/prashant-singh-2001).
