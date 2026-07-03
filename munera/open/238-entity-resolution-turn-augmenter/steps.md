# Steps — 238 entity-resolution turn augmenter

## Slice 1 — eligibility + envelope skeleton + registration

- [x] Add `psi/ai` dependency to `extensions/context-manager/deps.edn`
      (mirror `extensions/auto-session-name/deps.edn`).
- [x] In `context_manager.clj`, add `entity-resolution-helper-session-ids`
      atom (distinct from the existing `helper-session-ids`).
- [x] Add `slash-command-only?` predicate (trimmed non-empty text starts
      with `/`) applied to `:turn-augmentation/user-text`.
- [x] Add `entity-resolution-augmentation` fn returning well-formed `:no-op`
      envelopes (empty operations, empty child-session-ids, diagnostic) for:
      tracked helper session, blank effective-cwd, slash-command-only text.
- [x] Register the second augmenter (`:augmenter-id "entity-resolution"`,
      description, version, handler) in `register-turn-augmenter!`.
- [x] Tests: helper-session no-op, blank-cwd no-op, slash-command-only no-op
      (assert no helper session is created — stub collaborator not called),
      both augmenters registered.
- [x] Lint + `bb test --focus extensions.context-manager-test`; commit.

## Slice 2 — prompt construction, parsing, rendering (pure)

- [x] Add embedded Method-steps-1–5 string constant adapted per Resolved
      decision 6: shell discovery commands retained as bash-runnable;
      runtime/session-graph introspection replaced with capability-gap
      disclosure; no session entity type.
- [x] Add bash-safety constraint text (evidence-gathering only; no
      mutation / installs / long-running processes / unrelated effects).
- [x] Add output-contract text: one line per confident mapping,
      `surface → canonical (evidence; confidence)`, emit only confident
      evidence-backed mappings, no preamble/tables/questions.
- [x] Add history-tail excerpt rendering from `:turn-augmentation/history`
      (visible user/assistant lines, slash-command lines dropped,
      tail-truncated ~4000 chars — reuse auto-session-name pattern shape).
- [x] Add `build-entity-resolution-prompt` composing method + constraints +
      contract + user text + history excerpt.
- [x] Add line parser: accept only well-formed
      `surface → canonical (evidence; confidence)` lines; no
      confidence-value validation/threshold; discard all other text.
- [x] Add `:content` renderer: `surface → canonical (evidence)` lines
      (confidence dropped, three fields).
- [x] Tests: parser accepts well-formed lines, discards
      preamble/malformed/question-shaped text, zero-lines case; history
      excerpt includes prior-turn lines and drops slash-command lines;
      rendered content is three-field; prompt contains method, safety
      constraints, contract, user text, and history excerpt.
- [x] Lint + focused tests; commit.

## Slice 3 — helper-session orchestration

- [x] Verify `run-agent-loop-in-session` options for a max-rounds bound;
      record finding in implementation.md. FINDING: no `:max-rounds` option
      exists (only `:session-id :prompt :model :api-key`). Round cap is
      prompt-instructed; the enforced finite bound is the 120s wall-clock
      budget via `future` + timed `deref`.
- [x] Add model-selection call: `resolve-selection` with strong
      `:locality :local`, `:latency-tier :low`, `:cost-tier #{:zero :low}`,
      parent-session model as context; take top-ranked candidate only;
      empty → `:no-op` (no cloud fallback).
- [x] Add helper-run fn: `create-child-session` from parent session-id with
      `:tool-ids ["bash"]` and effective cwd; track id in the
      entity-resolution atom; `run-agent-loop-in-session` with the built
      prompt under a 120s wall-clock budget (future + timed deref) and
      max-8-rounds bound (loop option if available, else prompt-instructed
      with wall-clock as the enforced bound); close session in `finally`,
      then untrack.
- [x] Wire orchestration into `entity-resolution-augmentation`:
      success ⇒ `:success` envelope with one `:append-context-block`
      (`:id "entity-resolution"`, `:title "Resolved entities"`, rendered
      content), child-session id in
      `:turn-augmentation/child-session-ids`, no `:source`;
      failed/empty/timed-out run or zero parsed lines ⇒ `:no-op`.
- [x] Make orchestration parameterizable over the extension `api`
      (selection + run collaborators injectable) for stub-based tests.
- [x] Tests (stubbed collaborators): confident-mapping success envelope
      (block id/title/content, child-session-ids provenance);
      no-local-model → no-op; failed helper run (single attempted
      candidate) → no-op with no retry of lower-ranked candidates;
      empty-output → no-op; bound-exceeded → no-op; helper session id
      tracked during run and closed/untracked after; recursion: augmenter
      invoked for its tracked helper session → no-op.
- [x] Lint + focused tests; commit.

## Slice 4 — end-to-end, replay, ambiguous-dropped

- [~] Dispatch-level insertion: the produced `:success` envelope carries the
      exact 237-rail operation shape (`:op :append-context-block`,
      `:id "entity-resolution"`, `:title`, three-field `:content`, no
      `:source`) — asserted by `entity-resolution-confident-mapping-success-test`.
      The block's insertion before the current user message and prompt-layer
      rendering is the shared, augmenter-id-agnostic 237 mechanism, already
      proven by `build-prepared-request-inserts-turn-augmentation-context-test`
      (`components/agent-session/.../prompt_request_test.clj`); re-proving it in
      the extension test ns would require adding agent-session/turn-runtime
      deps to the extension test alias (coupling AGENTS.md warns against).
- [x] Test: no referring expression (stubbed model emits no lines) ⇒ no-op
      (`entity-resolution-empty-run-no-op-test`, model-determined path,
      distinct from the slash-command pre-filter).
- [x] Test: ambiguous reference — stubbed helper omits the ambiguous
      surface ⇒ no line parsed for it, not present in rendered content
      (`entity-resolution-ambiguous-dropped-test`).
- [~] Replay reuse is an inherited 237 guarantee (recorded operations replay
      without re-invoking augmenters); already asserted by the 237 replay tests
      in `components/agent-session`. Not duplicated across the component
      boundary; see implementation.md.
- [x] Lint + `bb test`; commit.

## Slice 5 — docs, changelog, coherence

- [x] Update user docs (`doc/` turn-augmentation / context-manager page) to
      describe automatic entity resolution as a context-manager
      entity-resolution augmenter capability (local model, bash-only helper,
      no-op fallbacks, non-interactive).
- [x] Add CHANGELOG `[Unreleased] → Added` entry.
- [x] Record final policy values (rounds/wall-clock bound, prompt constants)
      and any discoveries in implementation.md.
- [x] Full `bb test` + `clj-kondo --lint` on touched sources; verify each
      design.md acceptance criterion has a covering test or doc.
- [x] Commit; final coherence pass (meta/spec/tests/code/doc agree).

## Implementation-review follow-ups (turn 1)

- [ ] `default-run-helper`'s `create-child-session` call omits
      `:prompt-component-selection`, so the helper child session inherits the
      **full default system prompt** — AGENTS.md context files, all skill
      `when-to-use` contributions, all extension prompt contributions, and all
      tool prompt fragments (`normalize-prompt-component-selection` returns nil
      → default full assembly, see
      `components/prompt-assets/src/psi/prompt_assets/system_prompt.clj`). This
      contradicts Resolved decision 6 (helper prompt embeds **only** Method
      steps 1–5, deliberately excluding Output-Shape/Act-or-ask and any
      conflicting guidance) and the augmenter's non-interactive parse-only
      contract, and deviates from the cited `auto-session-name` precedent,
      which explicitly suppresses these via
      `:prompt-component-selection {:agents-md? false :extension-prompt-contributions [] :tool-names [] :skill-names [] :components #{}}`.
      Add an explicit `:prompt-component-selection` to the helper
      `create-child-session` call (mirror auto-session-name, keeping `bash` in
      `:tool-names`) so the constructed system-prompt is authoritative.

- [ ] `default-run-helper` passes `:worktree-path cwd` to
      `create-child-session`, but that mutation
      (`components/agent-session/src/psi/agent_session/mutations/session.clj`,
      `create-child-session`) does **not** destructure or forward
      `:worktree-path` — it is silently ignored; the child gets the effective
      cwd only via parent-worktree inheritance. This is a silent dead
      parameter (AGENTS.md `λ sync` / no-silent-shim). Either drop the
      argument with a comment stating cwd comes from parent inheritance, or
      make the reliance on inheritance explicit and asserted, so a future
      divergence between projected effective-cwd and parent worktree-path
      cannot break bash's working directory silently.

- [ ] `default-run-helper` reads `:psi.agent-session/agent-run-text` without
      checking `:psi.agent-session/agent-run-ok?`. On a failed run
      `run-agent-loop-in-session` returns `ok? false` with
      `agent-run-text = "Error: ..."`; the augmenter parses that text for
      mapping lines instead of treating it as a failed run. It happens to
      collapse to `:no-op` today (error text has no mapping-shaped line), but
      the failed-run path should gate on `agent-run-ok?` like the
      `auto-session-name` precedent, not on the incidental absence of a
      parseable line.

- [ ] `mapping-line-re` mis-parses two plausible local-model outputs: a
      `canonical` containing parentheses (e.g. `foo/bar (arity 2)`) leaks the
      inner parens into the evidence group, and an evidence string containing
      `;` (e.g. `git grep; 3 hits`) truncates at the first `;`. Given the
      design's emphasis on robust parsing of unpredictable local-model text,
      either harden the grammar (anchor the trailing `(...; ...)` group to the
      last parenthesized group) or document these as accepted parse
      limitations with a test.
