# v0.9 implementation notes

The Chromium mainline remains the only active target for this version. Compatibility ports are intentionally deferred until the mainline passes real browser testing.

The glossary is applied in the background translation path so body text, input translation, translated attributes, Shadow DOM and selection translation share one implementation. Site exclusions are stored locally by hostname and are enforced by body, attribute and input/Shadow translation paths. Translation protection restores already-known translations when a page rewrites the exact prior original text; genuinely changed text is treated as new content.
