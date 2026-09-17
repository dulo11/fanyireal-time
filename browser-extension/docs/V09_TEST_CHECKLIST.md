# v0.9 Chromium test checklist

1. Long page: start translation, scroll continuously, pause, resume, rescan, and confirm translated content is preserved.
2. Mixed language: English inside Chinese/Japanese pages should still be translated when target is Chinese/Japanese.
3. Glossary: add `OpenAI => OpenAI`, save, then verify body text, input preview and attribute text keep the fixed term.
4. Exclusions: choose one page region from the popup, verify it returns to original text and remains excluded after reload; clear site exclusions and verify translation resumes.
5. Input preview: test copy-only, swap languages, temporary mute and Alt+Enter without automatic sending.
6. DOM rewrite: on a dynamic React/Vue/chat page, verify translated text restored by the extension does not trigger another network translation when the site rewrites the same original text.
7. Attribute text: verify placeholder/title/aria-label/alt/button value translation and protection after DOM updates.
8. Open Shadow DOM: verify translation, pause/resume and protection.
9. Advanced settings: Azure test, cache prune, usage stats and glossary format validation.
