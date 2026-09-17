# Browser extension regression tests

These Node-based tests are run by GitHub Actions before packaging the extension.

Current coverage includes:

- language detection regressions
- glossary parsing and fixed-term rendering
- glossary runtime wrapping
- v0.9 feature contracts
- manifest/background/content-script wiring
- pause/resume, precise retry, exclusions and translation-protection structure

The tests are excluded from the release ZIP.