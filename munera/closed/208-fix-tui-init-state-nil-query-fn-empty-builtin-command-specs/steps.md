# Steps — 208 fix TUI test `init-state` empty `:builtin-command-specs`

## Slice 1 — rewire `init-state` onto the stub `query-fn` seam

- [x] In `components/tui/test/psi/tui/app_input_selector_test.clj`, inside
      `init-state`, build a stub `query-fn` (one arg = query vector) that
      returns a map `{:psi.agent-session/builtin-command-specs builtin-specs}`
      (where `builtin-specs` is the per-case value resolved from the
      `:builtin-command-specs` opt, defaulting to `sample-builtin-command-specs`).
- [x] Replace `(app/make-init nil ui-read-fn ui-disp-fn …)` with
      `(app/make-init query-fn ui-read-fn ui-disp-fn …)` so the real
      `build-init` introspection path populates `:builtin-command-specs`.
- [x] Remove the trailing `(assoc state :builtin-command-specs (vec builtin-specs))`
      from `init-state` — the seam now produces the slot.
- [x] Confirm `init-state` still accepts and strips the `:builtin-command-specs`
      opt (routing it into the stub), and continues to honour `:ui-state*` /
      `:ui-read-fn` opts unchanged.

## Slice 2 — convert per-case built-in tests to the helper opt

- [x] In `autocomplete-slash-includes-backend-builtin-commands-test` positive
      branch, replace `(assoc (init-state) :builtin-command-specs [...])` with a
      plain `(init-state)` call using the default fixture (design Test scope:
      "the positive built-in surface assertion uses the default stub specs").
- [x] Strengthen the positive branch to assert **all three** of `/help`,
      `/status`, `/quit` appear in the candidate values (design Acceptance); the
      default fixture (`sample-builtin-command-specs`) already contains all three.
- [x] In `autocomplete-slash-includes-backend-builtin-commands-test` empty
      branch, replace `(assoc (init-state) :builtin-command-specs [])` with
      `(init-state {:builtin-command-specs []})`; keep the assertion that no
      built-in (`/quit`) candidate appears.
- [x] In `autocomplete-slash-dedupes-builtin-template-collision-test`
      template-collision branch, replace
      `(assoc (init-state) :builtin-command-specs [{:name "resume" …}] :prompt-templates [{:name "resume"}])`
      with `(assoc (init-state {:builtin-command-specs [{:name "resume" …}]}) :prompt-templates [{:name "resume"}])`
      — built-in specs via the seam, `:prompt-templates` (unrelated slot) may
      remain an assoc.
- [x] In the same test's extension-collision branch, replace the
      `:builtin-command-specs` assoc with the `(init-state {:builtin-command-specs …})`
      seam form; keep `:extension-command-names ["resume"]` as an assoc.
- [x] Confirm no built-in-surface test retains a post-hoc
      `(assoc … :builtin-command-specs …)` for **any** case (grep the file).

## Slice 3 — verify

- [x] Inner loop: run the focused `app-input-selector-test` while iterating;
      confirm green. (The full TUI `bb test` below is the gating verification,
      per design Acceptance.) — 15 tests, 40 assertions, 0 failures.
- [x] Confirm unrelated `init-state` callers (history, skills-order,
      extension-command, leading-`/`) still pass — no assertion of built-in
      absence/exact-count broke (Risk R2) and none asserts `:query-fn` nil
      (Risk R3). — all green in the focused run.
- [x] Run `clj-kondo --lint components/tui/test/psi/tui/app_input_selector_test.clj`;
      0 new findings. — errors: 0, warnings: 0.
- [x] Confirm no production file changed (`git diff --name-only` lists only the
      test file + task munera files); no changelog entry (test-only, not
      user-visible).
- [x] Gating verification: run the full unit suite (TUI component is in `:unit`);
      confirm green (design Acceptance: "`bb test` (TUI component) passes"). —
      `clojure -M:test --focus unit` exited RC=0.

## Plan/steps ambiguity review follow-ups (ψ)

- [x] Slice 2 positive branch: resolve the unresolved this-or-that. design.md
      Test scope decides the positive assertion "uses the default stub specs";
      rewrite the Slice 2 positive-branch step (and plan.md Slice 2) to use the
      default-fixture form `(init-state)` only, deleting the
      "or `(init-state {:builtin-command-specs [...]})`" / "or include `status`
      in its per-case input" / "(or the default fixture)" alternatives.
- [x] Slice 3 verify: remove the "`bb test` TUI scope or focused
      `app-input-selector-test`" open choice in the first Slice 3 box. State the
      focused run as the inner loop and the full TUI `bb test` (now an explicit
      gating Slice 3 box, per design Acceptance) as the gating verification.

## Plan/steps inconsistency review follow-ups (ψ)

- [x] I1 — "regression intent" fixture vs. strengthened positive assertion.
      plan.md Decision 4 keeps reload-models/speed specs justified by the
      "previously-missing-command regression intent", but Decision 5 + Slice 2
      rewrite the positive assertion to only `/help`/`/status`/`/quit`, so no
      step asserts a previously-missing command. Reconcile: either add a
      previously-missing name (e.g. `/reload-models`) to the Slice 2 positive
      assertion, or drop Decision 4's "regression intent" justification (design
      Acceptance/Test scope require only help/status/quit).
      Resolved: chose the design-aligned option — dropped Decision 4's
      "regression intent" justification (design Acceptance/Test scope require
      only help/status/quit). Extras are now noted as benign, unasserted
      fixture data. See implementation.md.
- [x] I2 — "produced test state empty" vs. helper post-hoc assocs the default.
      design.md Problem line 31 ("produced test state has `:builtin-command-specs`
      empty") and plan.md Risk R2 ("previously empty unless they assoc'd")
      contradict the verified current helper, which ends with
      `(assoc state :builtin-command-specs (vec builtin-specs))` (full default)
      → `(init-state)` is non-empty today. Fix R2's premise (the change moves
      *how* the surface is produced — post-hoc assoc → seam — not *whether* it
      is present; unrelated tests already get the full default) and qualify
      design Problem line 31 as "empty via the `build-init` introspection path,
      before the helper's post-hoc assoc patches it".
      Resolved (plan half): R2's premise corrected in plan.md — unrelated tests
      already receive the full default surface; the rewire moves *how* the
      surface is produced, not *whether*. design.md half deferred: design.md is
      read-only in this pass; the Problem line-31 qualification is recorded as a
      pending design follow-up in implementation.md so the contradiction is
      tracked without editing the read-only artifact.

## Test review follow-ups (ψ)

- [x] Strengthen the empty-surface branch of
      `autocomplete-slash-includes-backend-builtin-commands-test` (signal/
      robustness). It asserted only `(not (contains? cand-vals "/quit"))` while
      the assertion message claims "no built-in slash command is offered" and
      design A3 requires "**no** built-in candidates". The default fixture also
      holds `help`/`status`, so a partial-leak regression dropping `quit` but
      leaking others passed vacuously. Resolved: added two more `not-contains?`
      checks so the branch now asserts none of `/help`/`/status`/`/quit` appear,
      matching the message + A3. Chose three explicit `not-contains?` checks
      (over `set/intersection`) to mirror the positive branch's per-name style
      and avoid adding a `clojure.set` require. Focused test 15/42/0; full unit
      suite RC=0.

## Test-shaper review follow-ups (ψ)

- [x] Positive/empty branch assertion-style asymmetry (consistency +
      `meaningful_failures`). In
      `autocomplete-slash-includes-backend-builtin-commands-test`, the positive
      branch's three `(is (contains? cand-vals "/help"))` / `/status` / `/quit`
      checks carry no failure messages while the empty branch now carries
      distinct per-name messages. Add matching per-name failure messages to the
      positive branch (e.g. `"with default backend specs /help is offered"`,
      `… /status …`, `… /quit …`) so the two sibling branches are
      message-symmetric and a positive-branch failure names the missing
      built-in. Re-run focused `app-input-selector-test` + `clj-kondo`.
      Resolved: added per-name messages `"with default backend specs /help is
      offered"`, `… /status …`, `… /quit …` to the positive branch, mirroring
      the empty branch. Both sibling branches are now message-symmetric.
      Focused `app-input-selector-test` 15/42/0; full `--focus unit` RC=0;
      clj-kondo 0/0.
- [x] Empty-surface branch — give the three `not-contains?` checks in the empty
      branch of `autocomplete-slash-includes-backend-builtin-commands-test`
      *distinct* failure messages so a failing run identifies which built-in
      (`/help`, `/status`, or `/quit`) leaked (test-shaper `meaningful_failures`).
      Resolved: named each message per-built-in (`"with empty backend specs
      /help is not offered"`, `… /status …`, `… /quit …`), so a failure
      pinpoints the leaking built-in. Chose per-name messages (over folding to
      a single leaked-set assertion) to mirror the per-name assertion structure
      already in the branch. Focused `app-input-selector-test` 15/42/0;
      full `--focus unit` RC=0; clj-kondo 0/0.

## Code-shaper review follow-ups (ψ)

- [x] Unify the opts-stripping idiom in `init-state`
      (`components/tui/test/psi/tui/app_input_selector_test.clj`) — consistency
      (`consistent(idioms)` + `locally_comprehensible`). The helper currently
      removes consumed opts by **two** mechanisms within five lines:
      `:builtin-command-specs` via **rebinding** `opts` (line 48) and
      `:ui-state*`/`:ui-read-fn` via a separate `opts'` binding (line 50). Pick
      one mechanism: e.g. read `builtin-specs`/`ui-atom`/`ui-read-fn*` from the
      original `opts`, then strip all three consumed keys in a single
      `opts' (dissoc opts :builtin-command-specs :ui-state* :ui-read-fn)` passed
      to `make-init`. Functionally correct today; this is a clarity shaping.
      Re-run focused `app-input-selector-test` + full `--focus unit` + clj-kondo.
      Resolved: dropped the `(dissoc opts :builtin-command-specs)` rebind;
      `builtin-specs`/`ui-atom`/`ui-read-fn*` now all read from the original
      `opts`, and a single `opts' (dissoc opts :builtin-command-specs
      :ui-state* :ui-read-fn)` strips all three consumed keys before `make-init`.
      One idiom, one binding. Focused `app-input-selector-test` 15/42/0;
      clj-paren-repair "No changes needed"; clj-kondo 0/0; full `--focus unit`
      RC=0 (3 pre-existing unrelated `F` failures confirmed present on baseline
      via git-stash — not introduced by this change). See implementation.md.
