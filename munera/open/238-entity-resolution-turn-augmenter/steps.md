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

- [x] **`entity-resolution-ambiguous-dropped-test` does not exercise the
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
      DONE: reworked `entity-resolution-ambiguous-dropped-test` to feed mixed
      helper output — one confident mapping line for "the resolver" plus prose
      declining the ambiguous "that thing" (no mapping line). Now asserts
      `:success` and that the rendered `:content` contains "the resolver" but
      **not** "that thing", exercising drop-one-keep-another distinct from the
      empty-run path.

- [x] **No test spans the production recursion-avoidance loop end to end.**
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
      DONE: added `entity-resolution-recursion-loop-end-to-end-test`. Drives
      the real `default-run-helper` against a blocking (uninterruptible) run so
      it `conj`s "child-1" and returns on a small injected `:wall-clock-ms`
      while the orphan keeps it tracked; then invokes the real
      `entity-resolution-augmentation` with a turn projection whose
      `:turn-augmentation/session-id` is that tracked "child-1", asserting
      `:no-op` — linking the real producer and consumer through the shared
      atom. Releases the orphan and awaits untrack to keep the fixture clean.

- [x] **The settled-success close+untrack path of `default-run-helper` is
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
      DONE: extended `fake-run-api` with a `:closed` atom that records
      `close-session` ids, added an `await-untracked` helper (settled cleanup
      runs on the future's own thread), and added
      `default-run-helper-settled-run-closes-and-untracks-test` — a normal
      settled run now asserts the child was closed and removed from
      `entity-resolution-helper-session-ids`, covering the common cleanup path.

## Test-review follow-ups (turn 8)

- [x] **The `render-history-excerpt` tail-truncation branch (`max-history-chars`
      = 4000) is untested — the design's "History-tail inclusion … tail-
      truncated" behaviour has no covering test.** `render-history-excerpt`
      has two branches: `(<= (count text) max-history-chars)` returns the full
      excerpt, else `(subs text (- (count text) max-history-chars))` keeps the
      *tail* (most-recent chars). `build-entity-resolution-prompt-test` only
      exercises short histories (the `<=` branch), so the `subs` tail-cut is
      never run. A regression that truncated from the head (`(subs text 0
      max-history-chars)`, dropping the most-recent/most-relevant anaphora
      context), off-by-one'd the bound, or dropped truncation entirely would
      pass all current tests. The design's "History-tail inclusion" v1 policy
      names truncation as a required behaviour (`auto-session-name`
      sanitize/truncate precedent). Add a `build-entity-resolution-prompt` /
      `render-history-excerpt` case with a history whose rendered lines exceed
      `max-history-chars`, asserting the excerpt is length-bounded (≤
      max-history-chars) and retains the *tail* (a marker in the last line is
      present; a marker in an early, truncated-away line is absent).
      DONE: added `build-entity-resolution-prompt-tail-truncation-test` —
      builds a `:tail` whose rendered excerpt exceeds `max-history-chars`
      (4000) via a head OLDMARKER line + ~30 filler lines + a tail NEWMARKER
      line; extracts the excerpt from the user-prompt and asserts it is
      length-bounded (≤ 4000), retains NEWMARKER (tail kept), and drops
      OLDMARKER (head truncated) — pinning the `subs`-from-tail branch against
      a head-cut / off-by-one / no-truncation regression.

- [x] **The augmenter does not catch exceptions from `run-helper`, and the
      `stub` test-helper's `throw?` affordance is dead — the "helper run
      fails → :no-op" contract is unverified for the exception path.**
      Required behaviour item 5 lists "when … the helper run fails … return a
      well-formed `:no-op`". `default-run-helper` catches its own internal
      failures and returns nil (covered), but `entity-resolution-augmentation`
      wraps neither `select-model` nor `run-helper` in try/catch, so an
      injected-collaborator exception (or any future run-helper that throws
      rather than returns nil) propagates uncaught out of the augmenter — a
      thrown-from-a-collaborator failure is NOT the well-formed `:no-op` the
      contract promises. The `stub` helper already defines a `throw?` branch
      for exactly this scenario, but no test sets it, so it is dead test-helper
      code. Either (a) remove the dead `throw?` branch from `stub`, or (b)
      decide the augmenter should be defensive against a throwing helper: wrap
      the helper run so an exception collapses to `:no-op`, and add a test
      (using `stub {:throw? true}`) asserting a throwing helper yields a
      well-formed `:no-op` rather than propagating. Pick one so the behaviour
      and its test-affordance agree — currently the affordance exists but the
      behaviour (and its test) do not.
      DONE: chose (b) — made the augmenter defensive. `run-helper` is now
      wrapped in `(try … (catch Throwable _ nil))` in
      `entity-resolution-augmentation`, so a throwing helper collapses to the
      same `nil` result / `:no-op` path as a failed/empty run (never
      propagates onto 237's blocking pre-turn path). Activated the previously
      dead `throw?` affordance: added
      `entity-resolution-throwing-helper-no-op-test` (`stub {:throw? true}`),
      asserting `:no-op` and empty `:child-session-ids` (no id reported when
      the run threw).

## Test-review follow-ups (turn 9)

- [x] **The design-required capability-gap disclosure in the helper system
      prompt is untested.** Resolved decision 6 fixes a specific adaptation of
      the embedded Method: shell discovery commands stay `bash`-runnable, but
      "runtime/session graph introspection is replaced with an explicit
      capability-gap disclosure" and "sessions are not a resolvable entity
      type." `entity-resolution-method` implements this ("You cannot query the
      Psi runtime/session graph — sessions are not a resolvable entity type
      here …"), but `build-entity-resolution-prompt-test` asserts only method /
      safety / contract / user-text / history — never this disclosure. A
      regression that dropped the capability-gap wording (re-introducing the
      skill's original unavailable-evidence-source phrasing, or implying
      sessions are resolvable) would pass all current tests, yet it is an
      explicit design-required prompt element. Add an assertion to
      `build-entity-resolution-prompt-test` (or a dedicated case) that the
      system-prompt contains the capability-gap disclosure (cannot query
      runtime/session graph) and the "sessions are not a resolvable entity
      type" statement.
      DONE: added assertions to `build-entity-resolution-prompt-test` that the
      system-prompt contains "cannot query the Psi runtime/session graph" and
      "sessions are not a resolvable entity type", pinning the Resolved
      decision 6 capability-gap disclosure against a regression that drops the
      wording or re-implies sessions are resolvable.

- [x] **The round-cap prompt instruction — the *only* signal of the design's
      round bound — is untested.** Slice 3's finding records that
      `run-agent-loop-in-session` exposes no `:max-rounds` option, so the
      "Bounded helper agent loop" round cap is *prompt-instructed only* (the
      enforced finite bound is the wall-clock budget, tested by
      `default-run-helper-timeout-branch-test`). `entity-resolution-bash-safety`
      carries the round cap ("use at most 8 rounds of tool use"), but no test
      asserts the built prompt contains it. Since the prompt text is the sole
      remaining representation of the round bound, a regression dropping or
      corrupting it (e.g. `max-helper-rounds` no longer interpolated) would
      pass every test. Add an assertion to `build-entity-resolution-prompt-test`
      that the system-prompt states the round cap (references
      `max-helper-rounds` / "at most N rounds").
      DONE: added an assertion to `build-entity-resolution-prompt-test` that the
      system-prompt contains "at most 8 rounds" (the interpolated
      `max-helper-rounds` value), pinning the sole prompt-level representation
      of the round bound against a drop/mis-interpolation regression.

- [x] **The selected model is never asserted to flow into the helper run —
      the single-attempt selection→run wiring is unverified.** Required
      behaviour item 3 / "Single-attempt model selection" specify that the one
      top-ranked local candidate `select-model` returns is the model the
      helper session actually runs under. `entity-resolution-augmentation`
      threads it as `:model model` into `run-helper`, but every stubbed
      `:run-helper` ignores its `:opts`, and no test asserts the model returned
      by `select-model` reaches `run-helper`'s run-opts. A regression that
      dropped `:model` from the run-opts (or passed the wrong value) would
      pass — the success test only checks the rendered block, not the
      selection→run seam. Add an assertion (e.g. a `:run-helper` that captures
      its `:model` run-opt) that the model returned by `select-model` is the
      one passed to the helper run.
      DONE: added `entity-resolution-selected-model-flows-into-run-test` — a
      `:run-helper` captures its `:model` run-opt and the test asserts it
      equals the exact map `select-model` returned, pinning the selection→run
      wiring against a dropped/wrong `:model`.

- [x] **`default-select-model` tests stub the model-registry infra via
      `with-redefs` on `catalog-view` instead of injecting a catalog
      (project `λtest` nullable-over-mock standard).** `resolve-selection`
      already exposes a `:catalog` parameter (defaulting to `(catalog-view)`),
      the intended injection seam, but `default-select-model` does not thread a
      catalog through, so `default-select-model-rejects-cloud-winner-test` /
      `default-select-model-accepts-local-winner-test` must `with-redefs` the
      real `catalog-view` var — a global stub of an infrastructure boundary the
      project's testing standard steers away from. Either make
      `default-select-model` accept/thread an injectable catalog (or expose the
      catalog via `api`) so the tests pass a nullable candidate pool as a
      parameter, or document why `with-redefs` is the accepted seam here.
      Low-risk; behaviour is already correct — this is a testability/standard
      alignment item.
      DONE: added an optional `catalog` arg to `default-select-model` (3-arity)
      that threads into `resolve-selection`'s `:catalog` seam; the 2-arity
      production path is unchanged (defaults to live `catalog-view`). Reworked
      `default-select-model-rejects-cloud-winner-test` /
      `default-select-model-accepts-local-winner-test` to inject a nullable
      `{:candidates [...]}` pool as a parameter instead of `with-redefs`-ing
      `catalog-view`. The registered-handler test
      (`entity-resolution-registered-handler-threads-real-api-test`) keeps
      `with-redefs catalog-view` deliberately: it exercises the production
      2-arity default-collaborator seam, which has no catalog injection point.

## Test-review follow-ups (turn 10)

- [x] **`default-run-helper`'s child-creation-failure branch is untested — the
      run/track entry gate on the blocking pre-turn path has no coverage.**
      `default-run-helper` wraps `create-child-session` in `(try … (catch
      Exception _ nil))` and then gates the entire run on
      `(when child-session-id …)`: when child creation returns nil (or throws
      → caught → nil), `child-session-id` is nil, so the fn returns nil (→
      `:no-op`) *without* running `run-agent-loop-in-session`, without
      `conj`-ing anything into `entity-resolution-helper-session-ids`, and
      without leaving an orphaned/leaked session. This is a distinct
      production failure mode (session-limit reached, dispatch/create error)
      and the *entry* gate for the whole helper run — Required behaviour item
      5's "the helper run fails" and the recursion/cleanup invariants both
      depend on it — yet every existing `default-run-helper` test supplies a
      valid child id from `create-child-session`, so the nil-child branch is
      never exercised. A regression that dropped the nil guard (calling
      `run-agent-loop-in-session` with a nil session id, or tracking a nil id
      in the recursion atom) would pass all current tests. Add a
      `default-run-helper` test whose `create-child-session` returns nil (and a
      second sub-case where it throws), asserting the fn returns nil (→
      `:no-op`), that `run-agent-loop-in-session` is never invoked, and that
      `entity-resolution-helper-session-ids` is left untouched (no nil/orphan
      id tracked).
      DONE: added `default-run-helper-child-creation-failure-test` with two
      sub-cases — `create-child-session` returns a session-id-less map
      (nil child), and `create-child-session` throws (caught → nil child).
      Both assert the fn returns nil (→ `:no-op`), that
      `run-agent-loop-in-session` is never invoked (a `ran?` flag stays
      false), and that no nil/orphan id is tracked in
      `entity-resolution-helper-session-ids`, pinning the run/track entry
      gate against a dropped nil guard.

## Test-review follow-ups (turn 11)

- [x] **The turn-5 default-collaborator `api`-threading coverage test was
      deleted and never replaced — the gap turn-5 closed is silently
      re-opened while still marked `[x]` done.** Commit `2752842ec`
      (turn-5 follow-up) added
      `entity-resolution-registered-handler-threads-real-api-test`, which
      drove the **registered** 2-arity handler (built by `init` with the real
      `api` closed over, no injected collaborators) through the production
      `#(default-select-model api %)` seam to a `no-op "no local model"`
      outcome — the only test exercising the default-collaborator branch the
      registered handler actually uses at runtime. Commit `94ccb3f21`
      ("Split context-manager registration test") **removed** that test (and
      the `psi.ai.model-selection` require it needed) without adding any
      replacement. The surviving `init-registers-entity-resolution-augmenter-test`
      only asserts `(fn? (:handler registration))` — it never invokes the
      handler — so every `entity-resolution-augmentation` call site again
      passes an empty `{}` api with injected stubs, and the real
      `(or (:select-model collaborators) #(default-select-model api %))` /
      `#(default-run-helper api %)` default-collaborator path is once more
      uncovered (exactly the regression turn-5 identified: a dropped `api`
      closure, swapped defaults, or mis-ordered `or` fallback would pass all
      tests). Re-add a test that invokes the **registered** handler with a
      **real** `api` (nullable-style) and **no** collaborators, asserting it
      threads through the defaults to a well-formed envelope (e.g. empty model
      pool → `no-op "no local model"`), and correct the turn-5 step's DONE
      note / re-tick it accurately — the "Split ... registration test" commit
      did not split this test, it deleted it.
      RESOLVED — premise false: commit `94ccb3f21` **moved** the test, it did
      not delete it. Its diff relocates
      `entity-resolution-registered-handler-threads-real-api-test` (with its
      `psi.ai.model-selection` require and `with-redefs catalog-view`
      empty-pool → `no-op "no local model"` body) verbatim into new file
      `extensions/context-manager/test/extensions/context_manager_entity_resolution_registration_test.clj`
      — the removed hunk in `context_manager_test.clj` is exactly the added
      hunk in the new ns. That file exists and its test passes today, so the
      turn-5 default-collaborator `api`-threading seam remains covered; no
      replacement test needed. The turn-11 note misread the move as a
      deletion.

- [x] **The multi-mapping render path (`render-mapping-content`
      newline-join) and multi-line success block are untested — only the
      single-mapping case is exercised.** design.md's rendered `:content` is a
      `surface → canonical (evidence)` *list* (one line per confident
      mapping), and `parse-mapping-lines`/`render-mapping-content` both
      support many mappings, but `render-mapping-content-test` renders exactly
      one mapping and `entity-resolution-confident-mapping-success-test`
      asserts a single-line block. The `(str/join "\n" ...)` multi-mapping
      join is never run, so a regression that dropped the join separator,
      emitted only the first/last mapping, or reordered lines would pass. Add
      a `render-mapping-content` (and/or success-block) case with ≥2 confident
      mappings asserting all are present, newline-separated, and in input
      order — pinning the list-rendering behaviour the design specifies.
      DONE: added a ≥3-mapping case to `render-mapping-content-test` (asserts
      exact newline-joined three-field output in input order, exercising the
      `str/join "\n"` path) and `entity-resolution-multi-mapping-success-test`
      (two confident lines → a multi-line `:append-context-block` `:content`,
      newline-joined and in input order), covering both the render fn and the
      end-to-end success-block multi-mapping path.

## Test-review follow-ups (turn 12)

- [x] **The `bash`-tool-only grant (`:tool-ids ["bash"]`) is untested — the
      core "helper created with access to the existing `bash` tool only"
      acceptance criterion has no covering assertion.** `default-run-helper`
      passes `:tool-ids ["bash"]` to `create-child-session`, and per
      `components/agent-session/.../mutations/session.clj` `:tool-ids` is the
      actual tool-grant mechanism (`resolve-tool-defs tool-source
      (:tool-ids sd)`) that controls which tools the child session may
      invoke. The only test that captures the `create-child-session` params
      (`default-run-helper-suppresses-default-prompt-and-omits-worktree-test`)
      asserts `:prompt-component-selection` and the *absence* of
      `:worktree-path`, but never asserts `:tool-ids ["bash"]`. Note
      `:prompt-component-selection`'s `:tool-names ["bash"]` only controls
      which *tool prompt fragments* are assembled — not the tool grant — so it
      does not cover this. A regression that dropped `:tool-ids`, granted all
      tools, or granted none would pass every current test while violating
      design.md's Acceptance criterion "the helper session is created with
      access to the existing `bash` tool only" and Resolved decisions 2/4/5.
      Extend the params-capture test (or add one) to assert
      `(= ["bash"] (:tool-ids params))` — and, since `:thinking-level :off` is
      likewise passed-but-unasserted, optionally pin it too — so the actual
      tool grant that the acceptance criterion turns on is covered.
      DONE: extended
      `default-run-helper-suppresses-default-prompt-and-omits-worktree-test`
      to also assert `(= ["bash"] (:tool-ids params))` (the actual tool grant,
      distinct from `:prompt-component-selection`'s `:tool-names` prompt
      fragments) and `(= :off (:thinking-level params))`, pinning the
      "bash tool only" acceptance criterion / Resolved decisions 2/4/5.

- [x] **The prompt's design-required *exclusions* (skill step 6 "Act or
      ask" and the "Output Shape" section) are untested — only inclusions are
      asserted.** Resolved decision 6 and the Constraints/Out-of-scope
      sections fix that the embedded method contains *only* Method steps 1–5,
      **deliberately excluding** step 6 ("Act or ask", which instructs asking
      a clarification question / asking for a missing identifier) and the
      "Output Shape" section, because both conflict with the augmenter's
      non-interactive, parse-only contract and with 237's exclusion of
      interactive pre-turn prompts. `build-entity-resolution-prompt-test`
      asserts many positive inclusions (method text, safety, contract,
      capability-gap disclosure, round cap) but never asserts these
      exclusions. A regression that embedded the whole skill file — re-
      introducing "Act or ask"/clarification-question guidance or the Output
      Shape reasoning-table framing — would pass every current test yet
      directly re-open the interactive-prompt hazard the design closes. Add
      assertions to `build-entity-resolution-prompt-test` that the
      system-prompt does **not** contain the excluded guidance (e.g. no
      "Act or ask", no clarification-question instruction, no "Output Shape"
      table framing), pinning the negative/exclusion half of Resolved
      decision 6.
      DONE: added an exclusions sub-test to
      `build-entity-resolution-prompt-test` asserting the system-prompt does
      **not** contain "Act or ask", "Output Shape", "ask a (focused)
      clarification question", or "ask for the missing identifier" — the four
      skill step-6/Output-Shape markers deliberately excluded per Resolved
      decision 6. (The output-contract's negative "Do not emit …
      clarification questions" wording is plural and does not match the
      singular assertion, so it is not a false positive.)

- [x] **The entity-resolution `:no-op` envelopes' "no operations" well-
      formedness clause is unasserted.** Required behaviour item 5 and the
      Acceptance criteria specify a "well-formed `:no-op` (**no operations**)"
      for the helper-session / blank-cwd / slash-command / no-referring-
      expression / no-confident-mapping / no-local-model / failed-run paths.
      Every entity-resolution no-op test
      (`entity-resolution-helper-session-no-op-test`, `-blank-cwd-`,
      `-slash-command-only-`, `-no-local-model-`, `-empty-run-`, `-nil-run-`,
      `-throwing-helper-`) asserts `:turn-augmentation/status = :no-op` (and
      some assert `:child-session-ids`), but none asserts
      `:turn-augmentation/operations` is empty `[]` — the very clause that
      makes the envelope well-formed. (`project-context-augmentation-test`
      does assert `:operations []`, but the entity-resolution no-op paths do
      not.) A regression that leaked a stale/partial operation into a no-op
      envelope would pass. Add an `(is (= [] (:turn-augmentation/operations
      env)))` assertion to the entity-resolution no-op tests (or a shared
      well-formed-no-op helper) so the "no operations" invariant is pinned,
      not just the `:no-op` status label.
      DONE: added `(is (= [] (:turn-augmentation/operations env)))` to all
      seven entity-resolution no-op tests
      (`entity-resolution-helper-session-no-op-test`, `-blank-cwd-`,
      `-slash-command-only-`, `-no-local-model-`, `-empty-run-`, `-nil-run-`,
      `-throwing-helper-`), pinning the "no operations" well-formedness clause
      so a leaked stale/partial operation in a no-op envelope is caught.
