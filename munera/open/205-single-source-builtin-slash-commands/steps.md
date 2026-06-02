# 205 — Steps

Checklist grouped by slice. Each item is independently executable + verifiable.
Tick with sha/decision on completion.

## Slice 1 — Backend spec table + derived projections

- [ ] Add ordered `builtin-command-specs` `array-map` in `commands.clj`, authored
      in current `format-help` display order, `/`-prefixed keys, with `:kinds`
      `:handler` `:description` `:usage` `:hide-in-help?` fields per design.
- [ ] Populate complete membership: exact-only (`/quit /exit /new /resume /status
      /history /help /? /prompts /skills /worktree /logout /reload-models
      /reload-prompts /reload-extension-installs`); prefixed-only (`/tree /jobs
      /job /cancel-job /remember /model /thinking /speed /effort /login`); dual
      `/project-repl` `#{:exact :prefixed}`. `/?` and `/exit` carry
      `:hide-in-help? true`.
- [ ] Add private helper `strip-slash` (or reuse existing) for `/`-prefix
      removal.
- [ ] Derive `exact-command-handlers` as a projection `{key → (:handler s)}` for
      entries with `:exact ∈ :kinds`; delete the literal map.
- [ ] Derive `prefixed-command-prefixes` as a projection `(vec keys)` for entries
      with `:prefixed ∈ :kinds` in table order; delete the literal vector.
- [ ] Derive `builtin-command-names` from `(set (map strip-slash (keys table)))`;
      remove the `concat`.
- [ ] Derive `format-help`'s built-in command block from `(seq table)` in table
      order, skipping `:hide-in-help?`, rendering `:usage` inline before the
      em-dash; keep `/skill:name` line + trailing prose + section blocks literal.
- [ ] Add test: derived `exact-command-handlers` equals the snapshotted prior
      literal map (regression lock).
- [ ] Add test: derived `prefixed-command-prefixes` equals the snapshotted prior
      literal vector (regression lock).
- [ ] Add test: derived `builtin-command-names` equals the snapshotted prior set.
- [ ] Add test: `/project-repl` appears in BOTH derived projections; bare
      `/project-repl` dispatches via exact handler, `/project-repl <args>` via the
      prefixed `case` (precedence unchanged).
- [ ] Add `format-help` test: built-in block renders in table order, `:usage`
      arg-hints present (`/model`, `/speed`, `/effort`), alias lines (`/?`,
      `/exit`) absent, `/skill:name` line present.
- [ ] Add narrow branch-coherence test: set of `:prefixed` spec-table keys equals
      set of `dispatch-prefixed-command` `case` branch keys.
- [ ] Run existing commands/dispatch test suite + targeted clj-kondo; green.

## Slice 2 — Backend resolver + graph discovery

- [ ] Add public `commands/builtin-command-specs-for-resolver` returning a vector
      of `{:name :description}` (bare names, table order, no `:usage`/internal
      fields).
- [ ] Add `builtin-commands-resolver` in `resolvers/extensions.clj` mirroring
      `extension-commands-resolver`; output
      `:psi.agent-session/builtin-command-specs` (+ `:psi.agent-session/builtin-command-names`).
- [ ] Register `builtin-commands-resolver` in `extensions.clj` `resolvers` vector
      so it flows into `all-resolvers` / the graph attr-index.
- [ ] Add test: resolver returns `{:name :description}` vector, bare names, table
      order; `/reload-models` and `/reload-prompts` present; `:usage`/`:kinds`/
      `:handler`/`:hide-in-help?` absent; aliases `/?`,`/exit` present (bare).
- [ ] Add graph-discovery test: `:psi.agent-session/builtin-command-specs` is in
      the resolver/attr index and resolvable for a session.
- [ ] Targeted clj-kondo over changed agent-session files; green.

## Slice 3 — TUI consumption

- [ ] `support.clj`: add `:psi.agent-session/builtin-command-specs` to
      `command-refresh-query` and the `build-init` introspection query; store
      `:builtin-command-specs` in state.
- [ ] `support.clj` `refresh-extension-command-names` (or sibling): refresh
      built-in specs from query result into state.
- [ ] `autocomplete.clj`: build built-in slash candidates from
      `state :builtin-command-specs` (slash-prefix bare names) instead of
      `shared/builtin-slash-commands`.
- [ ] `shared.clj`: remove `builtin-slash-commands` source-of-truth (delete +
      drop usages).
- [ ] Add TUI autocomplete test: candidates include `/reload-models` sourced from
      backend specs (not a hardcoded list); previously-missing built-ins present.
- [ ] Run TUI tests + targeted clj-kondo; green.

## Slice 4 — Emacs consumption

- [ ] `psi-session-commands.el`: add `:psi.agent-session/builtin-command-specs`
      to the slash-completion query string (line ~257); add frame-extractor for
      built-in specs.
- [ ] `psi-globals.el`: add state slot for backend built-in command specs
      (parallel to `extension-command-names`).
- [ ] `psi-session-commands.el` / `psi-events.el`: thread built-in specs through
      `psi-emacs--apply-slash-completion-data` and the event data path.
- [ ] `psi-completion.el`: `psi-emacs--state-slash-command-specs` merges backend
      built-in specs first (backend-wins on name collision).
- [ ] `psi-completion.el`: trim `psi-emacs-slash-command-specs` `defcustom`
      default to Emacs-only affordances (`/skill:`) + user-addition slot; update
      docstring (resolves open question #3).
- [ ] Add Emacs capf test: built-in specs from a queried frame include
      `/reload-models`; backend descriptions win over stale custom values.
- [ ] Byte-compile Emacs files clean.

## Slice 5 — Coherence lock + docs/changelog + full verify

- [ ] Add end-to-end coherence test (AC6): a representative built-in spec-table
      entry surfaces in both TUI and Emacs autocomplete with no UI-side list edit.
- [ ] CHANGELOG `[Unreleased]`: built-in commands now appear consistently in
      TUI/Emacs autocomplete; previously-missing commands (`/reload-models`,
      `/reload-prompts`, `/speed`, `/effort`, `/project-repl`, …) now listed.
- [ ] Update docs describing how slash commands are surfaced to UIs (single
      backend spec table → resolver → UI projections).
- [ ] Full `bb test` green; targeted lint clean across all changed files.
- [ ] Verify ACs: AC1 (resolver shape/order), AC2 (single keyed table /
      projections), AC3 (help derivation, aliases hidden), AC4/AC5 (TUI/Emacs
      `/reload-models`), AC6 (end-to-end flow), AC7 (one-way), AC8 (changelog +
      docs).
