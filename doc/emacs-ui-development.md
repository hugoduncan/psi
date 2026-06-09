# Emacs UI development

For contributors working on the Emacs frontend at `components/emacs-ui/`. For
user-facing setup and usage, see [`emacs-ui.md`](emacs-ui.md).

## Developer checks

From repo root:

- `bb emacs:test` (loads the split frontend suites, including `psi-buffer-lifecycle-test.el`, `psi-dispatch-test.el`, `psi-streaming-transcript-test.el`, `psi-tool-output-mode-test.el`, `psi-extension-ui-test.el`, `psi-capf-test.el`, and `psi-session-tree-test.el`)
- `bb emacs:e2e` (live end-to-end harness against `psi --rpc-edn`)
- `bb emacs:byte-compile`
- `bb emacs:check`
