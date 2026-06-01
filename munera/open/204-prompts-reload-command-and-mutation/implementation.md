# Implementation notes — 204-prompts-reload-command-and-mutation

## Architecture-fit review (2026-06-01)

Reviewed design.md for architectural fit only (not ambiguity/inconsistency).
Sources: AGENTS.md (VSM, dispatch sequencing, one-way), META.md (canonical
state vs runtime handles), doc/architecture.md (effect ordering, replay,
runtime-handle taxonomy). Cross-checked against the live `reload-models`
surface (session_mutations dispatch handler + `:model-registry/reload`
effect + execute-effect!).

Findings (actionable misfits):

- 🌀 Effect-vs-state kind mismatch. Design says "mirror `model-registry/reload`
  effect shape" and "prefer modeling the disk read as an effect for replay
  fidelity." But `model-registry/reload` mutates an **external runtime handle**
  (the model registry — ctx-owned, mutable, IO; ¬canonical state) and its
  handler returns NO `:root-state-update`. Prompt templates are **canonical
  session state** (`:prompt-templates` in `:state*`, written via
  `:root-state-update`). The two surfaces differ in kind; the model-registry
  analogy misleads. Per the dispatch sequencing contract (handler → apply →
  validate → trim → effects), effects run last, after `:apply` has written
  state, so an effect's *result* cannot feed a `:root-state-update`. A pure
  handler that computes the replaced vector and returns `:root-state-update`
  is the architecturally aligned shape — at the cost of in-handler file IO.

- 🌀 Inverted replay-fidelity justification. Design claims modeling discovery
  as an effect gives "replay fidelity." Replay **suppresses effects** while
  preserving state application (architecture.md). So effect-modeled discovery
  would NOT reproduce templates on replay — the opposite of the stated goal.
  File-IO-derived state is inherently non-replay-deterministic regardless;
  the replay argument should be dropped or corrected, and the IO-on-replay
  consequence stated explicitly for whichever shape is chosen.

- 🌀 If an effect path is still pursued, note the only existing precedent for
  an effect writing canonical state is `mark-flushed` calling
  `apply-root-state-update-in!` from inside the effect (a second apply) — this
  bypasses the pure handler-result model and should be an explicit, justified
  exception, not a silent pattern. The design currently implies effect+state
  is a free, consistent choice; it is not.

One-way / reads-via-resolvers / writes-via-dispatch alignment: OK. No new
shim/adapter: OK (reuses `discover-templates`). Worktree-path discovery
input: correctly identified as session worktree, not cwd — aligned.

## Design-step follow-ups executed (2026-06-01)

Both architecture-fit follow-up design-steps applied to design.md:

- ✅ Effect-vs-state mismatch resolved. Rewrote "Architectural alignment" to
  specify the reload as a **pure `:session/reload-prompts` handler** performing
  `discover-templates` IO inline and returning a `:root-state-update` that sets
  `:prompt-templates`. Removed the "mirror `model-registry/reload` effect shape"
  default; documented the kind difference (external runtime handle vs canonical
  state) and the dispatch sequencing reason (effects run last, so an effect
  result cannot feed `:root-state-update`). Noted `mark-flushed`'s
  second-apply as the only effect-writes-state precedent and an explicit
  exception, not the default.
- ✅ Replay-fidelity rationale corrected. Replaced the "effect for replay
  fidelity" claim with the accurate statement: replay suppresses effects but
  preserves state application, so the last applied `:prompt-templates` value is
  preserved on replay; file-IO-derived state is non-replay-deterministic
  regardless. Dropped the inverted justification.
- Open question #1 (effect vs in-handler) marked resolved in design.md,
  pointing to the Architectural alignment section.

## Ambiguity review (2026-06-01)

Reviewed design.md for ambiguities only (not architecture/inconsistency).
Cross-checked against live code: `app-runtime/build-startup-plan` (startup
`(pt/discover-templates)` call), `prompt_templates/discover-templates` +
`default-config`, `mutations/prompts` (`add-prompt-template` output
convention + `all-mutations`), `dispatch_handlers/prompt_handlers`
(`:session/register-prompt-template` append), `session_mutations`
(`reload-models`, `set-active-tools`/`set-skills` replace shape +
`:runtime/refresh-system-prompt`), and `commands/format-reload-models`.

New actionable ambiguities:

- 🌀 A1 — Discovery-input set underspecified + startup divergence. AC6/Discovery
  inputs require reload to pass worktree-derived `:project-prompts-dir`, but
  startup calls `(pt/discover-templates)` with NO opts (default *process-relative*
  `.psi/prompts`, default global dir). Design neither (a) specifies whether
  reload also passes `:global-prompts-dir` (default vs explicit) nor (b)
  acknowledges that worktree-relative reload **intentionally diverges** from
  cwd-relative startup. For worktree sessions (cwd ≠ worktree) the same edits
  yield different sets at startup vs reload. Pin the full opts map and state the
  divergence as intended.

- 🌀 A2 — `--prompt-template` is a phantom source. "Why" lists CLI
  `--prompt-template` extra-paths as a current discovery source, and OQ#2 defers
  its "persistence across reload." But no `--prompt-template` flag exists in the
  codebase; startup never passes `:extra-paths`. There is nothing to persist or
  drop. Reframe OQ#2 / "Why": extra-paths are not currently wired into startup;
  reload likewise omits them (or note the flag is unimplemented).

- 🌀 A3 — Diagnostics return shape is unresolved either/or. Return shape says
  return `:diagnostics` "(if discover-templates surfaces any — currently it does
  not... Document that prompt discovery has no diagnostics channel, OR add
  minimal error capture)". AC4 promises the command summary shows "any discovery
  diagnostics", which is unreachable if there is no diagnostics channel. Decide:
  no diagnostics (drop from return shape + AC4 summary) vs add capture; do not
  leave both.

- 🌀 A4 — Replace handler shape left open. "Replace, not append" poses an open
  question (dedicated `:session/set-prompt-templates` vs reuse `register` in a
  loop) and states only a "design preference". AC8 fixes the *event* as
  `:session/reload-prompts` but does not say whether that handler does the
  inline replace itself or delegates. Resolve to one shape (preferred: the
  `reload-prompts` handler computes the discovered vector and returns a
  `:root-state-update` replacing `:prompt-templates`, mirroring
  `set-skills`/`set-active-tools` replace handlers).

- 🌀 A5 — Mutation output schema non-committal. Return shape gives output keys
  "e.g. `:psi.prompt-template/reloaded?` / `:count`"; AC5 pins no `::pco/output`.
  Fix the exact mutation output set (mirroring `add-prompt-template`'s
  `:added?`/`:count` → `:reloaded?`/`:count`) and remove "e.g.".

- 🌀 A6 (low-confidence) — System-prompt refresh unspecified. `set-skills` and
  `set-active-tools` emit `:runtime/refresh-system-prompt` because those sets
  feed the system prompt. No system-prompt module enumerates `:prompt-templates`
  (templates are `/name`-invoked), so refresh is likely NOT needed — but the
  design is silent. State explicitly that reload need not emit
  `:runtime/refresh-system-prompt` (and why), or specify it if `/prompts`-style
  surfaces require it.

## Ambiguity-review follow-ups executed (2026-06-01)

All six A1–A6 follow-ups resolved in design.md (code cross-checked, no
blockers). ✅ all six marked done in design-steps.md.

- ✅ A1 — Pinned the exact reload opts map in "Discovery inputs":
  `:global-prompts-dir` passed **explicitly** as `default-config` value,
  `:project-prompts-dir` = `<session-worktree>/.psi/prompts`, `:extra-paths`
  omitted, `:disabled` not passed. Documented the **intentional** worktree-vs-cwd
  divergence from startup (startup `(discover-templates)` uses process-relative
  `.psi/prompts`; reload is worktree-correct). Confirmed against
  `prompt_templates/default-config` (process-relative `:project-prompts-dir`)
  and `app_runtime` startup `(pt/discover-templates)` no-arg call.
- ✅ A2 — `--prompt-template` confirmed **phantom**: only a `prompt_templates.clj`
  doc-comment mentions it; no CLI flag parses it, startup never passes
  `:extra-paths`. Reframed "Why" (dropped CLI source bullet, added unimplemented
  note) and resolved OQ#2 as moot (nothing to persist/drop; reload omits
  extra-paths).
- ✅ A3 — Diagnostics resolved to **no channel**: `discover-templates` returns a
  plain vector with no diagnostics. Dropped `:diagnostics` from the return shape
  and AC4 summary (worktree + count only). Not adding error capture in this task.
- ✅ A4 — Replace-handler shape fixed: `:session/reload-prompts` itself runs
  `discover-templates` inline and returns a single `:root-state-update`
  replacing `:prompt-templates` (mirrors `set-active-tools`/`set-skills`); no
  dedicated `set-prompt-templates` handler, no register-loop. Removed the open
  "preference".
- ✅ A5 — Mutation `::pco/output` pinned to
  `[:psi.prompt-template/reloaded? :psi.prompt-template/count]` with exact return
  map (mirrors `add-prompt-template`); "e.g." removed; AC5 updated to name the
  output set.
- ✅ A6 — Documented **no** `:runtime/refresh-system-prompt`: confirmed no
  system-prompt builder reads `:prompt-templates` (grep). Templates are
  `/name`-invoked, not enumerated in the system prompt; handler emits **no
  effects**. Added a "No system-prompt refresh" subsection.

## Inconsistency review (2026-06-01)

Reviewed design.md for internal inconsistency only (not architecture/ambiguity).
Cross-checked every concrete code claim against the live tree:

- `:session/reload-models` dispatch handler (`dispatch_handlers/session_mutations.clj`):
  returns `:effects [:model-registry/reload]` + `:return-effect-result? true`,
  **no** `:root-state-update` → matches design's "external runtime handle" claim. ✓
- `:session/set-active-tools` / `:session/set-skills`: `:root-state-update`
  wholesale replace + `:runtime/refresh-system-prompt` effect → matches design. ✓
- `:session/register-prompt-template` appends via `(fnil conj [])`
  (`dispatch_handlers/prompt_handlers.clj`) → matches "append" claim. ✓
- `:prompt-templates` is canonical session state written via `session-update`/
  `:root-state-update` → matches. ✓
- `mutations.clj` aggregates `prompts/all-mutations`; adding `reload-prompts`
  to `prompts/all-mutations` makes it psi-tool-visible via the aggregate → matches. ✓
- `add-prompt-template` `::pco/output` `[:added? :count]` → design's
  `[:reloaded? :count]` mirror is consistent. ✓
- `discover-templates` returns a plain vector, no diagnostics channel;
  `:extra-paths` opt exists but is never wired from a CLI flag (`--prompt-template`
  appears only in a doc-comment, line 13) → matches Why/A2/A3 framing. ✓
- `build-startup-plan` calls `(pt/discover-templates)` no-arg (process-relative
  `default-config` `:project-prompts-dir = ".psi/prompts"`); skill discovery
  returns `{:keys [skills diagnostics]}` (has a channel) vs prompt discovery
  (none) → matches startup-divergence + diagnostics-asymmetry claims. ✓
- `/help` text enumerates `/reload-models` + `/reload-extension-installs`;
  `/prompts` command exists → AC2/AC9 conditional references now confirmed true. ✓

Internal AC↔body consistency: diagnostics dropped from both AC4 and Return shape;
OQ#1/#2 resolved consistently with the Architectural-alignment / Discovery-inputs
bodies; global-prompts-dir passed as the same default value (not a divergence) is
consistent with "worktree project dir is the only intentional divergence". ✓

Considered (arguable, NOT actionable): design calls `mark-flushed`
(`dispatch_effects.clj:278`) "the only existing precedent for an **effect**
writing canonical state." `apply-root-state-update-in!` is also called widely in
turn-runtime, but those call sites are core/runtime functions, not effect
handlers — so the design's effect-scoped claim holds and is correctly qualified.

Result: **no new actionable internal inconsistency**. Prior architecture-fit and
ambiguity reviews already resolved the substantive issues; design.md is internally
coherent and consistent with the referenced artifacts.

## Plan/steps ambiguity review (2026-06-01)

Reviewed plan.md + steps.md (not design — already A1–A6/arch/inconsistency
reviewed) for build-time ambiguities. Cross-checked alias mappings and
`dispatch!` return semantics against the live tree.

New actionable ambiguities:

- 🌀 P1 — Worktree-helper alias is conflated across files. `session-worktree-path-in`
  lives **only** in `psi.session-state.state`. Per-file aliases differ:
  `session_mutations.clj` → `session` = `psi.session-state.state` (has it),
  `ss` = `psi.session-state.init` (does **not** have it);
  `session_settings.clj` → `ss` = `psi.session-state.state`,
  `session` = `psi.session-state.model`; `commands.clj` → `ss` =
  `psi.session-state.state`. Steps slice-1 says "confirm the worktree helper
  `ss`/`session/session-worktree-path-in` is already in scope" and plan's
  "Surfaces touched" says "session-settings/`ss` worktree helper" — the
  `ss`/`session` slash-alternative is wrong for `session_mutations.clj` (the
  `ss` variant won't resolve there). Pin the correct per-file alias:
  `session/session-worktree-path-in` in `session_mutations.clj`,
  `ss/session-worktree-path-in` in `session_settings.clj`/`commands.clj`.

- 🌀 P2 — Slice-1 `:return ... :worktree <path>` uses a bare `<path>`
  placeholder. Two candidate forms exist (recompute
  `(session/session-worktree-path-in ctx session-id)` vs reuse a `let`-bound
  worktree value used for the opts `:project-prompts-dir`). Name the single
  binding so opts and the `:worktree` return value derive from the same
  computed path (avoids double IO / divergence).

- 🌀 P3 — Slice-3 mutation entry point left as an either/or. Steps say the
  mutation body "calls the core path (`reload-prompts-in!` **or** `dispatch!`)",
  contradicting the plan/design's single-shared-entry-point decision
  (`reload-prompts-in!` "so the command and mutation share one entry point").
  Resolve to `reload-prompts-in!` only; drop the `dispatch!` alternative.

Verified NOT ambiguous (no step added): the slice-2 "confirm `dispatch!`
surfaces the handler `:return`" risk is sound — `state-kernel/dispatch`
`apply-pure-result` sets `:result (:return pure-result)` and `dispatch!`
returns `(:result result-ictx)`, so `reload-prompts-in!`'s
`(dispatch/dispatch! ...)` yields the `{:reloaded? :count :worktree}` map
directly (no `:return-effect-result?` needed). Mechanism confirmed in code.

## Plan/steps ambiguity-review follow-ups executed (2026-06-01)

P1–P3 (the three plan/steps ambiguity follow-ups) resolved in plan.md +
steps.md (docs-only; no code touched — implementation slices not yet started).

- ✅ P1 — Per-file worktree-helper alias pinned. Slice-1 wording now uses
  `session/session-worktree-path-in` (`session` = `psi.session-state.state`),
  noting the `ss` alias in `session_mutations.clj` (= `psi.session-state.init`)
  lacks it. Removed the `ss`/`session` slash-alternative from slice 1 and the
  plan "Surfaces touched" note + the "Exact discovery opts" example.
  `session_settings.clj` / `commands.clj` keep `ss/session-worktree-path-in`
  (`ss` = `psi.session-state.state` in those nss) — already correct.
- ✅ P2 — Single `worktree-path` `let` binding named in slice 1; opts
  `:project-prompts-dir` and the `:return :worktree` value both derive from it.
  Removed the bare `<path>` placeholder; plan opts example shows the binding.
  Avoids a second `session-worktree-path-in` call.
- ✅ P3 — Slice-3 mutation entry point resolved to `reload-prompts-in!` only;
  dropped the `dispatch!` either/or, consistent with the plan/design's single
  shared entry point.

No blockers. Design unchanged (read-only context). Remaining work = the
implementation slices 1–5, unchanged by these clarifications.

## Plan/steps inconsistency review (2026-06-01)

Reviewed plan.md + steps.md for **cross-file inconsistencies** (plan ↔ steps ↔
design, and vs the live mutation/command idioms). Cross-checked
`mutations/prompts.clj`, `session_settings.clj`, `commands.clj`, and
`core.clj` against the plan's "mirror existing surfaces" claims.

New actionable inconsistencies:

- 🌀 I1 — Command surface omits the required `core.clj` re-export. Plan
  "Surfaces touched" + steps slice 4 say `format-prompts-reload` should "call
  the core reload fn" **mirroring `format-reload-models`**. But the live
  `format-reload-models` (`commands.clj:255`) calls
  `session/reload-models-in!` where `session` = **`psi.agent-session.core`**
  (commands.clj alias, line 30), and `core.clj:181` **re-exports**
  `reload-models-in!` delegating to `settings/reload-models-in!`. The plan
  only adds `reload-prompts-in!` to `session_settings.clj` (slice 2) and lists
  surfaces `session_settings.clj / mutations/prompts.clj / commands.clj /
  CHANGELOG / docs` — **`core.clj` is missing**. To mirror `/reload-models`
  the command needs a `reload-prompts-in!` re-export in `psi.agent-session.core`
  (paralleling `core.clj:181`); otherwise slice 4 must call `settings/` (or
  add a `session-settings` alias) directly, **diverging** from the
  `format-reload-models` idiom. Resolve: add `core.clj` to "Surfaces touched"
  + a slice-2/4 step adding the `core.clj` re-export, OR explicitly state the
  command calls `session-settings` directly and note the divergence.

- 🌀 I2 — Mutation invocation idiom contradicts "mirror `add-prompt-template`".
  Design pins the mutation `::pco/output` "mirroring `add-prompt-template`"
  (design L142) and plan/steps mandate the mutation body call the shared
  `reload-prompts-in!` core fn (P3 resolution; plan L64–66). But **every**
  existing mutation in `mutations/prompts.clj` — including `add-prompt-template`
  — calls `dispatch/dispatch!` **directly** with `agent-session-ctx`; **none**
  routes through a `session_settings.clj` `*-in!` core fn. So the mutation
  body deliberately diverges from the established `prompts.clj` idiom that the
  output-shape instruction tells the builder to mirror. The task files never
  acknowledge this departure, leaving a builder a genuine contradiction
  ("mirror add-prompt-template" ⇒ call `dispatch!` directly vs "share one
  entry point" ⇒ call `reload-prompts-in!`). Resolve: keep the
  `reload-prompts-in!` shared-entry decision but add an explicit note in
  plan/steps that this is an intentional divergence from the direct-`dispatch!`
  mutation idiom (mutation passes `agent-session-ctx` as the `ctx` arg of
  `reload-prompts-in! [ctx session-id]`).

Verified consistent (no step added):
- Per-file worktree-helper aliases (P1 resolution) match the live nss:
  `session_mutations.clj` `session`=`state`/`ss`=`init`;
  `session_settings.clj` `ss`=`state`/`session`=`model`;
  `commands.clj` `ss`=`state`. ✓
- `reload-models` handler returns `:return-effect-result? true` with no
  `:root-state-update` (session_mutations L282–287) → plan's risk note +
  design "external runtime handle" framing accurate. ✓
- `set-skills`/`set-active-tools` replace shape (`:root-state-update` +
  `:runtime/refresh-system-prompt` + `:return`) matches the design's cited
  replace-handler model. ✓
- `add-prompt-template` `::pco/output [:added? :count]` and
  `::pco/params [:psi/agent-session-ctx :session-id :template]` → steps slice-3
  `[:reloaded? :count]` / `[:psi/agent-session-ctx :session-id]` mirror is
  consistent. ✓
- `discover-templates` plain-vector / no-diagnostics matches AC4 + return
  shape. ✓

Result: **two new actionable cross-file inconsistencies (I1, I2)** — both about
mirroring existing reload surfaces (command core re-export; mutation invocation
idiom). Added as slice-2/4 follow-up steps.

## Plan/steps inconsistency-review follow-ups executed (2026-06-01)

I1 + I2 (the two plan/steps inconsistency follow-ups) resolved in plan.md +
steps.md (docs-only; implementation slices not yet started, no code touched).
Design unchanged (read-only context).

- ✅ I1 — Command core-fn surface pinned to **mirror `/reload-models` exactly**.
  Chose the `core.clj` re-export option (not the `settings/`-direct divergence).
  Plan: added `core.clj` to "Surfaces touched", a "single shared entry point via
  core.clj re-export" key decision, and slice-2 now names the settings fn + the
  core re-export. Steps: slice-2 gained a `psi.agent-session.core`
  `reload-prompts-in!` re-export step (mirrors `core.clj:181`); slice-4 command
  now calls `session/reload-prompts-in!` (`session` = `psi.agent-session.core`),
  exactly as `format-reload-models` (`commands.clj:255`).

- ✅ I2 — Mutation-idiom "divergence" resolved as **not a divergence**. Verified
  against the live tree: the `reload-models` *mutation*
  (`mutations/session.clj:283`) already calls `core/reload-models-in!` directly
  (not `dispatch!`), and `send-prompt` (`prompts.clj:130`) calls a core fn
  (`ext-rt/send-extension-prompt-in!`). The "every mutation calls dispatch!"
  premise held only for the `add-prompt-template`/`register-*` mutations in
  `prompts.clj` (which simply have no shared core fn). So routing
  `reload-prompts` through `core/reload-prompts-in!` mirrors the closest analog
  (`reload-models` mutation), and the earlier "intentional divergence" framing
  is wrong. Steps slice-3 now: mirror `add-prompt-template` for `::pco/output`
  shape, `reload-models` for core-fn invocation; mutation passes
  `agent-session-ctx` as the `ctx` arg of `reload-prompts-in! [ctx session-id]`.

Net effect: the command and mutation share **one** `core/reload-prompts-in!`
re-export, matching the existing `reload-models` surface pair precisely. No
blockers; remaining work = implementation slices 1–5, unchanged in structure.
