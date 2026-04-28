# Emacs UI (rpc-edn)

The repository includes an Emacs frontend at `components/emacs-ui/` that runs
psi in a dedicated process buffer over rpc-edn.

## Install (straight.el)

Install directly from GitHub with `straight.el`:

```elisp
(straight-use-package
 '(psi-emacs
   :type git
   :host github
   :repo "hugoduncan/psi"
   :files ("components/emacs-ui/*.el")))

(require 'psi)
```

This installs the Emacs frontend files from the repo and makes
`M-x psi-emacs-start` / `M-x psi-emacs-project` available.

## Start

1. Install the canonical psi launcher so `psi` is available on `PATH`.
2. In Emacs, add `components/emacs-ui` to `load-path` and load `psi.el`.
3. Run one of:
   - `M-x psi-emacs-start` for the default/global buffer.
     - Use `C-u M-x psi-emacs-start` to force a fresh buffer name (`*psi*<2>`, etc.).
   - `M-x psi-emacs-project` for a project-scoped buffer in the current project.
     - Buffer naming style: `*psi:<project>*` (for example `*psi:psi-main*`).
     - `C-u M-x psi-emacs-project` forces a fresh generated project buffer name.
     - `C-u N M-x psi-emacs-project` opens/uses slot `N`
       (`N<=1` => `*psi:<project>*`, `N>=2` => `*psi:<project>*<N>`).

This opens a dedicated psi buffer (default `*psi*`, or project-scoped `*psi:<project>*`) and starts one
owned subprocess per dedicated buffer using:

- `psi --rpc-edn`

By default, the subprocess runs in the directory where `psi-emacs-start` is
invoked. Override explicitly via `psi-emacs-working-directory` if needed.

## Customizing `psi-emacs-command`

`psi-emacs-command` controls the exact subprocess command used by the frontend.
Default:

```elisp
("psi" "--rpc-edn")
```

Customize interactively:
- `M-x customize-variable RET psi-emacs-command RET`

Or set in init:

```elisp
(setq psi-emacs-command '("psi" "--rpc-edn"))
```

Example with explicit model:

```elisp
(setq psi-emacs-command
      '("psi" "--rpc-edn" "--model" "sonnet-4.6"))
```

Requirement: command must launch psi in rpc-edn mode (include `--rpc-edn`).

Streaming stall detection defaults to 600 seconds (10 minutes) in Emacs.
Customize `psi-emacs-stream-timeout-seconds` to change the frontend watchdog.

## Developer checks

From repo root:

- `bb emacs:test` (loads the split frontend suites, including `psi-buffer-lifecycle-test.el`, `psi-dispatch-test.el`, `psi-streaming-transcript-test.el`, `psi-tool-output-mode-test.el`, `psi-extension-ui-test.el`, `psi-capf-test.el`, and `psi-session-tree-test.el`)
- `bb emacs:e2e` (live end-to-end harness against `psi --rpc-edn`)
- `bb emacs:byte-compile`
- `bb emacs:check`

## Compose and keybindings

In `psi-emacs-mode`:

- `RET` inserts newline (never sends)
- `C-c RET` send prompt
  - slash-prefixed input always uses backend `command`
  - while streaming, non-slash input uses steer (`prompt_while_streaming` with `behavior=steer`)
- `C-u C-c RET` queue override for non-slash streaming input
- `C-c C-q` queue while streaming for non-slash input; slash-prefixed input still uses backend `command`; fallback to normal send when idle
- `M-p` navigate to older input history entry
- `M-n` navigate to newer input history entry (or recover stash after `M-p`)
- `M-r` search input history via completing-read (`M-x psi-emacs-search-input-history`);
  selecting an entry stashes the current draft and populates the input area;
  `C-g` or empty-string cancels without changing input or navigation state
- `C-c C-k` abort active streaming (`abort`)
- `C-c C-r` reconnect (prompts before clearing edited buffer)
- `C-c C-t` toggle tool-output view mode (collapsed ↔ expanded); also available as `M-x psi-emacs-toggle-tool-output-view`
- `C-c m m` set model (`M-x psi-emacs-set-model`)
- `C-c m n` cycle model next (`M-x psi-emacs-cycle-model-next`)
- `C-c m p` cycle model previous (`M-x psi-emacs-cycle-model-prev`)
- `C-c m t` set thinking level (`M-x psi-emacs-set-thinking-level`)
- `C-c m c` cycle thinking level (`M-x psi-emacs-cycle-thinking-level`)

Compose source rules:

- Active region sends region text (for both `C-c RET` and `C-c C-q`).
- Without a region, psi sends the tail draft block from the draft anchor marker to end-of-buffer.
- Normal editing keeps the anchor at the start of the current draft tail, so transcript text above the anchor is not resent unless you explicitly select it as a region.
- Reconnect clear (`C-c C-r` after confirmation) resets the buffer and repositions the draft anchor at the new buffer end; after reconnect, sends come only from text typed after that reset point.
