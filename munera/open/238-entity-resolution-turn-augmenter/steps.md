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

- [x] `default-run-helper`'s `create-child-session` call omits
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
      DONE: added `:prompt-component-selection {:agents-md? false ... :tool-names ["bash"] ... :components #{}}`;
      asserted by `default-run-helper-suppresses-default-prompt-and-omits-worktree-test`.

- [x] `default-run-helper` passes `:worktree-path cwd` to
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
      DONE: dropped `:worktree-path` (and `:cwd` from run-helper opts) with a
      docstring/comment stating cwd comes from parent-worktree inheritance;
      asserted absent by `default-run-helper-suppresses-default-prompt-and-omits-worktree-test`.

- [x] `default-run-helper` reads `:psi.agent-session/agent-run-text` without
      checking `:psi.agent-session/agent-run-ok?`. On a failed run
      `run-agent-loop-in-session` returns `ok? false` with
      `agent-run-text = "Error: ..."`; the augmenter parses that text for
      mapping lines instead of treating it as a failed run. It happens to
      collapse to `:no-op` today (error text has no mapping-shaped line), but
      the failed-run path should gate on `agent-run-ok?` like the
      `auto-session-name` precedent, not on the incidental absence of a
      parseable line.
      DONE: `:text` is now `(when agent-run-ok? agent-run-text)`; asserted by
      `default-run-helper-gates-on-run-ok-test` (ok? false → nil text; ok? true → text).

- [x] `mapping-line-re` mis-parses two plausible local-model outputs: a
      `canonical` containing parentheses (e.g. `foo/bar (arity 2)`) leaks the
      inner parens into the evidence group, and an evidence string containing
      `;` (e.g. `git grep; 3 hits`) truncates at the first `;`. Given the
      design's emphasis on robust parsing of unpredictable local-model text,
      either harden the grammar (anchor the trailing `(...; ...)` group to the
      last parenthesized group) or document these as accepted parse
      limitations with a test.
      DONE: hardened regex — trailing group anchored to the last `(...)`, and
      evidence splits at the last `;`; asserted by two new cases in
      `parse-mapping-lines-test` (canonical-with-parens, evidence-with-semicolon).

## Implementation-review follow-ups (turn 2)

- [x] **Cloud model can be selected on every eligible turn — violates the
      local-only acceptance criterion, constraint, and doc guarantee.**
      `default-select-model` returns `(first (get-in result [:ranking :ranked]))`
      whenever `resolve-selection` yields `:outcome :ok`, without checking the
      returned candidate's `:locality`. In `helper-model-selection-request`,
      `:locality :local` is only a **strong-preference** (affects ranking among
      survivors), not a **required** constraint (affects filtering). The
      required constraints are just `:supports-text`, `:latency-tier :equals
      :low`, and `:cost-tier :one-of [:zero :low]`. Registered cloud providers
      are `:latency-tier :low`
      (`components/ai/src/psi/ai/models.clj` `provider-defaults`, anthropic +
      openai), and any cheap-tier cloud model (`input-cost ≤ 1.0`,
      `output-cost ≤ 5.0` → `:cost-tier :low` per `cost-tier`) survives the
      required filter. So when **no local model is configured** but such a
      cloud model is registered, `resolve-selection` returns `:ok` with the
      **cloud** candidate as `(first ranked)`, and the augmenter runs a
      **cloud** helper on 237's blocking pre-turn critical path of every
      eligible turn.
      This directly violates: design.md Acceptance criterion "when no local
      model is available it returns a well-formed `:no-op` and no cloud model
      is used"; Constraint "Local-first … never silently use a cloud model on
      every turn"; and the shipped `doc/extensions.md` claim "selects a single
      top-ranked **local** model … never falls back to a cloud model." The
      `entity-resolution-no-local-model-no-op-test` does not catch this — it
      stubs `:select-model` to return `nil` and never exercises the real
      `default-select-model`/`resolve-selection` path.
      (`auto-session-name` shares the same request shape, but its design does
      not elevate local-only to a hard acceptance criterion on a per-turn
      blocking path, so the same latent behaviour is out of scope for that
      extension and in scope here.)
      Fix: make `default-select-model` reject non-local winners — either add
      `{:criterion :locality :equals :local}` to `:required` (so a non-local
      pool yields `:required-constraints-unsatisfied` → nil → `:no-op`), or
      guard the returned candidate on `(= :local (get-in candidate [:facts
      :locality]))` returning nil otherwise. Add a test that drives the real
      `default-select-model` with a catalog containing only a cheap-tier cloud
      candidate (no local) and asserts nil (→ `:no-op`, no cloud helper run).
      DONE: `default-select-model` now guards the top-ranked candidate on
      `(= :local (get-in candidate [:facts :locality]))`, returning nil for a
      non-local winner (→ `:no-op`). Asserted by
      `default-select-model-rejects-cloud-winner-test` (cloud-only pool → nil,
      drives real `default-select-model`/`resolve-selection` via redefed
      `catalog-view`) and `default-select-model-accepts-local-winner-test`
      (local pool → selected). Shipped doc claim
      ("top-ranked **local** model … never falls back to a cloud model") now
      accurate; no doc edit needed.

## Implementation-review follow-ups (turn 3)

- [x] **Timeout path leaves an orphaned helper future racing session close /
      budget.** In `default-run-helper`, on wall-clock timeout the code does
      `(future-cancel fut)` then, in `finally`, closes the child
      (`psi.extension/close-session`) and `disj`s it from
      `entity-resolution-helper-session-ids`. But `run-agent-loop-in-session`
      is a **blocking dispatch** (`core/prompt-in!` → live model call);
      `future-cancel` only interrupts the worker thread and does **not**
      reliably unwind a blocking model/HTTP call, so the orphaned future can
      keep running *after* the `finally` has already closed and untracked the
      child session. Consequences: (a) the still-running future mutates /
      prompts a session the `finally` just closed (undefined behaviour against
      a detached session); (b) model work / cost continues past the 120s
      `helper-wall-clock-ms` budget the bound was supposed to enforce
      (Resolved decision 3 / "Bounded helper agent loop" require *finite*
      worst-case blocking, but the budget only bounds *this* augmenter's
      blocking, not the orphaned thread's continued work); (c) the child id is
      untracked while a turn may still run under it, narrowing the
      recursion-avoidance guarantee. Address by making the timeout path
      deterministic: keep the child tracked until the future actually
      settles (or close only after the orphan is confirmed done), and/or
      document the orphan explicitly and bound its damage (e.g. close after
      await, or accept-and-document that `future-cancel` cannot unwind the
      in-flight model call). The `auto-session-name` precedent does not use a
      wall-clock `future`/`deref` bound, so there is no prior pattern to
      inherit here — this bound is new to 238 and its teardown semantics need
      to be pinned down rather than left to `future-cancel`'s best effort.
      DONE: made the run future own its teardown — it closes + untracks the
      child in a `finally` once the (uninterruptible) blocking call actually
      returns/throws. Timeout path returns promptly with `:text nil`, leaves
      the child tracked (recursion-safe) until that `finally` fires, and no
      longer calls `future-cancel` (it can't unwind the blocking call and its
      cancel-then-`deref` also defeats genuine-settlement detection). Settled
      path is unchanged behaviourally (finally completes before `deref`
      returns the value). Docstring documents the semantics.

- [x] **The enforced finite bound (wall-clock timeout branch) is untested at
      the `default-run-helper` level.** Slice 3 records that
      `run-agent-loop-in-session` has no `:max-rounds` option, so the round
      cap is only *prompt-instructed* and the *sole enforced* finite bound is
      the 120s wall-clock budget (`future` + timed `deref`, the `::timeout`
      branch). Yet no test exercises that branch: the "bound-exceeded → no-op"
      assertion in slice 3 stubs the `run-helper` collaborator inside
      `entity-resolution-augmentation` and never runs the real
      `default-run-helper` `deref`/`::timeout`/`future-cancel`/`finally` code.
      For the one load-bearing bound on 237's blocking critical path, add a
      `default-run-helper` test that drives the real timeout branch (e.g. a
      `:mutate-session run-agent-loop-in-session` that blocks past a
      test-shrunk budget, or making `helper-wall-clock-ms` injectable so the
      test can use a small value) and asserts `:text` is nil (→ `:no-op`) and
      the child is closed/untracked. Without it, the only enforced bound is
      unverified and could regress silently.
      DONE: made `helper-wall-clock-ms` injectable via a `:wall-clock-ms`
      run-opt; added `default-run-helper-timeout-branch-test`, which drives
      the real `deref`/`::timeout` branch with a small injected budget against
      an uninterruptible blocking stub, asserting `:text` nil (→ `:no-op`),
      that the child stays tracked and unclosed while the orphan runs, and is
      closed + untracked only after the orphan settles.

## Implementation-review follow-ups (turn 4)

- [x] **`parse-mapping-lines` accepts non-mapping / degenerate lines,
      injecting misleading content into the `Resolved entities` block —
      weakens the design's "never guess" and robust-parsing guarantees.**
      `mapping-line-re` matches any line containing an arrow (`→`/`->`) plus a
      trailing `(… ; …)` group, without requiring the captured surface/
      canonical to be non-empty *or* the line to be a genuine mapping. Three
      concrete false-positives (verified against the live regex):
      1. **Empty canonical.** `"a →  (e; c)"` matches → `{:surface "a"
         :canonical "" :evidence "e"}`, rendering the degenerate block line
         `a →  (e)` with a blank canonical. The parser trims canonical to `""`
         but never rejects the mapping, so an empty target is emitted as if
         confident.
      2. **Incidental code-shaped line echoed by the model.**
         `"(fn [x] -> (foo x)) (call; note)"` matches → `{:surface "(fn [x]"
         :canonical "(foo x)) " :evidence "call"}`. A local model that echoes
         a code snippet or arrow-bearing prose with a trailing parenthesized
         clause produces a bogus "resolved entity" the augmenter injects as
         fact — a direct "never guess" violation, since nothing was actually
         resolved.
      3. **Nested parens in evidence.** `"the fn → foo (bar) (baz (qux);
         high)"` mis-splits → evidence `"baz (qux"`, confidence `"qux)"` — the
         inner `(qux)` leaks across the evidence/confidence boundary. Prior
         turn-1 hardening handled parens-in-canonical and a single semicolon in
         evidence, but not nested parentheses in evidence.
      Given the design's stated emphasis on "robust parsing of unpredictable
      local-model text" and the "never guess — only confident, evidence-backed
      mappings" constraint, harden the parser and/or filter: reject lines whose
      trimmed surface or canonical is empty (drop, don't emit); and either
      tighten the grammar so an arbitrary arrow-plus-parenthesized-clause line
      is not accepted as a mapping (e.g. constrain canonical to a
      path/symbol/entity shape, or require the trailing group to be the final
      balanced-paren token) or document these as accepted parse limitations
      with covering tests. Add `parse-mapping-lines-test` cases for
      empty-canonical rejection, the code-shaped false-positive, and the
      nested-parens evidence split so the accept/reject boundary is pinned.
      DONE: replaced the single greedy `mapping-line-re` with a structural
      parser — `balanced-trailing-group` scans right-to-left to take the
      *balanced* final `(...)` (nested evidence parens stay inside `inner`,
      code-shaped unbalanced tails are rejected), evidence splits at the last
      `;`, and a mapping is emitted only when surface/canonical/evidence/
      confidence are all non-empty *and* `balanced-parens?` holds for surface
      and canonical (rejects echoed code like `(foo x))`). Asserted by three
      new `parse-mapping-lines-test` cases: nested-parens-in-evidence split,
      empty-canonical rejection, code-shaped false-positive rejection. Prior
      parens-in-canonical and semicolon-in-evidence cases still pass.

## Implementation-review follow-ups (turn 5)

- [x] **The registered `entity-resolution` handler's `api`-threading is
      untested — the production default-collaborator wiring has no covering
      test.** In `register-turn-augmenter!` the handler is
      `(fn [turn-projection] (entity-resolution-augmentation api turn-projection))`
      (no collaborators), so at runtime it threads the real extension `api`
      into `default-select-model` and `default-run-helper`. But every one of
      the 8 `entity-resolution-augmentation` call sites in the test ns passes
      an **empty** `{}` api together with **injected stub** collaborators
      (`:select-model`/`:run-helper`), so the default branch — where the two
      defaults are built as `#(default-select-model api %)` and
      `#(default-run-helper api %)` — is never exercised through the
      augmenter. `default-select-model` and `default-run-helper` are tested
      *directly*, but not the seam that binds `api` into them via the 2-arity
      `entity-resolution-augmentation`. Meanwhile
      `init-registers-entity-resolution-augmenter-test` only asserts the
      registered handler is `fn?` — unlike the sibling
      `init-registers-turn-augmenter-test`, which *invokes* the
      `project-context` handler and asserts a `:success` envelope. A
      regression that dropped `api` from the closure, swapped the default
      collaborators, or mis-ordered the
      `(or (:select-model collaborators) #(default-select-model api %))`
      fallback would pass all current tests. Add a test that invokes the
      2-arity `entity-resolution-augmentation` (or the registered handler)
      with a **real** `api` map (nullable-style, exposing
      `:query-session`/`:mutate-session`/`:mutate`) and **no** collaborators,
      asserting it threads through the defaults to a well-formed envelope
      (e.g. no-local-model → `:no-op`, or a stubbed
      `:mutate-session`/`resolve-selection` producing a `:success` block), so
      the production default-collaborator path is covered rather than only the
      stub-injected path.
      DONE: added `entity-resolution-registered-handler-threads-real-api-test`
      — drives the **registered** handler (built by `init` with the real
      nullable `api` closed over, no injected collaborators) with a `base-tp`
      turn projection under a `catalog-view`-redefed empty model pool. The real
      `#(default-select-model api %)` closure runs through `resolve-selection`
      (→ `:no-winner` on the empty pool → nil), so the handler reaches the
      deterministic `no-op "no local model"` outcome — proving `api` is threaded
      through the default-collaborator seam, not just the stub-injected path.

## Implementation-review follow-ups (turn 6)

- [x] **History-tail inclusion is non-functional in production — the
      augmenter mis-reads the `:turn-augmentation/history` projection shape,
      silently disabling the design-required anaphora context.** The 237
      contract (`munera/closed/237-.../design.md` lines 221–248) and the live
      producer
      (`components/agent-session/src/psi/agent_session/dispatch_effects.clj`
      `build-augmentation-history-projection`) both fix
      `:turn-augmentation/history` as a **map**
      `{:message-count N :tail [{:index .. :role .. :content-types ..
      :snippet ..} ...]}` (tail = last 8 prior messages, each with a
      `:snippet`, no `:text`/`:content` keys). But `render-history-excerpt`
      iterates `(or history [])` **directly** (treating the map as a seq of
      entries — it degrades to iterating MapEntry pairs) and `history-line`
      reads `(:text entry)`/`(:content entry)`, neither of which exists on a
      tail entry (the real key is `:snippet`). Consequences: (a) in
      production the history excerpt is *always empty*, so
      `build-entity-resolution-prompt` never includes prior-turn context and
      the "History-tail inclusion" v1 policy's anaphora resolution ("it",
      "this", "that", "the former/latter") — which design.md declares
      *required* because anaphora is only resolvable against prior turns — is
      dead; (b) the defect is masked because `build-entity-resolution-prompt-test`
      passes a hand-built **flat vector** `[{:role "user" :text "..."}]` that
      matches neither the real projection map nor its entry keys, so the test
      is green against a fixture the runtime never produces. Fix
      `render-history-excerpt`/`history-line` to consume the real projection:
      read `(:tail history)` and each entry's `:role` + `:snippet` (dropping
      slash-command and blank snippets as today), and update the prompt test
      fixture to the actual `{:message-count :tail [...]}` shape (or a shared
      237 projection helper) so the test proves real-shape history is
      rendered. Add a regression asserting a map-shaped history with `:tail`
      snippets appears in the user-prompt and a flat-vector/`nil`/empty-tail
      history yields no excerpt.
      DONE: `render-history-excerpt` now iterates `(:tail history)` and
      `history-line` reads each entry's `:role` + `:snippet` (dropping
      slash-command/blank snippets as before), matching the live 237
      `build-augmentation-history-projection` shape
      (`{:message-count N :tail [{:index :role :content-types :snippet}]}`).
      `build-entity-resolution-prompt-test` fixture rewritten to the real
      projection map; added regressions asserting (a) a map-shaped `:tail`
      snippet renders as `User: <snippet>` in the user-prompt, and (b)
      `nil`/empty-`:tail`/flat-vector history yields no excerpt (flat vector
      has no `:tail` → correctly ignored).

## Test-review follow-ups (turn 7)

- [ ] **`entity-resolution-ambiguous-dropped-test` does not exercise the
      design behaviour it names — it is a duplicate of the empty-run no-op
      test.** design.md's Acceptance criteria and "Confidence gate" policy
      specify the ambiguous case as: the helper self-gates and *omits the
      ambiguous surface's line while still emitting confident ones*, and "the
      assertion is that no line is emitted/parsed for the ambiguous surface,
      not that a low-confidence line is filtered." The current test stubs the
      helper with `:text ""` (fully empty output), which is behaviourally
      identical to `entity-resolution-empty-run-no-op-test` — it proves only
      "empty text ⇒ no-op," not "an ambiguous surface is dropped *while a
      confident one is kept*." Its second assertion
      `(is (not (some #(= :success %) [(:turn-augmentation/status env)])))` is
      trivially true given the first `:no-op` assertion and adds no signal.
      Strengthen the test to feed a *mixed* helper output — one confident
      mapping line for an unambiguous surface plus prose/commentary about an
      ambiguous surface that emits no mapping line — and assert the resulting
      `:success` block's rendered content contains the confident surface and
      does **not** contain the ambiguous surface. That exercises the
      never-guess drop-one-keep-another semantics the criterion actually
      requires, distinct from the empty-run path.

- [ ] **No test spans the production recursion-avoidance loop end to end.**
      The recursion guarantee ("Helper sessions ... never themselves
      augmented (recursion avoidance verified)") depends on two halves in
      *different* code paths sharing `entity-resolution-helper-session-ids`:
      `default-run-helper` `conj`s the real child id into the atom, and the
      augmenter pre-filter reads that same atom to no-op. Both halves are
      tested only in isolation and against *different* ids:
      `entity-resolution-helper-session-no-op-test` seeds the atom **manually**
      (`swap! ... conj "s1"`) rather than via a real run, and the
      success/orchestration tests stub `:run-helper` so it never touches the
      atom. A regression that made `default-run-helper` track the wrong id (or
      stop tracking), while leaving the manually-seeded pre-filter test green,
      would not be caught. Add a test that drives the real `default-run-helper`
      to track a child id, then invokes `entity-resolution-augmentation` with a
      turn projection whose `:turn-augmentation/session-id` is that tracked id,
      asserting `:no-op` — linking the producer and consumer of the tracking
      atom in one flow.

- [ ] **The settled-success close+untrack path of `default-run-helper` is
      unasserted — the primary cleanup path has no coverage.** The "Helper
      sessions ... cleaned up" acceptance criterion is verified only for the
      *timeout/orphan-settled* branch (`default-run-helper-timeout-branch-test`).
      On the normal settled run (`default-run-helper-gates-on-run-ok-test`) the
      future's `finally` closes the child (`:mutate` →
      `psi.extension/close-session`) and `disj`s it from the tracking atom, but
      neither `gates-on-run-ok` sub-case asserts the child was closed or
      untracked — `fake-run-api`'s `:mutate` is a no-op that records nothing.
      Extend the settled-run test (or add one) to record the `close-session`
      call and assert, after the run returns, that the child id was closed and
      removed from `entity-resolution-helper-session-ids`, so the common-path
      cleanup is covered rather than only the exceptional timeout path.
