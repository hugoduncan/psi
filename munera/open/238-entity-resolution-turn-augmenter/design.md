# 238 — Automatic entity resolution turn augmenter

## Goal

Automatically resolve ambiguous/underspecified references in a user's submitted
prompt into concrete project entities (paths, tasks, workflows, skills,
extensions, namespaces, vars, commands, docs, vocabulary symbols) and inject
that resolution as pre-turn context, so the parent turn sees an evidence-backed
`surface → canonical` mapping before it acts.

Reuse two already-shipped mechanisms:

1. the **pre-turn request augmentation** mechanism from closed task
   `237-pre-turn-request-augmentation` (the `:register-turn-augmenter` API,
   `:psi.capability/turn-augmentation`, the `:append-context-block` operation,
   and the `extensions/context-manager` scaffold);
2. the **local-model helper-session** pattern from
   `extensions/auto-session-name` (model selection with a strong `:locality
   :local` preference, `create-child-session` + `run-agent-loop-in-session`,
   and `helper-session-ids` recursion avoidance).

The reasoning method is the existing `entity-resolution` skill
(`.psi/skills/entity-resolution/SKILL.md`): detect referring expressions →
gather evidence → produce a `surface → canonical → evidence → confidence`
mapping → include only unambiguous mappings.

## Why

- The `entity-resolution` skill currently only fires when the model chooses to
  invoke it. Making resolution an automatic pre-turn phase means every eligible
  parent turn benefits without relying on the parent model to remember the
  skill.
- Task 237 built the augmentation rail specifically so extensions can propose
  turn-scoped context as data; the shipped `context-manager` only emits a
  trivial "Working directory: <cwd>" block. This task is the first substantive,
  model-backed augmenter and the intended proof that the rail carries real work.
- Using a local model keeps this cheap and private: it runs on every eligible
  turn, so it must not add cloud cost or meaningful latency, mirroring the
  `auto-session-name` cost/latency posture.

## Context / current state

- `extensions/context-manager/src/extensions/context_manager.clj` registers one
  augmenter, `:augmenter-id "project-context"`, returning a single
  `:append-context-block`. It already tracks a `helper-session-ids` atom (unused
  today) and no-ops for helper sessions.
- The augmenter receives the bounded v1 input contract (design of 237):
  `:turn-augmentation/user-text`, `:turn-augmentation/user-message`,
  `:turn-augmentation/effective-cwd`, `:turn-augmentation/session`,
  `:turn-augmentation/history` (bounded tail), `:turn-augmentation/session-id`,
  `:turn-augmentation/turn-id`, `:turn-augmentation/workflow-run-id`. It does
  **not** receive `ctx`, atom handles, tools, or credentials.
- `psi.ai.model-selection/resolve-selection` returns a ranked candidate list;
  `auto-session-name` requests `:latency-tier :low`, `:cost-tier #{:zero :low}`,
  strong `:locality :local`, and inherits the source session's model as context.
- Helper sessions are ordinary sessions created via
  `psi.extension/create-child-session` and driven with
  `psi.extension/run-agent-loop-in-session`; the parent `session-id` is the
  allocation reference. Recursion is avoided by remembering helper ids and
  returning `:no-op` when the augmenter is invoked for one of them.
- Model registry supports `:locality :local` (see `psi.ai.models` /
  `psi.ai.user_models`), so a local-only requirement is expressible.

## Required behaviour

A second turn augmenter registered by the existing `extensions/context-manager`
extension (new `:augmenter-id "entity-resolution"`, alongside the current
`"project-context"` augmenter) that, for an eligible parent turn:

1. reads the bounded turn projection (user text + history tail + effective-cwd);
2. selects a local helper model via `model-selection` (strong `:locality
   :local`, low latency, zero/low cost), inheriting the parent session's model
   as context, exactly like `auto-session-name`;
3. runs a helper session — created **with a minimal read-only search toolset**
   so the local model can gather filesystem/git evidence — whose prompt applies
   the `entity-resolution` method to the user text, producing a compact
   `surface → canonical → evidence → confidence` mapping restricted to
   sufficiently-unambiguous entries;
4. returns a `:success` envelope with one `:append-context-block`
   (`:id "entity-resolution"`, `:title "Resolved entities"`, `:content` = the
   rendered mapping) when at least one confident mapping exists;
5. returns a well-formed `:no-op` envelope (no operations) when the turn is a
   tracked helper session, when there is no effective cwd, when no referring
   expressions are detected, when no confident mapping is produced, or when the
   helper run fails/does not return a usable result;
6. tracks its helper session ids and cleans them up (close on completion) as
   `auto-session-name` does, so it never augments its own helper turns.

The augmenter returns **data only** (per 237): it must not mutate the parent
request or parent session, must omit `:source` (core injects provenance), and
its helper-session ids go in `:turn-augmentation/child-session-ids` as
provenance.

## Resolved decisions

1. **Host — inside `context-manager` (for now).** The entity-resolution
   augmenter is registered as a second `:augmenter-id "entity-resolution"` by
   the existing `extensions/context-manager` extension, reusing its
   `:psi.capability/turn-augmentation` grant. It maintains its own helper-session
   id tracking (distinct from any `project-context` helper tracking) so
   recursion avoidance is per-augmenter-correct. A dedicated extension may be
   split out later; not in this task.

2. **Evidence — local model + tools.** The helper session is created **with a
   minimal read-only search toolset** (e.g. file read + directory list +
   content grep, no mutation/bash-write) and the local model searches the
   worktree/git for evidence itself, following the `entity-resolution` skill
   method. No deterministic pre-gathered corpus is required in v1; the tool
   surface must be read-only and side-effect-free.

3. **Latency — accept the local model on the critical path.** 237 makes pre-turn
   augmentation a blocking, no-deadline barrier and this task keeps the
   local-model call there. No heuristic-only / model-deferred fallback path is
   built in v1; cheap eligibility pre-filters (below) reduce, but do not replace,
   the model call. Local-only + zero/low cost selection keeps it inexpensive and
   private.

### Remaining v1 policies (settled, low-risk)

- **Eligibility pre-filter.** Skip tracked helper sessions and blank-cwd turns
  (per 237 scaffold), and skip slash-command-only prompts, mirroring
  `auto-session-name`'s guards, before spending a helper run.
- **Confidence gate & output shape.** Only sufficiently-unambiguous mappings
  enter the block; ambiguous/unevidenced references are dropped, never guessed.
  Rendered `:content` is a compact `surface → canonical` list with brief
  evidence; the raw user prompt is always preserved.
- **Model-absent fallback.** If `resolve-selection` yields no local winner, the
  augmenter returns a well-formed `:no-op`; it never falls back to a cloud model
  on every turn.

## Constraints

- Preserve the 237 dispatch/effect and data-only-extension boundaries: no
  parent-request or parent-state mutation; augmenter returns an envelope; helper
  work uses existing extension session APIs only (no new child/run API).
- Preserve deterministic replay: the augmenter runs only live; recorded
  accepted operations are replayed without re-invoking the model (already
  guaranteed by 237 — do not weaken it).
- Local-first: helper model selection must strongly prefer `:locality :local`
  and zero/low cost, like `auto-session-name`; never silently use a cloud model
  on every turn.
- Recursion safety: track and no-op for the augmenter's own helper session ids.
- Helper toolset is read-only: file read / list / grep only — no mutating,
  bash-write, or otherwise side-effecting tools on the helper session.
- Never guess: only confident, evidence-backed mappings are injected; ambiguity
  is dropped, not collapsed.
- Follow project change_chain: meta/spec/tests/code/doc coherence; malli for
  schemas; Scry-first tests.

## Acceptance criteria

- An entity-resolution turn augmenter is registered through
  `:register-turn-augmenter` and gated by `:psi.capability/turn-augmentation`
  (host extension declares the capability in its manifest/effective
  permissions).
- For a parent turn whose user text contains a referring expression that maps to
  exactly one strongly-evidenced project entity, the prepared request contains a
  `:turn/augmentation-context` block (via `:append-context-block`
  `:id "entity-resolution"`) carrying the `surface → canonical` mapping, inserted
  before the current user message.
- The augmenter uses a helper session driven by a **local** model selected via
  `psi.ai.model-selection` with a strong `:locality :local` preference, and the
  helper session is created with a **minimal read-only search toolset** (no
  mutating/side-effecting tools); when no local model is available it returns a
  well-formed `:no-op` and no cloud model is used.
- The augmenter returns a well-formed `:no-op` (no operations) for: tracked
  helper sessions, blank effective-cwd, prompts with no detectable referring
  expression, no confident mapping, and failed/empty helper runs.
- Helper sessions the augmenter creates are tracked, reported in
  `:turn-augmentation/child-session-ids`, cleaned up, and never themselves
  augmented (recursion avoidance verified).
- Ambiguous or unevidenced references are dropped from the mapping and never
  guessed; the raw user prompt is always preserved.
- Replaying the turn reuses the recorded operation and does not re-invoke the
  local model (inherited 237 guarantee, asserted by a test).
- Tests (Scry-first) cover: confident single mapping → success block; no
  referring expression → no-op; helper-session recursion no-op; blank cwd
  no-op; no-local-model → no-op; ambiguous reference dropped; and replay reuse.
- Docs updated to describe automatic entity resolution as a context-manager /
  entity-resolution augmenter capability.

## Out of scope

- Changing the 237 augmentation mechanism, statechart lifecycle, capability
  gating, or operation vocabulary.
- New operation types beyond `:append-context-block`.
- Cloud-model resolution, multi-model ensembles, or resolution caching across
  turns.
- Interactive clarification prompts during pre-turn augmentation (237 explicitly
  excludes interactive pre-turn prompts).
- Rewriting the submitted user prompt or acting on resolved entities; this task
  only injects context.

## References

- `munera/closed/237-pre-turn-request-augmentation/design.md` — augmentation
  mechanism, input contract, envelope, rendering, replay.
- `extensions/context-manager/src/extensions/context_manager.clj` — augmenter
  scaffold to extend or model after.
- `extensions/auto-session-name/src/extensions/auto_session_name.clj` —
  local-model helper-session pattern and recursion avoidance.
- `components/ai/src/psi/ai/model_selection.clj` — `resolve-selection` request
  shape (locality/latency/cost criteria).
- `.psi/skills/entity-resolution/SKILL.md` — resolution method and output shape.
