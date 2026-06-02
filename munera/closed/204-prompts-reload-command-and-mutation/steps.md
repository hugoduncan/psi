# Steps — 204 prompts-reload command + mutation

Vertical slices from plan.md. Tick each item with its sha / decision note.

## Slice 1 — `:session/reload-prompts` dispatch handler

- [x] Add `[psi.prompt-assets.prompt-templates :as pt]` require to
      `dispatch_handlers/session_mutations.clj` (`session/session-worktree-path-in`
      = `psi.session-state.state` already in scope). (handler ns require added)
- [x] Register `:session/reload-prompts` core handler: single `worktree-path`
      `let` binding, opts `{:global-prompts-dir (:global-prompts-dir
      pt/default-config) :project-prompts-dir (str worktree-path "/.psi/prompts")}`,
      `(pt/discover-templates opts)` inline.
- [x] Handler returns a single `:root-state-update`
      `(session/session-update session-id #(assoc % :prompt-templates discovered))`
      + `:return {:reloaded? true :count (count discovered) :worktree worktree-path}`;
      emits **no** `:effects`.
- [x] Dispatch-handler test (`reload-prompts-handler-replaces-templates-test`):
      worktree with `foo`/`bar`, seeded stale template; dispatch replaces
      `:prompt-templates` with discovered vector (AC7); return map carries
      `:reloaded?`/`:count`/`:worktree`.
- [x] No-effects test (`reload-prompts-handler-emits-no-effects-test`): direct
      handler call asserts `(nil? (:effects result))`, has `:root-state-update`.
- [x] `clj-kondo` clean (0/0); focused test green (3 tests / 11 assertions).

## Slice 2 — `reload-prompts-in!` core entry fn

- [x] Added `reload-prompts-in!` to `session_settings.clj` mirroring
      `reload-models-in!`: `(dispatch/dispatch! ctx :session/reload-prompts
      {:session-id session-id} {:origin :core})`.
- [x] Added `reload-prompts-in!` **re-export** to `psi.agent-session.core`
      delegating to `settings/reload-prompts-in!` (mirrors the
      `reload-models-in!` re-export).
- [x] Confirmed `dispatch!` surfaces handler `:return` map
      (`reload-prompts-in-core-fn-surfaces-return-test`): asserts
      `:reloaded?`/`:count`/`:worktree`.
- [x] `clj-kondo` clean; focused test green.

## Slice 3 — `psi.extension/reload-prompts` mutation

- [x] Added `reload-prompts` `pco/defmutation` to `mutations/prompts.clj`:
      `::pco/op-name 'psi.extension/reload-prompts`,
      `::pco/params [:psi/agent-session-ctx :session-id]`,
      `::pco/output [:psi.prompt-template/reloaded? :psi.prompt-template/count]`.
- [x] Mutation body calls `core/reload-prompts-in!` (added `[psi.agent-session.core
      :as core]` require, mirroring `mutations/session.clj`'s
      `core/reload-models-in!` use); returns
      `{:psi.prompt-template/reloaded? (boolean reloaded?)
        :psi.prompt-template/count (or count 0)}` — **no** `:worktree`.
- [x] Added `reload-prompts` to `all-mutations` (after `add-prompt-template`).
- [x] Mutation test (`reload-prompts-mutation-output-and-replace-test`): live
      psi-tool `action: "mutate"` invocation with worktree session; asserts
      output keys/values, no `:worktree`, and `:prompt-templates` replaced via
      dispatch (AC5).
- [x] psi-tool visibility test (`reload-prompts-mutation-psi-tool-visible-test`):
      asserts `'psi.extension/reload-prompts` op-name is in
      `mutations/all-mutations` (the aggregate the registered-mutation set
      derives from); the mutate test above proves live invokability (AC5).
- [x] `clj-kondo` clean; focused mutation + psi-tool tests green (5 tests / 17
      assertions).

## Slice 4 — `/prompts-reload` command

- [x] Added `format-prompts-reload` in `commands.clj`: calls
      `session/reload-prompts-in!` (`session` = `psi.agent-session.core`, the
      slice-2 re-export) and reads `:worktree`/`:count` from the return map
      (no separate `session-worktree-path-in` call — the handler return already
      carries `:worktree`); builds a `:text` summary with **worktree + count
      only**, no diagnostics line (AC4).
- [x] Added `"/prompts-reload" :prompts-reload` to `exact-command-handlers`.
- [x] Added `:prompts-reload {:type :text :message (format-prompts-reload ctx
      session-id)}` to the exact-command `case`.
- [x] Added `/help` listing line for `/prompts-reload` (between
      `/reload-models` and `/reload-extension-installs`).
- [x] Command test (`prompts-reload-command-test`): worktree with `foo`/`bar`
      `.md`; asserts `{:type :text}`, "Prompts reloaded", worktree path,
      `count : 2`, and no diagnostics line (AC4). Also added `/prompts-reload`
      to `format-help-includes-all-commands-test` enumeration.
- [x] `clj-kondo` clean; focused command suite green (51 tests / 206
      assertions).

## Slice 5 — docs, changelog, coherence

- [x] Added `CHANGELOG.md` `[Unreleased] → Added` entry: `/prompts-reload`
      command + `psi.extension/reload-prompts` mutation (psi-tool visible),
      re-discovers from `~/.psi/agent/prompts` + `<worktree>/.psi/prompts` and
      replaces the session's templates (AC9).
- [x] Updated `doc/tui.md`: added `/prompts-reload` to the in-session command
      list and a new "Prompt templates" subsection documenting `/prompts` +
      `/prompts-reload` and the mutation (AC9). (No dedicated prompt-template
      doc page exists; `/reload-models` lives in `doc/custom-providers.md`,
      which is provider-specific and not the right home for prompts.)
- [x] End-to-end acceptance proof (AC1–AC3, AC6,
      `reload-prompts-end-to-end-edit-add-delete-test`): worktree
      `<wt>/.psi/prompts` with `foo`/`baz`; after reload (a) editing `foo.md`
      → `foo` template `:content` updates, (b) adding `bar.md` → `bar`
      discoverable, (c) deleting `baz.md` → `baz` removed — all against the
      worktree root.
- [x] `clj-kondo --lint` over all changed src + test paths: 0/0.
- [x] Full `bb test`: green (✅ All tests passed).
- [x] Re-read changed handler/core/mutation/command/docs/changelog (sync) —
      coherent.

## Plan/steps ambiguity follow-ups (2026-06-01)

- [x] P1: Pinned per-file worktree-helper alias. Slice-1 now states
      `session/session-worktree-path-in` (`session` = `psi.session-state.state`;
      noted `ss` = `psi.session-state.init` lacks it); removed the
      `ss`/`session` slash-alternative. Updated plan "Surfaces touched" + the
      "Exact discovery opts" example to `session/`. (`session_settings.clj` /
      `commands.clj` keep `ss/session-worktree-path-in` — `ss` =
      `psi.session-state.state` there — already correct in their slices.)
- [x] P2: Named the single `worktree-path` binding in slice 1; opts
      `:project-prompts-dir` and the `:return :worktree` value both derive from
      it (bare `<path>` placeholder removed; no double `session-worktree-path-in`
      call). Plan opts example shows the `let` binding.
- [x] P3: Resolved slice-3 mutation entry point to `reload-prompts-in!` only;
      dropped the `dispatch!` alternative from the step wording.

## Plan/steps inconsistency follow-ups (2026-06-01)

- [x] I1: Command core-fn surface pinned to **mirror `/reload-models` exactly**.
      Added a `reload-prompts-in!` re-export to `psi.agent-session.core`
      (delegating to `settings/reload-prompts-in!`, paralleling `core.clj:181`);
      added `core.clj` to plan "Surfaces touched" + slice order 2; slice 2 now
      has a re-export step and slice 4 calls `session/reload-prompts-in!`
      (`session` = `psi.agent-session.core`) — exactly as
      `format-reload-models` (`commands.clj:255`) calls `session/reload-models-in!`.
      No direct-`settings/` divergence taken.
- [x] I2: Mutation-idiom contradiction **resolved as no divergence**. The
      premise ("every `prompts.clj` mutation calls `dispatch!` directly") held
      only for `prompts.clj`; the live `reload-models` mutation
      (`mutations/session.clj:283`) — the true reload analog — already calls
      `core/reload-models-in!`, **not** `dispatch!`. So routing `reload-prompts`
      through `core/reload-prompts-in!` mirrors the `reload-models` mutation
      surface; it is not a departure. Slice-3 step now: mirror
      `add-prompt-template` for `::pco/output` shape but `reload-models` for the
      core-fn invocation; mutation passes `agent-session-ctx` as the `ctx` arg
      of `reload-prompts-in! [ctx session-id]`. Plan "Key decisions" records the
      shared-`core.clj`-re-export rationale.

## Test-review follow-ups (2026-06-01)

- [x] T1: Added empty/absent-prompts-dir boundary tests in
      `reload_prompts_test.clj`. New `worktree-without-prompts!` helper (no
      `.psi/prompts` dir) + reused `worktree-with-prompts! {}` (empty dir).
      `reload-prompts-handler-empty-dir-replaces-with-empty-test` seeds a stale
      template, dispatches `:session/reload-prompts`, and asserts
      `:reloaded? true`, `:count 0`, `:prompt-templates` = `[]` (stale gone)
      for both the absent and empty cases.
      `reload-prompts-mutation-empty-dir-count-zero-test` does the same through
      the live psi-tool `mutate` invocation, asserting the mutation's
      `(or count 0)` zero path yields `:psi.prompt-template/count 0` and the
      templates are replaced with `[]`. Focused ns green (8 tests / 36
      assertions, up from 6/—); `clj-kondo` 0/0. Closes the `{boundary}`
      coverage gap.

## Test-review second pass (2026-06-01)

- [x] Re-ran task-test-review independently: `reload-prompts-test` 8/36 green,
      command + help tests 2/31 green. Well-formed, full code-testable AC
      coverage, ¬mock ¬stub. No new actionable findings (see implementation.md
      "Test review — second pass"). Review complete.

## Test-shaper follow-ups (2026-06-01)

- [x] TS1: Trim redundant return-shape proofs. The
      `{:reloaded? true :count 2 :worktree wt}` + `#{"foo" "bar"}` replace is
      asserted in three tests (`…handler-replaces-templates-test`,
      `…in-core-fn-surfaces-return-test`, `…end-to-end…` initial reload). Keep
      one return-shape assertion per entry point (dispatch / core fn / command),
      and reduce the e2e test's initial-reload assertions to the minimum
      pre-edit baseline (its unique value is the AC1–AC3 edit/add/delete delta).
- [x] TS2: Assert "no system-prompt refresh" at the observable boundary instead
      of via `(kernel/handler-entry :session/reload-prompts)` → `(:fn …)` raw-
      handler reach. Either dispatch through `session/dispatch-in!` and assert
      the absence of the refresh side effect observably, or document why the
      pure-result white-box inspection is the only surface that exposes emitted
      effects (meaningful-failures: a handler-result key rename must not break a
      test with no behavior change).
- [x] TS3: Extract compressing helpers for duplicated ceremony:
      `seed-stale!` (the `ss/update-state-value-in! … assoc :prompt-templates
      [{:name "stale" …}]` block, ×3), `invoke-reload-mutation` (null query-fn +
      `make-psi-tool` + `action "mutate"` + `read-string`, ×2 verbatim), and a
      `template-names` helper (×3). Helpers must compress ceremony without
      hiding intent (arrange/act/assert stays explicit).

Done (2026-06-01, this pass):

- [x] TS1: Trimmed the e2e test's initial reload to a single
      `(= #{"foo" "baz"} (template-names …))` baseline (was content + presence
      asserts duplicating `…handler-replaces-templates-test`); its unique
      AC1–AC3 edit/add/delete delta unchanged. Full return-shape proof
      (`:reloaded?`/`:count`/`:worktree`) now lives once per entry point:
      dispatch (`…handler-replaces-templates-test`), core fn
      (`…in-core-fn-surfaces-return-test`). Assertion count 36 → 34.
- [x] TS2: Replaced `…handler-emits-no-effects-test` (raw
      `kernel/handler-entry` → `:fn` → pure-result inspection) with
      `reload-prompts-does-not-refresh-system-prompt-test`: rebinds ctx's
      `:refresh-system-prompt-fn` to a recorder atom, dispatches via
      `session/dispatch-in!`, asserts `(false? @refreshed?)`. The refresh
      callback is the real boundary a `:runtime/refresh-system-prompt` effect
      crosses (dispatch effect-interceptor → `execute-effect-fn` →
      `:refresh-system-prompt-fn`), so a handler-result key rename no longer
      breaks the test absent a behavior change. Dropped the now-unused
      `psi.state-kernel.dispatch` require.
- [x] TS3: Added `seed-stale!` (×3 stale-template seed), `template-names`
      (×3 name-set read), and `invoke-reload-mutation` (×2 null-query-fn +
      psi-tool `mutate` + `read-string`, returns `{:result :parsed}`) helpers;
      hoisted the lone `template-by-name` to the helper block (removed its
      duplicate later defn). All call sites keep explicit arrange/act/assert.
      `clj-kondo` 0/0; focused ns 8 tests / 34 assertions green; command +
      help suite 51/206 green (shared-helper regression check).

## Test-shaper second-pass follow-ups (2026-06-01)

- [x] TS4: Added the real positive control to
      `reload-prompts-does-not-refresh-system-prompt-test`. In the same ctx, the
      test first dispatches `:session/set-prompt-component-selection` (a handler
      that emits exactly one `:runtime/refresh-system-prompt` effect — chosen
      over `:session/set-active-tools`, which also emits
      `:runtime/agent-set-tools`, and `:session/set-skills`, which needs skill
      setup) and asserts the recorder flips to `true` (proving the rebound
      `:refresh-system-prompt-fn` is live and the effect path runs in the test
      ctx). It then resets the recorder and asserts reload leaves it `false`.
      The absence assertion can no longer pass vacuously: a wrong rebind key or
      inert effect path now fails the positive-control `is`. Focused ns green
      (8 tests / 35 assertions, +1); `clj-kondo` 0/0.

## Docs-review follow-ups (2026-06-01)

- [x] Ran review-task-docs independently over `README.md` / `doc/` /
      `CHANGELOG.md`. CHANGELOG `[Unreleased]` entry, `doc/tui.md` command
      list + Prompt templates subsection, and `/help` text are accurate,
      complete, and consistent with the implementation (worktree+count output,
      discovery paths, mutation op-name). No stale references; no removed
      behaviour. No new actionable docs findings (see implementation.md
      "Docs review"). Review complete.

## Code-shaper follow-ups (2026-06-01)

- [x] CS1: Resolved by **renaming the command to `/reload-prompts`** (the
      verb-first, sibling-consistent choice — `λone_way` / `λconsistent`, not a
      design.md rationale carve-out). Changed: command string `"/reload-prompts"`
      + `:reload-prompts` case key + `exact-command-handlers` entry + `/help`
      line (`commands.clj`); `doc/tui.md` command list + Prompt-templates
      subsection; `CHANGELOG.md` `[Unreleased]` entry; `commands_test.clj`
      (`reload-prompts-command-test`, dispatch path, and the
      `format-help-includes-all-commands-test` enumeration). Every internal
      symbol (`:session/reload-prompts`, `psi.extension/reload-prompts`,
      `reload-prompts-in!`) was already verb-first, so only the command
      surface needed alignment.
- [x] CS2: Renamed the private formatter `format-prompts-reload` →
      `format-reload-prompts` (defn + its case-arm call site in `commands.clj`),
      matching `format-reload-models` / `format-reload-extension-installs` and
      the renamed command. `clj-kondo` 0/0; full `bb test` green.

## Code-shaper second-pass follow-ups (2026-06-01)

- [x] Re-ran code-shaper independently over the production surfaces post-CS1/CS2
      rename (handler / settings / core / mutation / command / discover). All
      simple ∧ consistent ∧ robust; naming now uniformly verb-first. The
      in-handler IO and `core/`-vs-`dispatch/` mutation idiom split are the
      documented, already-resolved decisions (design Architectural alignment +
      I2). No new actionable findings (see implementation.md "Code-shaper
      review — second pass"). Review complete.
