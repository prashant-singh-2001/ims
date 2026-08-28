# PieceTrack

![Java 25](https://img.shields.io/badge/Java-25-orange)
![Windows 10%2F11](https://img.shields.io/badge/platform-Windows%2010%2F11-blue)
![License: Proprietary](https://img.shields.io/badge/license-proprietary-red)
![Status: Stable](https://img.shields.io/badge/status-stable-brightgreen)
![Release: v1.2.1](https://img.shields.io/badge/release-v1.2.1-blue)

A Windows desktop application for any retail business that needs to track
individual physical units, not just quantities: piece-level inventory
tracking, GST-compliant sales invoicing, purchase and payment management, and
encrypted backups to Google Drive or OneDrive. Built as a single-process Spring Boot + JavaFX
application backed by an embedded SQLite database — no server, no separate
database install, works fully offline except for backup upload, email, and
WhatsApp share.

## Why piece-level tracking

Most inventory tools count "4 chairs." This one tracks four *specific*
chairs — each with its own tag, its own recorded purchase cost, and its own
location — because that is what makes real profit-per-sale possible instead of
an averaged guess. It works for anything sold as individually distinguishable
units — furniture, appliances, jewellery, electronics — where two nominally
identical items can carry different costs and different histories. It shapes
the entire data model: see [`docs/02-data-model.md`](docs/02-data-model.md)
for the piece lifecycle state machine this is built around.

Item models carry whatever fields your business actually needs — the field
list itself (Material, Warranty, Voltage, Carat, or anything else) is defined
by the owner from Settings, not hardcoded into the software (M15).

## Features

- **Catalogue & piece register** — item models with photos and user-defined
  attributes (Material, Warranty, Voltage — whatever your business tracks);
  every physical unit individually tagged, costed, and tracked
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
  a dedicated Google Drive or OneDrive folder (one active destination at a
  time, switchable from Settings), with retention pruning, offline queueing,
  catch-up runs, and an explicit, staged restore flow.
- **Bookings** — every active invoice viewed by which of its items are still
  undelivered, with a per-item Delivered checkbox and a "Mark All Delivered"
  action.
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
| Cloud backup | Google Drive API v3 or Microsoft Graph API (OneDrive), OAuth 2.0 desktop loopback flow, one active provider at a time |
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
git clone https://github.com/prashant-singh-2001/ims.git
cd ims
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
[Releases page](https://github.com/prashant-singh-2001/ims/releases/tag/v1.2.1)
(`.exe`, built automatically by `.github/workflows/release.yml` on every tag
push) — building from source is only needed for development or if you want a
newer commit than the latest tag.

### One-time setup for cloud backup

**OneDrive** needs no setup at all — it ships pre-installed on Windows 10/11
and the app carries its own OAuth app registration, so connecting it from
Settings is just sign in and consent.

**Google Drive** needs a Google Cloud project and OAuth client that only the
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

122 automated tests across 21 test classes — real SQLite temp databases and
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

Five post-v1 milestones have since shipped: **M10** re-themed the app on
AtlantaFX with a persistent navigation shell and made GST registration
optional (a shop that isn't GST-registered can turn tax off entirely), **M11**
added per-piece condition photos on the piece detail screen, independent of
each item model's own catalogue photos, **M12** added OneDrive as a second
backup destination alongside Google Drive, **M13** added a Bookings tab
tracking which sold items have actually been delivered, and **M14** added
per-machine licensing and activation with a remote kill switch, including a
read-only mode instead of a hard lock if a licence ever needs attention.
`v1.0.0` was the first release considered feature-complete and
production-ready end to end; `v1.2.1` is the current tagged release, built
and published automatically by
[`.github/workflows/release.yml`](.github/workflows/release.yml); every push
and pull request against `main` also runs the full suite via
[`.github/workflows/ci.yml`](.github/workflows/ci.yml).

**M15** (post-`v1.0.0`) generalized the application from a furniture-only tool
into PieceTrack: the six hardcoded specification columns (dimensions,
material, finish, colour) were replaced with user-defined attribute
definitions the owner manages from Categories & Locations, so any retail
business — not just furniture — can describe its own products. Existing
installs migrate automatically: the application data folder, existing
specification data, and the Google Drive backup folder name all carry over
with nothing lost. See [`docs/04-roadmap.md`](docs/04-roadmap.md) for the
full milestone writeup.

**Post-M15** (`v1.1.0`–`v1.2.0`, not numbered roadmap milestones): a full
visual restyle — floating panels, soft shadows, a black-and-yellow palette,
real pictographic icons in place of `app.css`'s AtlantaFX default look — and
a backup-logging fix so a successful backup, a failed one, and one that never
ran at all are no longer indistinguishable silence in the log (NFR-10). See
[`CHANGELOG.md`](CHANGELOG.md) for the full entries.

v1.1 (GST period summary, CSV import, quotations) and v2/v3 are directional
only — see the roadmap for what's deferred and why. (Confusingly close to
this project's own semver tags of the same name — see
[`docs/04-roadmap.md`](docs/04-roadmap.md) §1 for the distinction.)

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Security

See [`SECURITY.md`](SECURITY.md) for how to report a vulnerability.

## License

All rights reserved — see [`LICENSE`](LICENSE). This repository is public for
visibility; it is not licensed for reuse, modification, or redistribution.

## Contact

Open a [GitHub issue](https://github.com/prashant-singh-2001/ims/issues)
or reach the maintainer at [@prashant-singh-2001](https://github.com/prashant-singh-2001).
