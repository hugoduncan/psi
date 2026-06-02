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

## Implementation — Slices 1 & 2 (2026-06-01)

Built the reload handler + core entry fn.

- **Slice 1** — `:session/reload-prompts` handler in
  `dispatch_handlers/session_mutations.clj` (added `[psi.prompt-assets.prompt-templates :as pt]`
  require). Single `worktree-path` `let` binding → opts
  `{:global-prompts-dir (:global-prompts-dir pt/default-config)
    :project-prompts-dir (str worktree-path "/.psi/prompts")}` → inline
  `(pt/discover-templates opts)`. Returns `:root-state-update`
  (`session/session-update` wholesale `assoc :prompt-templates discovered`) +
  `:return {:reloaded? true :count … :worktree …}`. **No `:effects`** (templates
  are `/name`-invoked, not enumerated in the system prompt).
- **Slice 2** — `reload-prompts-in!` in `session_settings.clj`
  (`dispatch! … :session/reload-prompts … {:origin :core}`) + a `core.clj`
  re-export delegating to `settings/reload-prompts-in!`. Both mirror the
  `reload-models-in!` settings-fn + core re-export pair.

Tests (`reload_prompts_test.clj`, 3 tests / 11 assertions):
- replace semantics (seeded `stale` template gone; discovered `foo`/`bar`
  present with new content) + return shape;
- no-effects (direct handler call: `(nil? (:effects result))`);
- `reload-prompts-in!` core fn surfaces the `:return` map (confirms `dispatch!`
  return semantics — no `:return-effect-result?` needed; `apply-pure-result`
  sets `:result (:return pure-result)`).

Verification: `clj-kondo` 0/0 over the 4 changed files; focused suite green.

Test-setup notes:
- `session-at-worktree` creates the session with `{:worktree-path wt}` and
  `:cwd wt`, so `session-worktree-path-in` resolves to the temp worktree (not
  process cwd), exercising AC6's worktree-correct discovery root.
- Stale template seeded via `ss/update-state-value-in!` on
  `(ss/session-data-path session-id)` (no `update-session-data-in!` helper
  exists).

## Implementation — Slice 3 (2026-06-01)

`psi.extension/reload-prompts` mutation in `mutations/prompts.clj`:
`::pco/output [:psi.prompt-template/reloaded? :psi.prompt-template/count]`
(mirrors `add-prompt-template`'s `[:added? :count]` shape); body calls
`core/reload-prompts-in!` (added `[psi.agent-session.core :as core]` require —
no circular dep; `mutations/session.clj` already requires `core` for
`reload-models-in!`). Does **not** surface `:worktree`. Added to `all-mutations`.

Tests: live psi-tool `action: "mutate"` invocation
(`reload-prompts-mutation-output-and-replace-test`) — ctx built with
`:mutations mutations/all-mutations` (the create-context opt key; **not**
`:all-mutations` — that's the internal stored key). Asserts output map,
absent `:worktree`, dispatch replace. Visibility test reads op-name via
`(:config m)`/`meta` `::pco/op-name` (mirroring psi_tool's `mutation-op-name`).

Deviation note: the visibility assertion checks membership in
`mutations/all-mutations` (the aggregate `registered-mutation-syms` derives
from) rather than re-deriving the registered set; the live mutate test already
exercises the full registered-mutation resolution path end-to-end.

## Implementation — Slice 4 (2026-06-01)

`/prompts-reload` command in `commands.clj`: `format-prompts-reload` calls
`session/reload-prompts-in!` (core re-export) and reports worktree + count from
the **return map** (deviation from `format-reload-models`, which reads worktree
separately via `ss/session-worktree-path-in` then count from the return —
prompts' `reload-prompts-in!` already returns `:worktree`, so a separate
worktree read would be redundant). No diagnostics line (AC4). Wired into
`exact-command-handlers`, the exact-command `case`, and `/help`.

Tests: `prompts-reload-command-test` (worktree `.psi/prompts/{foo,bar}.md`,
asserts text/worktree/`count : 2`/no-diagnostics); extended
`format-help-includes-all-commands-test` with `/prompts-reload`.

## Implementation — Slice 5 (2026-06-01)

Docs + changelog + coherence. CHANGELOG `[Unreleased] → Added` entry for the
command + mutation. `doc/tui.md`: `/prompts-reload` added to the in-session
command list + a new "Prompt templates" subsection (documents `/prompts`,
`/prompts-reload`, and the `psi.extension/reload-prompts` mutation).

Doc-placement note: no dedicated prompt-template user doc page exists.
`/reload-models` is documented only in `doc/custom-providers.md` (provider-
specific) — not the right home for prompt reload. `doc/tui.md`'s in-session
commands section is the discoverable home alongside `/prompts`.

End-to-end acceptance proof `reload-prompts-end-to-end-edit-add-delete-test`
covers AC1 (edit→content updates), AC2 (add→discoverable), AC3
(delete→removed), AC6 (worktree root), all via `reload-prompts-in!` against a
temp `<wt>/.psi/prompts`.

Verification: `clj-kondo` 0/0 over all changed src+test; full `bb test` green.

## Task complete (2026-06-01)

All five slices implemented; all `steps.md` items checked. Surfaces:
- `:session/reload-prompts` pure dispatch handler (in-handler discovery IO,
  `:root-state-update` wholesale replace, no effects).
- `reload-prompts-in!` settings fn + `core.clj` re-export.
- `psi.extension/reload-prompts` mutation (psi-tool visible).
- `/prompts-reload` command + `/help` listing.
- CHANGELOG + `doc/tui.md` updates.
Tests: `reload_prompts_test.clj` (6 tests / 22 assertions) + command/help tests
in `commands_test.clj`. Full `bb test` green. AC1–AC9 all satisfied.

## Implementation review (2026-06-01)

Reviewed the built code (not design/plan — already reviewed) against design.md
and architecture: design-fit, one-way/architecture, new-vs-reusable patterns,
unnecessary abstraction, structural perf. Sources: AGENTS.md (VSM, dispatch
sequencing, one-way), design.md, and the live tree.

Verified (no actionable findings):
- ✅ Design-fit. Handler (`session_mutations.clj:289-301`), mutation
  (`mutations/prompts.clj:34-45` + `all-mutations`), command
  (`commands.clj:268-276`, `exact-command-handlers`, `case`, `/help`), core
  re-export (`core.clj:187-192`), settings fn (`session_settings.clj:157-162`)
  all match design exactly. Discovery opts identical to the design's pinned map
  (`:global-prompts-dir` explicit from `default-config`,
  `:project-prompts-dir` = `<worktree>/.psi/prompts`, no `:extra-paths`/`:disabled`).
- ✅ Architecture. Writes via `:session/reload-prompts` dispatch; pure handler
  with in-handler discovery IO returning a single `:root-state-update` wholesale
  replace (mirrors `set-skills`/`set-active-tools`); no effects (templates are
  `/name`-invoked, not system-prompt-enumerated); reuses `discover-templates`
  (no new shim/adapter). Command/mutation share one `core/reload-prompts-in!`
  re-export, mirroring the `reload-models` surface pair.
- ✅ No reinvented pattern, no unnecessary abstraction — the thin settings-fn +
  core re-export is the established reload idiom, not gratuitous.
- ✅ Robustness. `discover-template-files` returns empty for absent dirs
  (`(.exists d)` guard) — reload of a worktree with no `.psi/prompts` yields an
  empty set, no crash.
- ✅ Perf. Synchronous disk IO on the dispatch thread is manual/infrequent and
  mirrors `/reload-models`; acknowledged in plan risks. Not a structural issue.
- ✅ Tests. `reload_prompts_test.clj` (6 deftests) covers AC1–AC9 incl. the live
  psi-tool `action: "mutate"` resolution path (AC5) and worktree-rooted
  edit/add/delete (AC1–AC3, AC6). `clj-kondo` 0/0 over changed files;
  `bb test` green (re-run).

Result: no new actionable implementation-level feedback. Implementation is
high-quality, design-faithful, architecturally aligned, and well-tested.

## Test review (2026-06-01)

Reviewed `reload_prompts_test.clj` (6 deftests / 22 assertions) + the
`commands_test.clj` `/prompts-reload` test against the task-test-review
criteria: well-formedness, behaviour↔test coverage of the design ACs, and
infra-deps injectable/nullable (¬mock ¬stub). Re-ran focused suite green
(6 tests / 22 assertions, 0 failures).

Verified (no actionable finding):
- ✅ Well-formed. Deterministic, temp-dir isolated (`createTempDirectory`),
  cleaned up via `delete-tree!` in `finally`. No global registry leakage.
- ✅ ¬mock ¬stub ¬with-redefs in the prompt-reload tests. `(fn [_q] {})` is a
  real null query-fn for the unused-on-mutate query dependency (the mutate path
  uses real dispatch), not a mock of the unit under test. Real `discover-templates`
  IO, real dispatch, real psi-tool `action: "mutate"` resolution.
- ✅ AC coverage: AC1/AC2/AC3/AC6 (end-to-end edit/add/delete, worktree-rooted),
  AC4 (command text worktree+count, no diagnostics), AC5 (mutation output keys +
  psi-tool registered-set visibility + live mutate), AC7 (replace: seeded `stale`
  gone), AC8-write (state via `:session/reload-prompts` dispatch). No-effects test
  guards the "no system-prompt refresh" decision (`(nil? (:effects result))`).

Actionable finding:
- ❌ T1 (boundary coverage gap) — No test covers reload against a worktree with
  **no / empty `.psi/prompts`** (zero discovered templates). Every test seeds
  ≥1 `.md`. The implementation review itself cites empty-dir handling as a
  robustness behaviour (`discover-template-files` returns empty for absent dirs,
  "no crash"), and the mutation's `(or count 0)` zero-fallback is never
  exercised. Per the Test formalism (`{nominal, edge, boundary}`) the zero/empty
  boundary is missing. Add a handler+mutation test: seed a stale template,
  reload a worktree whose `.psi/prompts` is absent/empty → `reloaded? true`,
  `count 0`, `:prompt-templates` replaced with `[]` (also proves AC7
  replace-to-empty and the mutation `count 0` path).

Considered, NOT actionable:
- AC8 read-via-resolver: tests read `:prompt-templates` via direct
  `ss/get-session-data-in`, never through a resolver. Low-confidence — resolver
  reads of `:prompt-templates` are pre-existing (not added by this task), so the
  read-side of AC8 is out of scope for this task's tests. No step added.

## T1 boundary-coverage follow-up executed (2026-06-01)

- ✅ T1 closed. Added two boundary tests to
  `components/agent-session/test/psi/agent_session/reload_prompts_test.clj`,
  each parameterised over `["absent" worktree-without-prompts!]` and
  `["empty"  (worktree-with-prompts! {})]`:
  - `reload-prompts-handler-empty-dir-replaces-with-empty-test` — seeds a stale
    `:prompt-templates`, dispatches `:session/reload-prompts`, asserts
    `:reloaded? true`, `:count 0`, and `:prompt-templates` = `[]` (stale gone;
    proves AC7 replace-to-empty for the zero-discovery boundary, no crash on
    absent dir).
  - `reload-prompts-mutation-empty-dir-count-zero-test` — same scenario through
    the live psi-tool `mutate` invocation; asserts the mutation `:psi-tool/result`
    is `{:psi.prompt-template/reloaded? true :psi.prompt-template/count 0}`
    (exercises the previously-uncovered `(or count 0)` zero fallback) and the
    session's `:prompt-templates` is replaced with `[]` via dispatch.
  - New `worktree-without-prompts!` helper creates a temp worktree with **no**
    `.psi/prompts` dir; the empty case reuses `worktree-with-prompts! {}`.
  - `clj-kondo --lint` on the test file: 0/0. Focused ns green: 8 tests / 36
    assertions (was 6). No production code change required — the handler already
    returns `[]` for absent/empty dirs and the mutation already has the
    `(or count 0)` guard; this only closes the missing-coverage gap.

## Test review — second pass (2026-06-01)

Re-ran the task-test-review skill independently against the current tree
(criteria: well-formed, ∀b∈behaviour(design)∃t covering, infra-deps
injectable/nullable ∧ ¬mock ∧ ¬stub). Read `reload_prompts_test.clj` (8
deftests / 36 assertions) + `commands_test.clj` `prompts-reload-command-test` +
`format-help-includes-all-commands-test`. Ran focused suites:
`reload-prompts-test` 8/36 green; command + help tests 2/31 green.

Verified (no new actionable finding):
- ✅ Well-formed. Deterministic, temp-worktree isolated
  (`createTempDirectory`), cleaned in `finally`; real `discover-templates` IO,
  real dispatch, real psi-tool `mutate` resolution.
- ✅ ¬mock ¬stub ¬with-redefs in the prompt-reload tests. `(fn [_q] {})` is a
  real null query-fn for the unused-on-mutate query dependency, not a mock of
  the unit under test.
- ✅ AC coverage complete for code-testable ACs: AC1/2/3/6 (end-to-end
  edit/add/delete, worktree-rooted), AC4 (command text worktree+count, no
  diagnostics), AC5 (mutation output keys + live mutate + all-mutations
  membership), AC7 (replace: seeded `stale` gone, incl. T1 replace-to-empty
  boundary), AC8-write (state via `:session/reload-prompts` dispatch). No-effects
  test guards the "no system-prompt refresh" decision.
- ✅ T1 boundary (empty/absent `.psi/prompts`) closed in the prior pass and
  passing (handler + mutation `(or count 0)` path).

Considered, NOT actionable (consistent with prior pass):
- AC8 read-via-resolver: tests read `:prompt-templates` via direct
  `ss/get-session-data-in`, not a resolver. Resolver reads of
  `:prompt-templates` are pre-existing (not added by this task); read-side AC8
  is out of scope for this task's tests.
- `psi-tool-visible-test` asserts `all-mutations` membership rather than
  re-deriving the registered set; the live `mutate` test already exercises the
  full registered-mutation resolution path end-to-end. Adequate.
- Command test sets `:cwd` = `:worktree-path`, so it doesn't exercise the
  worktree≠cwd divergence in the command path; the handler + end-to-end tests
  already cover worktree-correct discovery (AC6). Adequate.

Result: **no new actionable test-level feedback.** Tests are well-formed,
cover every code-testable design AC, and use real infrastructure without
mocks/stubs. Test review complete.

## Test-shaper review (2026-06-01)

Applied test-shaper (clarity ∧ signal ∧ robustness ∧ economical) to
`reload_prompts_test.clj` (8 deftests) + `commands_test.clj`
`prompts-reload-command-test`. Prior passes covered well-formedness, AC
coverage, ¬mock. This pass targets *shape*: redundancy, white-box coupling,
ceremony. Distinct from the prior task-test-review findings (T1 closed; cwd-
divergence + resolver-read already dismissed).

Actionable (shape):

- ❌ TS1 (economical — redundant return-shape assertions). The
  `{:reloaded? true :count 2 :worktree wt}` triple + `#{"foo" "bar"}` replace
  is asserted in **three** tests: `…handler-replaces-templates-test`,
  `…in-core-fn-surfaces-return-test`, and `…end-to-end-edit-add-delete-test`'s
  initial reload. The three entry points (raw dispatch, core fn, command path)
  warrant *one* return-shape assertion each, but the full discover→replace
  proof is re-run redundantly. The end-to-end test's *initial* reload duplicates
  the handler-replace test; only its edit/add/delete delta (AC1–AC3) is unique.
  Trim the initial-reload assertions in the e2e test to the minimum needed to
  establish the pre-edit baseline.

- ❌ TS2 (behavior-focused — white-box handler reach). `…emits-no-effects-test`
  pulls the raw handler fn via `(kernel/handler-entry :session/reload-prompts)`
  → `(:fn …)` and asserts on the handler's pure-result map shape
  (`:effects`/`:root-state-update`/`:return`). This couples the test to the
  kernel handler-registry internals rather than the dispatch surface every
  other test uses. The *decision under guard* (no system-prompt refresh) is
  real and worth a test, but assert it at the observable boundary — e.g.
  dispatch through `session/dispatch-in!` and assert no system-prompt rebuild
  observable side effect — or, if the pure-result must be inspected, document
  why the white-box reach is the only surface that exposes "emitted effects".
  As written, a refactor of the handler-result key names breaks the test
  without a behavior change (meaningful-failures violation).

- ❌ TS3 (simple/consistent — duplicated ceremony, no compressing helpers).
  Three patterns are copy-pasted verbatim across tests with no helper:
  (a) stale-seed `(ss/update-state-value-in! ctx (ss/session-data-path sid)
  assoc :prompt-templates [{:name "stale" :content "old"}])` (×3);
  (b) the null query-fn `(fn [_q] {})` + `make-psi-tool` + `(:execute tool)`
  `action "mutate"` invocation + `read-string` parse (×2, verbatim);
  (c) `(set (map :name (:prompt-templates (ss/get-session-data-in …))))` (×3).
  Add `seed-stale!`, `invoke-reload-mutation` (returns parsed result), and a
  `template-names` helper (alongside the existing `template-by-name`). These
  compress ceremony without hiding intent and make the arrange/act/assert
  structure explicit per the simple(tests) lens.

Considered, NOT actionable:
- `prompts-reload-command-test` cwd=worktree (no divergence exercise) — already
  raised and dismissed by the prior pass (handler/e2e cover AC6). No new step.
- `(fn [_q] {})` null query-fn — a real null dependency, not a mock; correct
  per ¬mock. The TS3 helper only de-duplicates it, doesn't change its nature.

Result: three shape-level follow-ups (TS1 redundancy, TS2 white-box reach,
TS3 ceremony helpers). None block correctness; all improve change-confidence.

## Test-shaper follow-ups executed (2026-06-01)

Applied TS1–TS3 from the test-shaper review pass to
`reload_prompts_test.clj`.

- **TS1 (redundancy)** — The e2e `…edit-add-delete-test` initial reload no
  longer re-proves discover→replace + return shape (covered once each by the
  dispatch and core-fn tests); it now asserts only the
  `#{"foo" "baz"}` pre-edit baseline it needs to delta the AC1–AC3 changes
  against. Net: 36 → 34 assertions, no coverage loss.

- **TS2 (white-box reach → observable boundary)** — `…emits-no-effects-test`
  reached the raw handler via `kernel/handler-entry` → `:fn` and asserted on
  the pure-result map keys (`:effects`/`:root-state-update`/`:return`),
  coupling the test to handler-registry internals. Replaced with
  `…does-not-refresh-system-prompt-test`, which rebinds ctx's
  `:refresh-system-prompt-fn` to a recording atom and dispatches through
  `session/dispatch-in!`. Rationale for this being *the* observable boundary:
  a `:runtime/refresh-system-prompt` effect, if emitted, is executed by the
  dispatch effect-interceptor (`state-kernel/dispatch.clj` `effect-interceptor`)
  via `:execute-effect-fn` → `dispatch-effects/execute-effect!`
  (`:runtime/refresh-system-prompt` defmethod) → `(:refresh-system-prompt-fn
  ctx)`. So the recorder is exactly where the side effect would surface. The
  guarded decision (reload must not rebuild the system prompt) now fails only
  on a real behavior change, not a handler-result key rename. Dropped the
  unused `psi.state-kernel.dispatch` require.

- **TS3 (ceremony helpers)** — Added `seed-stale!` (stale-template seed, ×3),
  `template-names` (name-set read, ×3), and `invoke-reload-mutation`
  (null query-fn + `make-psi-tool` + `mutate` action + `read-string`,
  returns `{:result :parsed}`, ×2 verbatim before). Hoisted `template-by-name`
  into the helper block and removed its duplicate mid-file defn. The
  null query-fn `(fn [_q] {})` stays a real null dependency (¬mock) — the
  helper only de-duplicates it. Arrange/act/assert remains explicit at every
  call site.

Verify: `clj-kondo` 0/0; `clojure -M:test --focus
psi.agent-session.reload-prompts-test` → 8 tests / 34 assertions / 0 failures;
`--focus psi.agent-session.commands-test` → 51 / 206 / 0 (shared-helper
regression guard, no shared code touched there). All three shape follow-ups
closed; no behavior change to the production reload path.

## Test-shaper review — second pass (2026-06-01)

Re-applied test-shaper (clarity ∧ signal ∧ robustness ∧ economical) to the
current `reload_prompts_test.clj` (8 deftests / 34 assertions) +
`commands_test.clj` `prompts-reload-command-test` / `format-help-includes-all-
commands-test`. Confirmed the prior TS1–TS3 follow-ups are in the tree
(e2e baseline trimmed; observable-boundary refresh test via recorder atom;
`seed-stale!`/`template-names`/`invoke-reload-mutation`/`template-by-name`
helpers). Focused ns green (8/34). Cross-checked the TS2 boundary claim against
`dispatch_effects.clj:199` (`:runtime/refresh-system-prompt` →
`(:refresh-system-prompt-fn ctx)`) and `context.clj:193-194` (default ctx wires
`:execute-effect-fn` → real `execute-effect!`): the recorder is the genuine
effect boundary and the dispatch path runs effects in the test ctx. ✓

Actionable (signal / meaningful-failures, low confidence):

- ❌ TS4 — `reload-prompts-does-not-refresh-system-prompt-test` is an absence
  assertion (`(false? @refreshed?)`) with **no in-test positive control**
  proving the rebound `:refresh-system-prompt-fn` recorder would actually fire
  if a `:runtime/refresh-system-prompt` effect were emitted. If the rebind key
  were wrong, or the test ctx's effect path were inert, the assertion would
  pass vacuously — the test would still go green even if reload *did* (or a
  future regression *started to*) refresh. The boundary is currently verified
  only by code-reading (this note), not by the test itself. Add a minimal
  positive control so the recorder's liveness is proven where the absence is
  asserted — e.g. an `is` that, in the same ctx, an event known to refresh
  (`:session/set-skills`/`:session/set-active-tools`) flips the recorder to
  `true`, **then** assert reload leaves it `false`. Tension to weigh when
  executing: this adds an unrelated event to a `single_concern` test and mild
  `minimal_incidental_setup` cost; if the positive control is judged to dilute
  the test's focus more than it strengthens signal, the alternative is a short
  in-test comment asserting (via the verified code path) why the recorder is
  live — but a real positive-control assertion is the stronger fix.

Considered, NOT actionable (re-confirmed):
- TS1 return-shape "redundancy": one return-shape proof per entry point
  (dispatch / core-fn / mutation) is intentional, not redundant — settled.
- Two mutation tests share a 4-line success-envelope preamble
  (`:is-error`/`:overall-status`/full `:psi-tool/result`/replace) via the
  shared `invoke-reload-mutation` helper; counts differ (2 vs 0 — the actual
  behavior under test). Duplication is minimal and intent-revealing; a further
  helper would hide intent. No step.
- `prompts-reload-command-test` `"count : 2"` string assertion mirrors the
  `format-reload-models` convention (`commands.clj:261/265/275`); consistent,
  not brittle relative to the established surface. No step.

Result: one new low-confidence shape follow-up (TS4 positive control for the
absence assertion). Does not block correctness.
