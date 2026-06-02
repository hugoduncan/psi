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
