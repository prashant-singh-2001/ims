## Note on external contributions

This repository's code is licensed all-rights-reserved (see `LICENSE`) and is
not currently accepting external code contributions — see `CONTRIBUTING.md`.
If you're not the maintainer, please open an issue instead of a pull request
unless you've been explicitly asked to submit one.

## Summary

What does this change do, and why?

## Related issue

Closes #

## Checklist

- [ ] `mvn clean test` passes locally
- [ ] Any new behavior has a corresponding automated test (this project
      deliberately avoids mocking core business logic — see the test classes
      under `src/test/java` for the established pattern of real temp-directory
      SQLite databases and real FXML loading)
- [ ] Relevant docs updated (`docs/01-requirements.md`,
      `docs/02-data-model.md`, `docs/03-screens.md`, or `CHANGELOG.md`) if this
      changes scope, schema, or a screen
- [ ] No secrets, credentials, or real shop data included anywhere in the diff

## Screenshots

If this changes a screen, before/after screenshots help.
