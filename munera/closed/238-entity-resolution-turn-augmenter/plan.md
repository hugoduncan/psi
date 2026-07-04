# Plan — 238 entity-resolution turn augmenter

## Approach

Extend `extensions/context-manager` (no new extension, no manifest change —
its install manifest already declares `:psi.capability/turn-augmentation`)
with a second registered augmenter, `:augmenter-id "entity-resolution"`,
alongside the existing `"project-context"` augmenter.

Key decisions (all fixed by design.md; numbers below are the plan-level
policy values design.md deferred to us):

1. **Structure.** Add the new augmenter in
   `extensions/context-manager/src/extensions/context_manager.clj` (same ns
   as the scaffold, per Resolved decision 1). It keeps its **own**
   `entity-resolution-helper-session-ids` atom, distinct from the existing
   `helper-session-ids` atom, so recursion avoidance is per-augmenter.
   Registration happens in the same `register-turn-augmenter!` path (call
   `register` twice) inside `init`.

2. **Dependency.** Add `psi/ai` to `extensions/context-manager/deps.edn`
   (currently absent; `auto-session-name` is the precedent) to call
   `psi.ai.model-selection/resolve-selection`.

3. **Eligibility pre-filter** (before any model selection / helper run):
   - tracked helper session → `:no-op`
   - blank `:turn-augmentation/effective-cwd` → `:no-op`
   - slash-command-only user text (trimmed non-empty text starting with
     `/`, same predicate shape as `auto-session-name`'s
     `slash-command-text?`, applied to whole `user-text`) → `:no-op`,
     helper session never created.

4. **Model selection — single attempt.** Call `resolve-selection` with
   strong `:locality :local`, `:latency-tier :low`, `:cost-tier
   #{:zero :low}`, inheriting the parent session model as context (mirror
   `auto-session-name`'s request shape). Take only the **top-ranked**
   candidate; no candidate → `:no-op`; the one attempt failing → `:no-op`.
   Deliberate departure from `auto-session-name`'s retry-across-list.

5. **Helper session.** `psi.extension/create-child-session` from the parent
   `session-id`, granting **only** the existing `bash` tool
   (`:tool-ids ["bash"]`), cwd = effective cwd. Track its id in the
   augmenter's own atom, report it in
   `:turn-augmentation/child-session-ids`, and close it
   (`psi.extension/close-session`) in a `finally`, removing it from the
   tracking atom after close (keep membership during the run for recursion
   safety; removal after close is fine since the session no longer turns).

6. **Helper prompt construction.** A dedicated
   `build-entity-resolution-prompt` fn that composes:
   - the `entity-resolution` skill's Method section **steps 1–5 only**,
     embedded as a string constant in the augmenter source, adapted per
     Resolved decision 6's two-case split: shell discovery commands
     (`git status`, `git ls-files`, `find`, `git grep`) remain usable via
     `bash`; runtime/session-graph introspection is replaced by an explicit
     capability-gap disclosure; sessions are never a resolvable entity type.
   - safety constraints: `bash` is evidence-gathering only; no mutation,
     no dependency installation, no long-running processes, no unrelated
     side effects.
   - the output contract: one line per confident mapping,
     `surface → canonical (evidence; confidence)`; emit a line only for
     confident, evidence-backed mappings; no preamble, table, or questions.
   - the material: current-turn user text plus a rendered history-tail
     excerpt built from `:turn-augmentation/history`, reusing the
     `auto-session-name` sanitize/truncate pattern (visible user/assistant
     lines, slash-command lines dropped, tail-truncated to ~4000 chars).

7. **Bounded helper agent loop.** Cap the helper run: max **8** agent-loop
   rounds and a total wall-clock budget of **120s** for the whole helper
   run (each bash command already has its own 30s cap). Hitting either
   bound ⇒ unusable run ⇒ `:no-op`. Implement the wall-clock budget as a
   timeout around `run-agent-loop-in-session` (future + deref-with-timeout)
   and the round cap via the loop options if supported, else via the
   wall-clock budget alone with the round cap documented as
   prompt-instructed. (Verify what `run-agent-loop-in-session` supports at
   implementation time; the design only requires that a finite bound
   exists.)

8. **Parsing & rendering.** Parse only lines matching the exact
   `surface → canonical (evidence; confidence)` shape (regex on `→` plus a
   trailing parenthesized `evidence; confidence` group); discard everything
   else. **No confidence-value thresholding** — every well-formed line is
   kept (model self-gating). Zero parsed lines ⇒ `:no-op`. Otherwise return
   `:success` with one `:append-context-block`
   `{:id "entity-resolution" :title "Resolved entities" :content ...}`,
   `:content` re-rendered as `surface → canonical (evidence)` lines
   (confidence dropped). Omit `:source`; never mutate the parent request.

9. **Replay.** Nothing to build — 237 already replays recorded operations
   without re-invoking augmenters; add a test asserting the inherited
   guarantee holds for this augmenter's block.

10. **Tests (Scry-first).** Pure logic (pre-filter, prompt construction,
    line parsing, rendering) tested directly; helper-run orchestration
    tested by injecting stub run/select functions (nullable-style — the
    augmenter's orchestration fn takes its model-selection and
    session-run collaborators as arguments so tests substitute
    deterministic fakes without mocking frameworks). Test file:
    `extensions/context-manager/test/extensions/context_manager_test.clj`
    (extend existing).

11. **Docs.** Document the automatic entity-resolution augmenter in the
    extension/user docs (`doc/` page covering context-manager / turn
    augmentation) and add a CHANGELOG `[Unreleased] Added` entry (user
    visible behaviour).

## Risks

- **`run-agent-loop-in-session` bound support unknown.** It may not expose
  a max-rounds option; mitigation: wall-clock budget is the enforceable
  bound, round cap becomes prompt guidance. Verify early (slice 3).
- **Local model output discipline.** A weak local model may never emit
  parseable lines; that's an accepted `:no-op` outcome by design (Resolved
  decision 5), but tests must not depend on a live model — all model
  behaviour is stubbed.
- **Latency on every eligible turn.** Bounded (≤120s worst case) but still
  blocking; pre-filters are the only relief. Accepted by Resolved
  decision 3.
- **Testing the orchestration seam.** Requires the orchestration fn to be
  parameterizable over the extension `api` map; the existing `api`-map
  pattern already supports passing fake `:mutate-session`/`:mutate` fns, so
  risk is low.
- **Shared ns growth.** Adding a second augmenter to one file grows
  `context_manager.clj` substantially; keep the entity-resolution code in
  clearly-sectioned pure fns so a later extension split (deferred by
  design) stays cheap.

## Slice order

1. **Pure eligibility + envelope skeleton** — pre-filter predicates and the
   entity-resolution augmenter fn returning correct `:no-op` envelopes for
   helper-session / blank-cwd / slash-command-only turns; registration of
   the second augmenter. Tests for all three no-op paths + registration.
2. **Prompt construction + parsing + rendering (pure)** — embedded Method
   steps 1–5 constant with adaptations, history-tail excerpt rendering,
   output-contract prompt text, line parser, `:content` renderer. Tests:
   parse confident lines / discard junk / zero-lines, history excerpt
   shape, rendered three-field content.
3. **Helper-session orchestration** — model selection (single attempt),
   child-session create with `bash` grant, bounded agent-loop run,
   cleanup/close, child-session-id provenance, failure → `:no-op`. Tests
   with stubbed collaborators: success path, no-local-model → no-op,
   failed/empty run → no-op, bound-exceeded → no-op, recursion tracking,
   cleanup.
4. **End-to-end + replay + ambiguous-dropped** — wire through dispatch-level
   test asserting the `:turn/augmentation-context` block lands before the
   user message; ambiguous-surface-omitted assertion (no line emitted ⇒ not
   in content); replay reuses recorded op without model re-invocation.
5. **Docs + changelog** — doc page update, CHANGELOG entry, coherence pass
   (lint, `bb test`, verify design acceptance criteria checklist).
