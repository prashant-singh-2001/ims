# Contributing

Thanks for your interest in this project.

## Issues

Anyone can open an issue, and issues are the entry point for every change —
including any code you'd like to contribute:

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

## Pull requests

Contributions are welcome, with one rule that keeps every change tied to
tracked, agreed-on work:

**Only open a pull request for an issue that was opened by you, or that is
assigned to you.**

1. Find an existing issue, or open a new one. For features, check
   [`docs/04-roadmap.md`](docs/04-roadmap.md) first.
2. If it's someone else's issue and nobody is assigned, comment to ask for
   it, and wait to be assigned before starting work.
3. Branch, make the change, and open the pull request with `Closes #<issue>`
   in the description. Keep it to one issue per pull request.

A pull request that doesn't map to such an issue — a drive-by change, or work
on an issue assigned to someone else — will be closed with a link back here,
regardless of its merit.

The code is licensed all-rights-reserved (see [`LICENSE`](LICENSE)). By
opening a pull request you agree that your contribution is provided under
that same license, and that the copyright holder may use, modify, and
relicense it as part of the project. There is no separate contributor
license agreement.

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
