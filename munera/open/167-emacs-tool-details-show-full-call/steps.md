# Steps

- [x] Inspect Emacs tool-row rendering and existing tests for detail toggling and available call data.
- [x] Implement Emacs expanded `Call` detail rendering before existing response/output details while preserving collapsed summaries.
- [x] Add focused Emacs coverage for global collapsed, global expanded, and global toggled-closed tool rows with long or nested call arguments.
- [x] Run focused Emacs verification for the touched tool-row/transcript tests.
- [x] Run adjacent affected Emacs test suites or lint if implementation touches shared RPC/event payload code.
- [x] Update Emacs expanded call rendering so when both trusted raw `arguments` and `parsed-args` are present but parsed completeness is not provably equivalent, the `Call` section includes the raw arguments fallback; add focused coverage with a projected/filtered parsed-args fixture such as `psi-tool`.
- [x] Add focused Emacs coverage proving expanded `Call` details render an explicit nil/empty argument marker when no arguments are available, while collapsed rows still omit call details and toggled-closed rows remove them.
- [x] Add focused Emacs coverage proving invalid or unparseable raw `arguments` are displayed verbatim in expanded `Call` details instead of being dropped or reconstructed from the summary.
