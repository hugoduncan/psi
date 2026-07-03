# Steps — 238 entity-resolution turn augmenter

## Slice 1 — eligibility + envelope skeleton + registration

- [ ] Add `psi/ai` dependency to `extensions/context-manager/deps.edn`
      (mirror `extensions/auto-session-name/deps.edn`).
- [ ] In `context_manager.clj`, add `entity-resolution-helper-session-ids`
      atom (distinct from the existing `helper-session-ids`).
- [ ] Add `slash-command-only?` predicate (trimmed non-empty text starts
      with `/`) applied to `:turn-augmentation/user-text`.
- [ ] Add `entity-resolution-augmentation` fn returning well-formed `:no-op`
      envelopes (empty operations, empty child-session-ids, diagnostic) for:
      tracked helper session, blank effective-cwd, slash-command-only text.
- [ ] Register the second augmenter (`:augmenter-id "entity-resolution"`,
      description, version, handler) in `register-turn-augmenter!`.
- [ ] Tests: helper-session no-op, blank-cwd no-op, slash-command-only no-op
      (assert no helper session is created — stub collaborator not called),
      both augmenters registered.
- [ ] Lint + `bb test --focus extensions.context-manager-test`; commit.

## Slice 2 — prompt construction, parsing, rendering (pure)

- [ ] Add embedded Method-steps-1–5 string constant adapted per Resolved
      decision 6: shell discovery commands retained as bash-runnable;
      runtime/session-graph introspection replaced with capability-gap
      disclosure; no session entity type.
- [ ] Add bash-safety constraint text (evidence-gathering only; no
      mutation / installs / long-running processes / unrelated effects).
- [ ] Add output-contract text: one line per confident mapping,
      `surface → canonical (evidence; confidence)`, emit only confident
      evidence-backed mappings, no preamble/tables/questions.
- [ ] Add history-tail excerpt rendering from `:turn-augmentation/history`
      (visible user/assistant lines, slash-command lines dropped,
      tail-truncated ~4000 chars — reuse auto-session-name pattern shape).
- [ ] Add `build-entity-resolution-prompt` composing method + constraints +
      contract + user text + history excerpt.
- [ ] Add line parser: accept only well-formed
      `surface → canonical (evidence; confidence)` lines; no
      confidence-value validation/threshold; discard all other text.
- [ ] Add `:content` renderer: `surface → canonical (evidence)` lines
      (confidence dropped, three fields).
- [ ] Tests: parser accepts well-formed lines, discards
      preamble/malformed/question-shaped text, zero-lines case; history
      excerpt includes prior-turn lines and drops slash-command lines;
      rendered content is three-field; prompt contains method, safety
      constraints, contract, user text, and history excerpt.
- [ ] Lint + focused tests; commit.

## Slice 3 — helper-session orchestration

- [ ] Verify `run-agent-loop-in-session` options for a max-rounds bound;
      record finding in implementation.md.
- [ ] Add model-selection call: `resolve-selection` with strong
      `:locality :local`, `:latency-tier :low`, `:cost-tier #{:zero :low}`,
      parent-session model as context; take top-ranked candidate only;
      empty → `:no-op` (no cloud fallback).
- [ ] Add helper-run fn: `create-child-session` from parent session-id with
      `:tool-ids ["bash"]` and effective cwd; track id in the
      entity-resolution atom; `run-agent-loop-in-session` with the built
      prompt under a 120s wall-clock budget (future + timed deref) and
      max-8-rounds bound (loop option if available, else prompt-instructed
      with wall-clock as the enforced bound); close session in `finally`,
      then untrack.
- [ ] Wire orchestration into `entity-resolution-augmentation`:
      success ⇒ `:success` envelope with one `:append-context-block`
      (`:id "entity-resolution"`, `:title "Resolved entities"`, rendered
      content), child-session id in
      `:turn-augmentation/child-session-ids`, no `:source`;
      failed/empty/timed-out run or zero parsed lines ⇒ `:no-op`.
- [ ] Make orchestration parameterizable over the extension `api`
      (selection + run collaborators injectable) for stub-based tests.
- [ ] Tests (stubbed collaborators): confident-mapping success envelope
      (block id/title/content, child-session-ids provenance);
      no-local-model → no-op; failed helper run (single attempted
      candidate) → no-op with no retry of lower-ranked candidates;
      empty-output → no-op; bound-exceeded → no-op; helper session id
      tracked during run and closed/untracked after; recursion: augmenter
      invoked for its tracked helper session → no-op.
- [ ] Lint + focused tests; commit.

## Slice 4 — end-to-end, replay, ambiguous-dropped

- [ ] Dispatch-level test: eligible turn with a stubbed helper producing one
      confident line ⇒ prepared request contains
      `:turn/augmentation-context` block (`:id "entity-resolution"`) with
      `surface → canonical (evidence)` content, inserted before the current
      user message; raw user prompt preserved.
- [ ] Test: no referring expression (stubbed model emits no lines) ⇒ no-op
      (model-determined path, distinct from the slash-command pre-filter).
- [ ] Test: ambiguous reference — stubbed helper omits the ambiguous
      surface ⇒ no line parsed for it, not present in rendered content
      (no augmenter-side confidence filtering exercised).
- [ ] Test: replay of the turn reuses the recorded operation and does not
      re-invoke model selection / helper run (collaborator call counters).
- [ ] Lint + `bb test`; commit.

## Slice 5 — docs, changelog, coherence

- [ ] Update user docs (`doc/` turn-augmentation / context-manager page) to
      describe automatic entity resolution as a context-manager
      entity-resolution augmenter capability (local model, bash-only helper,
      no-op fallbacks, non-interactive).
- [ ] Add CHANGELOG `[Unreleased] → Added` entry.
- [ ] Record final policy values (rounds/wall-clock bound, prompt constants)
      and any discoveries in implementation.md.
- [ ] Full `bb test` + `clj-kondo --lint` on touched sources; verify each
      design.md acceptance criterion has a covering test or doc.
- [ ] Commit; final coherence pass (meta/spec/tests/code/doc agree).
