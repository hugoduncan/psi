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
