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

## Implementation review follow-ups (review pass 2)

- [x] R2 — Locked the exact-handler `case` seam symmetrically with R1 (option
      (a) + the (b) clarity remedy). Added a live `commands/exact-case-branches`
      def (the single literal source of the exact `case`'s `:handler` branch
      keys, authored adjacent to the `case` in `dispatch*`) with a load-time
      `assert` `(= exact-case-branches (set (vals bspec/exact-command-handlers)))`
      — so an `:exact` spec entry whose `:handler` is absent from the `case` is
      caught at namespace load (`unreachable > forbidden`). Added
      `exact-case-branch-coherence-test` reading `@#'commands/exact-case-branches`
      (mirrors `prefixed-case-branch-coherence-test`), and recommented
      `exact-command-handlers-projection-unchanged-test` to state it is a static
      snapshot lock, NOT a live-`case` coherence check. Targeted suites green
      (commands-builtin-specs + builtin-commands-resolver 10/43; commands-test
      51/206); clj-kondo clean.
- [x] R3 — Documented the deliberate Slice-2 deviation (static specs ⇒
      input-free resolver). Extended the `builtin-commands-resolver` docstring
      (resolvers/extensions.clj) to state why the `::pco/input []` / `_env` form
      is preferred over the mirrored `:psi/agent-session-ctx` input: the built-in
      spec table is a compile-time constant (session-independent), so an
      agent-session-ctx input would be inert ceremony; the input-free form keeps
      the resolver honest about its (lack of) dependencies and lets it resolve
      without an agent-session context. Also recorded under "Slice-2 deviations"
      in implementation.md so the plan↔code divergence is traceable.

## Test review follow-ups (review pass 1)

- [x] TT1 — Locked resolver `:description` content to the spec table. Added a
      `builtin-command-specs-resolver-shape-test` assertion that the resolver's
      `{name → description}` map equals
      `(into {} (for [[k s] bspec/builtin-command-specs] [(bspec/strip-slash k)
      (:description s)]))` — so a dropped/swapped/zip-misaligned description in
      `builtin-command-specs-for-resolver` is now caught (AC1), not just
      non-blankness/name order.
- [x] TT2 — Replaced the fragile whole-message `:hide-in-help?` substring checks
      in `format-help-derived-from-spec-table-test` with a dedicated
      `builtin-help-block-hide-in-help-projection-test` asserting against
      `bspec/builtin-help-block` directly: each hidden entry's exact rendered
      line is absent and each shown entry's line is present — locking the
      `:hide-in-help?` projection (built-in-block omission, not mere global
      absence). The `/skill:name` literal-line check stays in the `/help`
      message test.

## Test review follow-ups (review pass 2)

- [x] TT3 — Lock the `builtin-command-specs` per-entry well-formedness invariant
      (design "Spec-entry field set" / AC2): add a
      `builtin-command-specs-well-formed-test` in `commands_builtin_specs_test.clj`
      asserting, for EVERY entry of `bspec/builtin-command-specs`, that
      `(:kinds spec)` is a non-empty subset of `#{:exact :prefixed}` and that
      `(:exact ∈ :kinds) ⇒ (some? (:handler spec))`. Closes the gap that an
      empty-`:kinds` (named-but-unroutable) or `:exact`-without-`:handler`
      (projects `→ nil`) entry is currently representable in the single source
      yet caught by no test — projections silently assume the shape and R1/R2
      only lock the projection↔case seam, not the entry shape itself. A short
      malli schema over the entry-value or explicit `doseq`/`every?` assertions
      both work.

## Test review follow-ups (review pass 3)

- [x] TT4 — Added Emacs capf test `psi-capf-slash-no-backend-yields-no-builtins`
      (psi-capf-test.el), symmetric to the TUI empty-specs→none guard: asserts
      the shipped `(default-value 'psi-emacs-slash-command-specs)` is the trimmed
      `(("/skill:" . …))`, then with `:builtin-command-specs nil` (no backend)
      and the real default defcustom, `/qu`→no `/quit` and `/he`→no `/help`. So a
      regression re-adding built-ins to the defcustom default OR a
      `psi-emacs--state-slash-command-specs` edit re-introducing a hardcoded
      built-in list now fails (AC6/AC7). `bb emacs:check` 325/325 green (+1).
      Original item text:
      Add an Emacs capf test locking the "no-backend ⇒ `defcustom`
      supplies no built-ins" invariant (symmetric to the TUI empty-specs→none
      guard; AC6/AC7). With `:builtin-command-specs nil` (or `'()`) in
      `psi-emacs--state` and the default trimmed `psi-emacs-slash-command-specs`
      (`/skill:` only), assert slash completion for `/qu` (or `/he`) yields
      **no** `/quit`/`/help` candidate — only the Emacs-only `/skill:`
      affordance / any user-added entries survive. Closes the gap that every
      current built-in Emacs test seeds `:builtin-command-specs` (and the
      desc-wins test seeds BOTH backend + stale custom `/help`), so a regression
      re-adding built-ins to the `defcustom` default — or a
      `psi-emacs--state-slash-command-specs` edit re-introducing a hardcoded
      built-in list — would pass every Emacs test. Mirrors
      `autocomplete-slash-includes-backend-builtin-commands-test`'s
      "built-in candidates come from state, not a hardcoded list" block on the
      TUI side.

## Test review follow-ups (review pass 4)

- [x] TT5 — Added `format-help-block-line-order-test`
      (`commands_builtin_specs_test.clj`): builds the expected line sequence from
      `bspec/builtin-command-specs` filtered by `:hide-in-help?` in table order
      (reusing the `line-for` shape) and asserts
      `(str/split-lines (bspec/builtin-help-block))` equals it exactly — locking
      the whole ~18-line block, every interleaved `:usage`-bearing prefixed entry
      included, to the single source (AC3 "unchanged in order"). 11 tests / 192
      assertions green; clj-kondo clean.
      Original item text:
      Lock the full `format-help` built-in block line *order* to the
      single source (AC3 "unchanged in order"). `format-help-derived-from-spec-
      table-test` only asserts `quit < status < help` (the two leading + the
      trailing non-hidden entries), leaving the ~18 interleaved lines —
      including every `:usage`-bearing prefixed entry (`/tree`, `/model`,
      `/speed`, `/effort`, `/thinking`, `/login`, `/jobs`, …) — with no
      positional assertion; a middle-of-table reorder would pass every current
      test. Assert the rendered built-in block's line sequence equals the
      spec-table-ordered, `:hide-in-help?`-filtered projection of
      `bspec/builtin-command-specs` (reuse the `line-for` shape from
      `builtin-help-block-hide-in-help-projection-test`), locking whole-block
      order — interleaved `:usage` lines included — to the single source.
- [x] TT6 — Added `project-repl-exact-first-precedence-test`
      (`commands_builtin_specs_test.clj`) locking the seam, NOT behaviour:
      `(@#'commands/exact-command-handler "/project-repl") = :project-repl` (bare
      form has an exact handler ⇒ dispatch*'s `or` serves it via the exact path
      first), `(@#'commands/exact-command-handler "/project-repl start") = nil`
      (args form has no exact handler ⇒ exclusively prefixed-routed), and
      `(@#'commands/prefixed-command "/project-repl start") = "/project-repl"`
      (prefixed matcher reaches the args form). Deviation from item text: the
      proposed "prefixed matcher does **not** match the bare form" assertion is
      FALSE for the live `commands/prefixed-command` — `(= trimmed prefix)` is an
      explicit branch, so it matches the bare form too; exact-first precedence is
      decided by dispatch*'s `(or (case (exact-command-handler …) …)
      (dispatch-prefixed-command …))` short-circuit, not by the prefixed matcher
      declining the bare form. The seam assertions above honour TT6's intent
      ("prove the bare form is genuinely exact-routed") against the real code.
      See implementation.md "Follow-up execution — review pass 4". 11 tests /
      192 assertions green; clj-kondo clean.
      Original item text:
      Lock dual-kind `/project-repl` exact-first dispatch *precedence*
      (design "Dispatch-kind representation"), not just behaviour.
      `project-repl-dual-kind-test` asserts both projections contain
      `/project-repl` and that bare/`<args>` forms each return `:text`, but
      since both the exact handler and the prefixed `case` route to the same
      `dispatch-project-nrepl-command`, a behaviour assertion cannot prove the
      bare form is served by the exact path (exact-first) rather than the
      prefixed path — a regression flipping `dispatch*`'s `(or (case …)
      (dispatch-prefixed-command …))` order or dropping `/project-repl` from the
      exact projection would still pass. Add seam-level assertions: bare exact
      match `(commands/exact-command-handler "/project-repl") = :project-repl`,
      and that the prefixed matcher does not match the bare form (matches only
      `/project-repl <args>`), proving the bare form is genuinely exact-routed.

## Test review follow-ups (review pass 6)

- [x] TT7 — Added the full resolver name-order lock to
      `builtin-command-specs-resolver-shape-test`
      (`builtin_commands_resolver_test.clj`), in a new
      `"the FULL resolver name-order equals the spec-table key order (AC1)"`
      block: asserts `(mapv :name specs)` equals
      `(mapv #(bspec/strip-slash (key %)) bspec/builtin-command-specs)` — the
      full, interleaved spec-table key order, stripped. This locks the WHOLE
      output sequence to the single source, not just the leading
      `quit < status < help` triple (the pre-existing block) nor the
      order-insensitive TT1 `{name → description}` map; symmetric with TT5's
      help-block-order lock. A middle-of-table reorder, or a `sort`/`set`
      slipped into `builtin-command-specs-for-resolver`, now fails (AC1).
      `psi.agent-session.builtin-commands-resolver-test`: 3 tests / 26
      assertions green; clj-kondo clean.
      Original item text:
      Lock the resolver's FULL name-order to the single source (AC1 "in
      spec-table order"), symmetric with TT5's help-block-order lock.
      `builtin-command-specs-resolver-shape-test` only asserts the representative
      `quit < status < help` triple, and the TT1 description-content lock
      compares an order-INSENSITIVE `{name → description}` map — so a
      middle-of-table reorder (or a `sort`/`set` slipped into
      `builtin-command-specs-for-resolver`) passes every resolver test yet
      breaks AC1. Add to `builtin-commands-resolver-test` an assertion that the
      resolver's `(mapv :name specs)` equals
      `(mapv #(bspec/strip-slash (key %)) bspec/builtin-command-specs)`
      (the full, interleaved spec-table key order, stripped), locking the whole
      output sequence — not just the leading triple — to the single source.

## Test review follow-ups (review pass 7)

- [x] TS1 — Locked the built-in ↔ template/extension name-collision *dedup*
      contract on both UIs (pure test-coverage; no source change). TUI: added
      `autocomplete-slash-dedupes-builtin-template-collision-test`
      (`app_input_selector_test.clj`) — seeds `:builtin-command-specs
      [{:name "resume" …}]` plus `:prompt-templates [{:name "resume"}]` (and a
      second arm with `:extension-command-names ["resume"]`), opens `/`, asserts
      exactly one `/resume` candidate — driving the `slash-candidates`
      `(concat builtins templates skills ext-cmds)` → `distinct` seam with a
      genuine built-in↔template/ext collision (15t/40a, was 14t/38a). Emacs:
      added `psi-capf-slash-dedupes-builtin-template-collision-by-command-name`
      and `…-builtin-extension-collision-by-command-name` (`psi-capf-test.el`) —
      seed `:builtin-command-specs '(((:name . "resume")(:description . "resume
      the session")))` plus a `resume` template / `:extension-command-names`,
      `/re`, assert exactly one `/resume` candidate; the template arm also asserts
      backend-wins (`"resume the session"` description survives the
      `seq-uniq`). So a regression dropping `distinct`/`seq-uniq` — or merging so
      two same-named candidates survive — now fails (AC6/AC7). `bb emacs:check`
      327/327 green (+2); TUI suite green; clj-kondo clean.
      Original item text:
      Lock the built-in ↔ template/extension name-collision *dedup*
      contract newly introduced by folding `builtin-command-specs` into both UIs'
      candidate sources (TUI `slash-candidates` `(concat builtins templates
      skills ext-cmds)` → `distinct`, autocomplete.clj:61-63; Emacs
      `psi-emacs--state-slash-command-specs` backend-first `seq-uniq` merge). No
      current test drives a built-in name equal to a template/extension name, so
      a regression dropping `distinct`/`seq-uniq` (or merging so two same-named
      candidates survive) passes every UI test —
      `autocomplete-slash-includes-backend-builtin-commands-test` seeds only
      built-ins, and `psi-capf-slash-dedupes-command-template-collision-by-command-name`
      seeds a `resume` template with NO `:builtin-command-specs` (and the trimmed
      defcustom has no `/resume`), so neither exercises a built-in↔template/ext
      collision. Add:
      - TUI: seed both `:builtin-command-specs [{:name "resume" ...}]` and
        `:prompt-templates [{:name "resume" ...}]` (or `:extension-command-names
        ["resume"]`), open `/re`, assert exactly one `/resume` candidate.
      - Emacs: seed both `:builtin-command-specs '((( :name . "resume") ...))`
        and a `resume` `:prompt-templates`/`:extension-command-names`, `/re`,
        assert `(= 1 (length (seq-filter (lambda (c) (equal c "/resume")) cands)))`.
      Pure test-coverage locks over already-correct dedup behaviour; no source
      change expected.

## Docs review follow-ups (review-task-docs pass 1)

- [x] D1 — Reconciled `doc/architecture.md` "slash-command surface" bullet with
      the retained Emacs `defcustom`. Qualified the blanket "no hardcoded command
      lists" to "no hardcoded built-in *command* lists", and added a sub-bullet
      noting the Emacs `psi-emacs-slash-command-specs` `defcustom` survives as a
      user override/supplement (default trimmed to the Emacs-only `/skill:`
      affordance, not a backend routing target), merged after backend specs so
      backend descriptions win on name collision — matching the CHANGELOG
      wording. Pure doc-accuracy fix; no source/test change.

## Docs review follow-ups (review-task-docs pass 2)

- [x] D2 — Completed the `doc/tui.md` "In-session commands" reference block to
      match the now-authoritative `builtin-command-specs` table. Added the five
      missing user-invokable built-ins this task single-sourced:
      `/reload-models` and `/reload-extension-installs` (appended to the
      first/reload line beside `/reload-prompts`), and a new `/jobs [status ...]`
      `/job <job-id>` `/cancel-job <job-id>` line (usage hints lifted verbatim
      from the spec table's `:usage` fields). Help-hidden aliases `/?`/`/exit`
      and the dual-kind help-hidden `/project-repl` autocomplete entry correctly
      omitted (`/project-repl` already documented as its own sub-command line;
      `/login` already covered in the OAuth section). The TUI user reference now
      lists exactly the help-visible built-in surface. Pure doc-accuracy fix; no
      source/test change (original item text below).
      Original item text:
      `doc/tui.md` "In-session commands" reference list (lines ~57–60) is
      incomplete relative to the now-authoritative `builtin-command-specs`
      table. Add the missing user-invokable built-ins this task single-sourced:
      `/reload-models`, `/reload-extension-installs`, `/jobs [status ...]`,
      `/job <job-id>`, `/cancel-job <job-id>`. (Skip the help-hidden aliases
      `/?`/`/exit`.) Keep the existing prose sections; just complete the
      command-listing block so the TUI user reference matches the single source.
      Pure doc-accuracy fix; no source/test change.

## Code-shaper review follow-ups (code-shaper pass 1)

- [x] CS1 — De-duplicated the help-line render formula. Extracted a single
      public `render-help-line` fn in `bspec` (the sole renderer of the
      `"  /name [usage ]— description"` shape); `builtin-help-block` now maps it
      over the filtered table. Switched both test-local `line-for` fns
      (`format-help-block-line-order-test` + `builtin-help-block-hide-in-help-projection-test`)
      to call `bspec/render-help-line`, AND added `render-help-line-format-test`
      with stable literal goldens for the with-usage (`/model`) and no-usage
      (`/quit`) shapes — so a spacing/em-dash/usage-placement regression is now
      *caught* by an independent anchor rather than mirror-confirmed by the
      shared formula. No behaviour change; `builtin-help-block` output
      byte-identical. commands-builtin-specs-test green (+1 test); clj-kondo
      clean.

- [x] CS2 — Resolved the unused `:psi.agent-session/builtin-command-names`
      resolver attribute via option (a) (preferred: drop). Confirmed no UI reads
      it (TUI + Emacs consume `:builtin-command-specs` only; the `commands.clj`
      `bspec/builtin-command-names` *def* at :699 is unrelated to the resolver
      attribute). Dropped `:psi.agent-session/builtin-command-names` from the
      `builtin-commands-resolver` `::pco/output` and return map
      (extensions.clj); removed all resolver-attribute assertions/queries from
      `builtin_commands_resolver_test.clj` (shape test, the redundant bare-name
      mirror block, and the graph-discovery resolvability check) and reworded the
      ns docstring; trimmed the stale `[:psi.agent-session/builtin-command-names]`
      mention from `doc/architecture.md`'s EQL tips. The end-to-end membership
      lock (`builtin-commands-resolver-exposes-full-spec-table-membership-test`)
      still derives the name set from `:builtin-command-specs`, so AC6 coverage
      is unaffected. builtin-commands-resolver-test green; clj-kondo clean.

- [x] CS-incidental — Fixed a pre-existing Slice-3 regression surfaced by the
      full-suite verification: `app_update_runtime_test.clj`'s
      `explicit-refresh-boundary-refreshes-extension-command-names-test` stub
      `query-fn` guarded the exact one-key `[:psi.extension/command-names]` query,
      but Slice 3 widened `command-refresh-query` to also request
      `:psi.agent-session/builtin-command-specs`. Relaxed the guard to
      `(some #{:psi.extension/command-names} query)`. Test-only; full
      `bb clojure:test:unit` now green.

## Code-shaper review follow-ups (code-shaper pass 2)

- [ ] CS3 — Tighten the `builtin-command-specs` per-entry-shape invariant from
      *forbidden* (runtime test TT3) to *unreachable* (load-time), matching the
      `unreachable > forbidden` ethos AND the sibling idiom already used in this
      ns cluster (`commands.clj` guards both `case` seams with load-time
      `assert`s). The table's per-entry shape — `:kinds` non-empty
      `⊆ #{:exact :prefixed}`; `:exact ∈ :kinds ⇒ (some? :handler)`;
      `:description` non-blank string; optional `:usage` string;
      optional `:hide-in-help?` boolean — is the *primary* invariant of the
      single source that every projection silently assumes, yet it is currently
      enforced only by `builtin-command-specs-well-formed-test` (TT3), so a
      malformed entry (empty `:kinds` → named-but-unroutable; `:exact` without
      `:handler` → projects `"/name" → nil`) loads clean and is caught only when
      that test runs. Add a malli entry schema in `builtin_specs.clj` (malli is
      the project validation lib, already used in
      `psi.agent-session.dispatch-schema`) and a **load-time guard** over
      `builtin-command-specs` (e.g.
      `(assert (every? #(m/validate entry-schema (val %)) builtin-command-specs) …)`
      or `m/coerce`/`m/explain`-based), so a malformed entry fails at namespace
      load. Then shrink TT3 to assert the schema *rejects* representative
      malformations (empty `:kinds`, `:exact`-without-`:handler`), or retire it.
      Keep the existing two `case`-seam asserts unchanged. Verify the namespace
      still loads clean (the well-formed shipped table must pass) and re-run the
      commands-builtin-specs suite + targeted clj-kondo.
