# Contributing

Thanks for your interest in this project.

## Current status

This repository is public for visibility, but the code is licensed
all-rights-reserved (see [`LICENSE`](LICENSE)) and is not currently set up to
accept external code contributions — there is no contributor license
agreement process, and pull requests from outside contributors will not be
merged at this time. This is a deliberate, current-state fact, not a
permanent policy; it may change.

## What is welcome

Even without accepting code changes, bug reports and feature suggestions are
genuinely useful and welcome:

- **Found a bug?** Open an issue using the bug report template. Include
  Windows version, what you did, what you expected, and what actually
  happened. If it's a crash, attach the relevant lines from the rolling log
  under `%LOCALAPPDATA%\PieceTrack\logs\` — but check them first for
  anything shop-specific (customer names, phone numbers, amounts) before
  pasting into a public issue, since this is a public repository.
- **Have a feature idea?** Open an issue using the feature request template.
  Check [`docs/04-roadmap.md`](docs/04-roadmap.md) first — it may already be
  scoped for a later release (v1.1/v2/v3), in which case the issue helps
  prioritize it rather than duplicate it.
- **Security issue?** Do not open a public issue — see
  [`SECURITY.md`](SECURITY.md) instead.

## Reporting a bug well

The most useful bug reports include:

1. Exact steps to reproduce, starting from a known state.
2. What you expected to happen versus what happened instead.
3. Whether it's reproducible every time or intermittent.
4. The application version (Settings, or the installer's version number) and
   Windows version.

## Code of conduct

Participation in this project's issue tracker is governed by the
[Code of Conduct](CODE_OF_CONDUCT.md).
