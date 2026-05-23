# Steps

- [x] Inspect Emacs tool-row rendering and existing tests for detail toggling and available call data.
- [x] Implement Emacs expanded `Call` detail rendering before existing response/output details while preserving collapsed summaries.
- [x] Add focused Emacs coverage for global collapsed, global expanded, and global toggled-closed tool rows with long or nested call arguments.
- [x] Run focused Emacs verification for the touched tool-row/transcript tests.
- [x] Run adjacent affected Emacs test suites or lint if implementation touches shared RPC/event payload code.
- [x] Update Emacs expanded call rendering so when both trusted raw `arguments` and `parsed-args` are present but parsed completeness is not provably equivalent, the `Call` section includes the raw arguments fallback; add focused coverage with a projected/filtered parsed-args fixture such as `psi-tool`.
