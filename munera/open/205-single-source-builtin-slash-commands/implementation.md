# Implementation notes

## Reviews

### 2026-06-01 — Architecture-fit review (review-task-architecture), pass 1

Scope: architectural fit only (¬correctness, ¬clarity, ¬ambiguity, ¬consistency).
Sources consulted: `AGENTS.md`, `META.md`, `doc/architecture.md`, plus the
existing `extension-commands-resolver` (`resolvers/extensions.clj`) and
`commands.clj` `builtin-command-names` / routing maps for precedent.

Strong fit overall:
- Mirrors the established `:psi.extension/command-names` resolver precedent;
  both TUI (`tui/app/support.clj`) and Emacs (`psi-session-commands.el`) already
  consume that attribute via EQL — design adds one more attribute to queries
  they already make. Satisfies `λ one_way`, `single_source_of_truth`, and the
  architecture convergence rule ("backend answers it once; adapters differ only
  in rendering").
- Resolver placement in `agent-session` (alongside the routing maps) is correct
  and consistent: `extension-commands-resolver` lives there too, `app-runtime`
  hosts no resolvers, and co-locating the spec table with its routing authority
  keeps the coherence invariant local. Moving it to `app-runtime` would split
  the spec from the routing maps it must equal — rejected.
- No shim/adapter introduced; aligns with `λ shims_adapters`.
- Bare-name (no leading `/`) resolver shape matches `:psi.extension/command-names`.

One actionable architectural-fit misfit (see design-steps.md A1):
- A1 — Drift-prevention is *forbidden* (test fails) rather than *unreachable*
  (structurally impossible). The design's stated preference (open question #1)
  keeps routing+description as two parallel maps and enforces
  `set(spec-names) == set(routed-names)` **by test**. This conflicts with the
  project's structural-invariant ethos: `λ shape. unreachable > forbidden`,
  `impossible_invalid_states`, and
  `λ robust ... shaped_by(code, formalisms) → enforceable(invariants(code))`.
  A single table where each command entry carries both its handler/routing and
  its description (so name divergence is structurally impossible) better fits
  the architecture. The design already frames this as an open question; the
  architectural principle favours the single-source-of-keys option over
  "minimal blast radius." Plan should weigh `unreachable > forbidden` explicitly
  rather than defaulting to the test-enforced variant.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Design-steps follow-up

### 2026-06-01 — A1 (architecture-fit follow-up): single keyed spec table

Completed A1. Grounded the decision in the actual routing shape:
`exact-command-handlers` is a `name → handler-keyword` **map**;
`prefixed-command-prefixes` is a prefix **vector** dispatched via a hardcoded
`case` in `dispatch-prefixed-command`; `builtin-command-names` is already a
derived `concat`.

design.md changes:
- Replaced "One spec table, two derivations" decision with "Single spec table is
  the source of names (unreachable > forbidden)" — evaluates Option A (parallel
  table + coherence test = drift *forbidden*) vs Option B (single keyed spec
  table = drift *unreachable*), and **chooses Option B** on `unreachable >
  forbidden` / `impossible_invalid_states` grounds rather than blast-radius.
- Routing maps reframed as *derived projections* of the keyed spec table; names
  exist in exactly one place (the table keys), so name divergence is
  unrepresentable.
- Updated Scope "Derivation, not duplication", AC2, "Architectural alignment",
  and resolved open question #1 to match.

Honest residual scoping recorded in design: prefixed-command *handler* wiring
still lives in the `dispatch-prefixed-command` `case`. Option B removes name
drift (prefix vector becomes a projection) but a prefix could be in the table
yet lack a `case` branch — that is handler-wiring coherence, narrower than the
name drift this task fixes, unchanged from today, and explicitly out of scope.
Optionally covered by a narrow branch-coherence test (left to plan).

No blocking reasons; A1 fully addressed at design level (plan/code stages will
implement the projections).

### 2026-06-01 — Ambiguity review (review-task-design ambiguity pass), pass 1

Scope: ambiguities only (¬architecture, ¬correctness, ¬consistency). Read
design.md + design-steps.md (A1 resolved) and grounded against real code:
`commands.clj` `exact-command-handlers` (664), `prefixed-command-prefixes`
(682), `builtin-command-names` (715), `dispatch-prefixed-command` (697),
`format-help` (111), and `extension-commands-resolver` precedent.

Five new actionable ambiguities (B1–B5 in design-steps.md):

- B1 — **Dual-kind command unrepresentable.** `/project-repl` is in BOTH
  `exact-command-handlers` (`:project-repl`) AND `prefixed-command-prefixes`
  today; `dispatch*` tries exact first, then the prefixed `case` (both have a
  `/project-repl` branch). Option B's single keyed entry carries one
  `:dispatch`/`:handler` field — it cannot express "exact AND prefixed" without
  a rule. Design must specify: does an entry carry a set/either of kinds, which
  projection(s) it feeds, and which dispatch path wins? Unspecified → the
  projection of `exact-command-handlers`/`prefixed-command-prefixes` is
  ambiguous for this command.

- B2 — **Spec-table key form still "pick one."** Scope says key "without leading
  slash, or with — pick one and apply uniformly," yet "Slash prefix
  normalization" fixes only the *resolver output* (bare). The table-key form
  (Option B examples use `/`-prefixed keys; `strip-slash` is applied in the
  projections) is never decided. Pin the canonical table-key form in design so
  every projection's strip/keep is unambiguous.

- B3 — **`format-help` ordering + non-built-in lines.** AC3 says help "derives
  from the single spec source," but current `format-help` has hand-curated
  ordering, an arg-usage hint per line, a `/skill:name` line that is NOT a
  built-in routing entry (it's prose), and separate Prompt/Skill/Extension
  sections. Design doesn't state whether spec-table iteration order must
  reproduce the current help order, whether `/skill:name` stays hand-written,
  and where the per-line `— description` vs `:usage` split renders. Ambiguous
  what "no independent hardcoded built-in list remains in format-help" requires.

- B4 — **Spec-table ordering authority.** AC2 mandates a "single keyed spec
  table" but doesn't state whether it is an ordered map and whether resolver
  output, help listing, and autocomplete must preserve that order. UIs and help
  currently differ in order; "derived projection" leaves emission order
  undefined. Specify ordered-map requirement (or explicitly that order is
  unspecified) so AC4/AC5/AC6 tests have a deterministic target.

- B5 — **`:usage` field decision deferred into AC-bearing scope.** "Description
  granularity" leaves whether the spec carries `:usage` as a plan-stage
  resolution, but Option B's example spec already shows a `:usage` key and AC1
  fixes the resolver output to `{:name :description}` (no `:usage`). If `:usage`
  lands in the table, the resolver-output shape in AC1 and the design's example
  diverge. Decide at design whether `:usage` is a table field and whether it is
  exposed by the resolver, so AC1's `{:name :description}` is or is not final.

PASS_STATUS: ACTIONABLE_FEEDBACK
