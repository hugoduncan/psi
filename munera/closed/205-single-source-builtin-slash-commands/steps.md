# 205 — Steps

Checklist grouped by slice. Each item is independently executable + verifiable.
Tick with sha/decision on completion.

## Slice 1 — Backend spec table + derived projections

- [x] Add ordered `builtin-command-specs` `array-map` in `commands.clj`, authored
      in current `format-help` display order, `/`-prefixed keys, with `:kinds`
      `:handler` `:description` `:usage` `:hide-in-help?` fields per design.
- [x] Populate complete membership: exact-only (`/quit /exit /new /resume /status
      /history /help /? /prompts /skills /worktree /logout /reload-models
      /reload-prompts /reload-extension-installs`); prefixed-only (`/tree /jobs
      /job /cancel-job /remember /model /thinking /speed /effort /login`); dual
      `/project-repl` `#{:exact :prefixed}`. `/?`, `/exit`, AND `/project-repl`
      carry `:hide-in-help? true` (the three routed commands the current
      `format-help` omits — I1).
- [x] Add private helper `strip-slash` (or reuse existing) for `/`-prefix
      removal.
- [x] Derive `exact-command-handlers` as a projection `{key → (:handler s)}` for
      entries with `:exact ∈ :kinds`; delete the literal map.
- [x] Derive `prefixed-command-prefixes` as a projection `(vec keys)` for entries
      with `:prefixed ∈ :kinds` in table order; delete the literal vector.
- [x] Derive `builtin-command-names` from `(set (map strip-slash (keys table)))`;
      remove the `concat`.
- [x] Derive `format-help`'s built-in command block from `(seq table)` in table
      order, skipping `:hide-in-help?`, rendering `:usage` inline before the
      em-dash; keep `/skill:name` line + trailing prose + section blocks literal.
- [x] Add test: derived `exact-command-handlers` equals the snapshotted prior
      literal map (regression lock).
- [x] Add test: derived `prefixed-command-prefixes` equals the snapshotted prior
      literal vector (regression lock) — compared as a **set** (table order
      differs; prefix order is not load-bearing under the dispatch matcher).
- [x] Add test: derived `builtin-command-names` equals the snapshotted prior set.
- [x] Add test: `/project-repl` appears in BOTH derived projections; bare
      `/project-repl` dispatches via exact handler, `/project-repl <args>` via the
      prefixed `case` (precedence unchanged).
- [x] Add `format-help` test: built-in block renders in table order, `:usage`
      arg-hints present (`/model`, `/speed`, `/effort`), hidden lines (`/?`,
      `/exit`, `/project-repl`) absent — no NEW `/project-repl` help line (I1) —
      `/skill:name` line present.
- [x] Add narrow branch-coherence test: set of `:prefixed` spec-table keys equals
      set of `dispatch-prefixed-command` `case` branch keys.
- [x] Run existing commands/dispatch test suite + targeted clj-kondo; green.

## Slice 2 — Backend resolver + graph discovery

- [x] Add public `builtin-command-specs-for-resolver` returning a vector
      of `{:name :description}` (bare names, table order, no `:usage`/internal
      fields). **Lives in the new leaf ns `commands.builtin-specs`** (see
      deviation) so the resolver can depend on it without a load cycle.
- [x] Add `builtin-commands-resolver` in `resolvers/extensions.clj` mirroring
      `extension-commands-resolver`; output
      `:psi.agent-session/builtin-command-specs` (+ `:psi.agent-session/builtin-command-names`).
- [x] Register `builtin-commands-resolver` in `extensions.clj` `resolvers` vector
      so it flows into `all-resolvers` / the graph attr-index.
- [x] Add test: resolver returns `{:name :description}` vector, bare names, table
      order; `/reload-models` and `/reload-prompts` present; `:usage`/`:kinds`/
      `:handler`/`:hide-in-help?` absent; aliases `/?`,`/exit` present (bare).
- [x] Add graph-discovery test: `:psi.agent-session/builtin-command-specs` is in
      the resolver/attr index (`resolver-syms`) and resolvable for a session.
- [x] Targeted clj-kondo over changed agent-session files; green.

## Slice 3 — TUI consumption

- [x] `support.clj`: add `:psi.agent-session/builtin-command-specs` to
      `command-refresh-query` and the `build-init` introspection query; store
      `:builtin-command-specs` in state.
- [x] `support.clj` `refresh-extension-command-names` (extended in place — P4):
      destructure both `:psi.extension/command-names` and
      `:psi.agent-session/builtin-command-specs` from the one query result and
      `assoc` both state slots (vector-guarded via `cond->`).
- [x] `autocomplete.clj`: build built-in slash candidates from
      `state :builtin-command-specs` (slash-prefix bare names via
      `as-slash-command`) instead of `shared/builtin-slash-commands`.
- [x] `shared.clj`: delete the `builtin-slash-commands` `def` only (P5); KEEP
      the `app.shared` require in `autocomplete.clj` (still used for
      `input-value`/`input-pos`/`set-input-value`); remove only the
      `shared/builtin-slash-commands` symbol from the line-59 `concat`.
- [x] Add TUI autocomplete test: candidates include `/reload-models` sourced from
      backend specs (not a hardcoded list); previously-missing built-ins present;
      empty backend specs → no built-in candidates. Plus a
      `refresh-extension-command-names` test folding both keys from one query.
- [x] Run TUI tests + targeted clj-kondo; green (full `bb`/kaocha unit suite
      exits clean).

## Slice 4 — Emacs consumption

- [x] `psi-session-commands.el`: add `:psi.agent-session/builtin-command-specs`
      to the slash-completion query string (`psi-emacs--prompt-template-query`);
      add `psi-emacs--builtin-command-specs-from-query-frame`
      extractor (P3 channel 1).
- [x] `psi-globals.el`: add `builtin-command-specs` state slot (parallel to
      `extension-command-names`); seed `nil` in `psi-lifecycle.el` initial state
      AND the transcript-reset path (parallel to `extension-command-names`).
- [x] `psi-session-commands.el` / `psi-events.el`: thread built-in specs through
      `psi-emacs--apply-slash-completion-data` as a third positional arg
      `(names builtin-specs templates)` — updated all 4 sites (P1): defun,
      `declare-function` (`psi-events.el`), query-frame call, event-data call.
- [x] Fold built-in specs into the change-detection token (P2): added a
      `:builtins` segment to `psi-emacs--slash-completion-token` (via new
      `psi-emacs--normalize-builtin-command-specs`) and to the inline
      `next-token` in `psi-emacs--slash-completion-data-changed-p`, segment order
      fixed `:commands :builtins :templates`. ALSO extended the change-detection
      guard (I2): added `has-builtin-specs` (from `raw-builtin-specs`) to the
      `(or has-command-names has-templates)` condition gating `next-token`, so a
      built-in-spec-only event yields a non-nil token and refreshes (AC5/AC6).
- [x] Extract built-in specs on the session-update event-data path (P3 channel 2):
      `psi-emacs--slash-completion-data-changed-p` reads
      `(:builtin-command-specs builtin-command-specs)` from event data and passes
      them to `apply-slash-completion-data`.
- [x] `psi-completion.el`: `psi-emacs--state-slash-command-specs` merges backend
      built-in specs **first** (so `seq-uniq` keeps the backend entry; backend
      wins on name collision).
- [x] `psi-completion.el`: trim `psi-emacs-slash-command-specs` `defcustom`
      default to Emacs-only affordances (`/skill:`); update docstring (resolves
      open question #3).
- [x] Add Emacs capf test: built-in specs from state include `/reload-models`;
      backend descriptions win over stale custom values; built-in-spec-only
      session event refreshes (I2 lock). Existing capf/tree tests reseeded with
      backend specs (built-ins no longer come from the defcustom).
- [x] Byte-compile Emacs files clean; `bb emacs:check` 324/324 green.

## Slice 5 — Coherence lock + docs/changelog + full verify

- [x] Add end-to-end coherence lock (AC6): backend resolver exposes the full
      single-sourced spec-table membership (resolver bare-name set ==
      `builtin-command-names`), and the UIs build candidates purely from that
      surface (TUI: empty-specs→no-builtins + backend-sourced `/reload-models`;
      Emacs: state-sourced `/reload-models` + backend-desc-wins + builtin-only
      refresh). Adding a spec-table entry therefore flows to both UIs with no
      UI-side list edit.
- [x] CHANGELOG `[Unreleased]`: built-in commands now appear consistently in
      TUI/Emacs autocomplete; previously-missing commands (`/reload-models`,
      `/reload-prompts`, `/reload-extension-installs`, `/speed`, `/effort`,
      `/project-repl`) now listed; Emacs `defcustom` repurposed.
- [x] Update docs describing how slash commands are surfaced to UIs (single
      backend spec table → resolver → UI projections) — `doc/architecture.md`
      EQL Introspection Tips.
- [x] Full clj unit suite + emacs check green; lint clean across changed files.
- [x] Verify ACs: AC1 (resolver shape/order), AC2 (single keyed table /
      projections), AC3 (help derivation, aliases hidden), AC4/AC5 (TUI/Emacs
      `/reload-models`), AC6 (end-to-end flow), AC7 (one-way), AC8 (changelog +
      docs). See implementation.md "AC verification".

## Plan ambiguity follow-ups (review pass 1)

- [x] P1 — Resolved in plan.md "P1 — `apply-slash-completion-data` threading
      shape + all call sites": third positional arg
      `(names builtin-specs templates)`; all 4 sites enumerated (defun 302,
      `declare-function` `psi-events.el:28`, query-frame call 323, event-data
      call `psi-events.el:109`) + new globals slot. Slice-4 steps updated.
- [x] P2 — Resolved in plan.md "P2 — change-detection token includes built-in
      specs": `:builtins` segment added to both `psi-emacs--slash-completion-token`
      (292) and the inline `next-token` in `…-changed-p` (`psi-events.el:80`),
      structurally identical; built-in-spec-only change triggers refresh
      (AC5/AC6). Slice-4 step added.
- [x] P3 — Resolved in plan.md "P3 — arrival channel(s): BOTH query frame and
      event path": channel 1 frame-extractor + channel 2 event-data extraction,
      both feeding the same arity/token, mirroring `extension-command-names`.
      Slice-4 steps updated.
- [x] P4 — Resolved in plan.md "P4 — TUI refresh fn: extend in place": extend
      `refresh-extension-command-names` (no sibling/rename);
      `command-refresh-query` gains the new attr; one fn destructures + `assoc`es
      both keys (vector-guarded). Slice-3 step updated.
- [x] P5 — Resolved in plan.md "P5 — `shared.clj/builtin-slash-commands`
      disposal": single pinned disposal — delete the `def` only, KEEP the
      `shared` require in `autocomplete.clj`, remove only the
      `shared/builtin-slash-commands` symbol from the line-59 `concat`;
      "empty + drop require" alternative dropped. Slice-3 step updated.

## Plan inconsistency follow-ups (review pass 1)

- [x] I1 — `/project-repl` is routed but **absent from the current
      `format-help`** (like `/?` and `/exit`), yet plan/steps assign
      `:hide-in-help? true` to ONLY `/?` and `/exit`. Deriving help from the
      whole table (skipping only `:hide-in-help?`) would NEWLY emit a
      `/project-repl` line, breaking AC3 / "format-help derivation" / the plan
      risk's "help listing unchanged in order *and* membership". Reconcile in
      plan + steps: either add `:hide-in-help? true` to `/project-repl` in the
      Slice-1 populate-membership item AND the plan "Concrete spec-table
      membership" list (keeping help membership unchanged), or explicitly accept
      a new `/project-repl` help line, drop the "unchanged membership" claim, and
      adjust the Slice-1 `format-help` golden/substring test expectation.
- [x] I2 — P2's "built-in-spec-only change is detected (AC5/AC6)" is inconsistent
      with its mechanism: `psi-emacs--slash-completion-data-changed-p`
      (`psi-events.el:80`) computes `next-token` only under
      `(and (or has-command-names has-templates) …)`, so an event with only
      built-in specs leaves `next-token` nil → no refresh, regardless of the new
      `:builtins` segment. Update plan P2 + the Slice-4 token step to also extend
      the change-detection guard (add `has-builtin-specs` to the `or`) so a
      built-in-spec-only event triggers a refresh, or state in plan why a
      built-in-spec-only event cannot arrive on channel 2.

## Implementation review follow-ups (review pass 1)

- [x] R1 — `prefixed-case-branch-coherence-test` now reads a live branch-key
      def. Added `commands/prefixed-case-branches` (the single literal source of
      the `case`'s branch keys, authored adjacent to the `case` since `case`
      needs compile-time literals; handler-wiring stays hand-written per design)
      with a load-time `assert` that it equals `(set
      bspec/prefixed-command-prefixes)` — so spec-table↔`case` drift is caught
      at namespace load (`unreachable > forbidden`). The coherence test reads
      `@#'commands/prefixed-case-branches` instead of a second hardcoded literal,
      so it now genuinely locks the live seam. Targeted suite (6/18 in
      `commands-builtin-specs-test`, 51/206 in `commands-test`) + clj-kondo
      green.
