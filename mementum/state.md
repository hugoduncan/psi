# Mementum State

λ state_md(x).   ⟨project convention; mementum stays external/unchanged⟩
  | state.md ≡ current_state_snapshot(features ∧ structure ∧ orientation)   ⟨bootloader⟩
  | update(state.md) ≡ edit_in_place ∧ prune_stale   ⟨¬append_log⟩
  | ¬contains(state.md, {task_pass_notes ∨ review_pass_log ∨ per_commit_history ∨ progress_entries})
  | task_progress → munera_task_artifacts({implementation.md ∧ steps.md})   ⟨not state.md⟩
  | durable_lesson → memories ∨ knowledge   ⟨not state.md⟩
  | history(state.md) ≡ git   ⟨recover via git log, ¬accreted in-file⟩
  | delegated_session(review ∨ implement ∨ plan) → ¬obligated(update(state.md))
      ⟨write task_progress to task artifacts; touch state.md only on real feature/structure shift⟩
  | size(state.md) → small ∧ scannable(≤ ~30s)   ⟨grows → prune, ¬accrete⟩

Working-memory bootloader for psi. Read first each session for fast orientation.
This file describes the **current state of the project in terms of features and
structure** — it is not a task work log. Per-task history lives in git, Munera
task artifacts, and mementum memories/knowledge.

Bootstrapped on 2026-04-02.

## What psi is

A deterministic, replayable, UI-agnostic AI coding-agent harness in JVM Clojure.
Architecture follows a Viable System Model (see `AGENTS.md` → Architecture):

- Single canonical state atom; all reads go through resolvers (Pathom/EQL), all
  changes and side effects go through mutations dispatched on an interceptor
  chain that produces effects-as-data run at the boundary.
- Event log + replay; statecharts enforce valid transitions.
- Extensions are isolated mini viable systems (manifest/permissions/subscriptions).
- Adapters: TUI (terminal) and RPC (stdio/EDN, used by emacs-ui). `app-runtime`
  is shared by the adapters; `rpc` is transport-only.

## Capabilities (current)

@../ramora/IMPLEMENTED.md

## Protocols

- **mementum** — git-native memory. `mementum/memories/`, `mementum/knowledge/`,
  and this `state.md` working memory. Memories/knowledge are durable; state.md
  is the orientation bootloader.
- **munera** — git-native task protocol. `munera/open/` and `munera/closed/`
  task dirs (`design.md`, `plan.md`, `steps.md`, `implementation.md`);
  `munera/plan.md` curates active-task order.
- **romera** — git-native memory protocol. `romera/INDEX.md` entry-point.

## Orientation

- Active task work: read `munera/plan.md`, then the relevant `munera/open/NNN-*`.
- Architecture/principles: `AGENTS.md`, `ramora/META.md`, `doc/architecture.md`.
- User docs: `README.md`, `doc/`.
- Deeper recall: `git log`/`git grep` over `mementum/` and task artifacts.

## Build / test

- Tests: Scry-first. `bb clojure:test:unit` (also `:extensions`, `:integration`);
  focused `bb clojure:test:scry --namespace <ns>`.
- Lint/format: `clj-kondo --lint <paths>`; `clj-paren-repair <file>` after edits.
- Babashka tasks: `bb tasks`.
