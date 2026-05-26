# psi Emacs frontend

This frontend runs psi over `--rpc-edn` in dedicated Emacs buffers.

Entry commands:

- `M-x psi-emacs-start` — global/default psi buffer (`*psi*` by default)
- `M-x psi-emacs-project` — project-scoped psi buffer (`*psi:<project>*`)
- `M-x psi-emacs-move-point-to-prompt-end` — return point to the current psi prompt entry end (`C-c C-e` in `psi-emacs-mode`)

By default, subprocesses start from the directory where the start command is
invoked. `psi-emacs-project` uses the detected project root as startup cwd.
Set `psi-emacs-working-directory` to force a specific working directory.

## Developer checks

Run repeatable frontend checks from repo root:

- `bb emacs:test` — run ERT suites (`psi-rpc-test.el`, `psi-buffer-lifecycle-test.el`, `psi-dispatch-test.el`, `psi-streaming-transcript-test.el`, `psi-tool-output-mode-test.el`, `psi-extension-ui-test.el`, `psi-capf-test.el`, `psi-session-tree-test.el`)
- `bb emacs:e2e` — run live end-to-end harness (`psi-e2e-test.el`) against `psi --rpc-edn` (`/history`, `/thinking` frontend-action flow, `/quit`)
- `bb emacs:byte-compile` — byte-compile frontend modules (auto-cleans `.elc`)
- `bb emacs:check` — run byte-compile + tests

Runtime guard: `psi.el` deletes local `components/emacs-ui/psi*.elc` artifacts
before requiring split modules. This prevents stale bytecode from shadowing
fresh source during iterative development and triggering setter-missing errors
(e.g. `void-function (setf psi-emacs-state-...)`).
