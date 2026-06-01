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
