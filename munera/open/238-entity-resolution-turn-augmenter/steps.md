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

## Test-review follow-ups (turn 14)

- [x] **The confidence-required-field reject boundary is untested — a
      mapping-shaped line whose trailing group carries no confidence token is
      silently dropped, but no test isolates that reject case.** Resolved
      decision 6 makes the confidence token a **required** field of the line
      format ("The confidence token remains a **required** field of the line
      format — the model must state its confidence explicitly, which
      reinforces the self-gating discipline"). `parse-mapping-line`
      enforces this by requiring `(str/last-index-of inner ";")` to be
      non-nil: a mapping-shaped line whose trailing `(...)` group contains no
      `;` (e.g. `foo → bar (just evidence)`) yields `semi` nil → the whole
      line is dropped (verified live: `parse-mapping-lines
      "foo → bar (just evidence)"` ⇒ `[]`). But no `parse-mapping-lines-test`
      case isolates this: the existing "discards everything else" block feeds
      `"Should I proceed? (question)"` (a `;`-less trailing group) but only
      asserts the *total* parsed count is 2, conflating the no-arrow and
      no-semicolon reject reasons — it never pins that a genuinely
      arrow-bearing, mapping-shaped line lacking the required confidence
      token is rejected. A regression that made the semicolon/confidence
      split optional (accepting a line with evidence but no confidence, or
      treating the whole inner group as evidence with an empty confidence)
      would pass every current test while violating the "confidence remains a
      required field" contract and re-admitting confidence-less lines the
      self-gating discipline is meant to exclude. Add a `parse-mapping-lines-test`
      case asserting an arrow-bearing line whose trailing group has no `;`
      (e.g. `"the fn → foo/bar (exact path)"`) is rejected (`[]`), isolating
      the confidence-required reject boundary distinct from the no-arrow and
      empty-field rejects.
      DONE: added a `parse-mapping-lines-test` case asserting an arrow-bearing,
      mapping-shaped line whose trailing `(...)` group lacks a `;` (no
      confidence token) — `"the fn → foo/bar (exact path)"` — is rejected
      (`[]`), isolating the confidence-required reject boundary (`semi` nil →
      drop) from the pre-existing no-arrow / empty-field reject cases.

## Test-review follow-ups (turn 15)

- [x] **`render-history-excerpt` truncates mid-line/mid-word, injecting a
      corrupt role-less partial fragment as the first excerpt "line" — the
      current char-slice behaviour is neither pinned nor guarded.** The
      over-long branch does `(subs text (- (count text) max-history-chars))`,
      a raw *character* slice on the newline-joined excerpt. Verified live:
      for the exact fixture the turn-8 test builds, the excerpt's first line
      becomes `"ng words here padding words here …"` — sliced mid-word, with
      no leading `User:` role prefix, and the truncated-away head loses its
      role label entirely. So in production the excerpt's leading line is a
      syntactically corrupt, role-less fragment fed to the local model as if
      it were conversation content (it can look like partial prose the model
      then anaphora-resolves against, or — with adversarial history text —
      resemble a mapping-shaped tail). The turn-8
      `build-entity-resolution-prompt-tail-truncation-test` asserts only the
      char-bound (`≤ 4000`), tail-marker presence, and head-marker absence; it
      never asserts truncation lands on a line boundary or that no corrupt
      partial line survives, so the current mid-line/mid-word cut is unpinned:
      a regression is impossible to catch and, more importantly, the corrupt-
      first-line behaviour is not an intended contract. Either (a) truncate at
      a line boundary (drop whole leading lines until the joined text fits, so
      every surviving line keeps its `Role:` prefix and is intact) and add a
      test asserting the first excerpt line starts with a `Role:` prefix and
      no mid-word split occurs; or (b) explicitly accept and document the
      char-slice as a benign truncation and add a test that pins the resulting
      partial-first-line shape as deliberate — so the accept/reject boundary
      of the truncation is a stated contract, not incidental `subs` behaviour.
      DONE: chose (a) — line-boundary truncation. Added `tail-lines-within`
      helper; `render-history-excerpt` now drops whole *leading* lines until
      the joined tail fits `max-history-chars` (keeping the last line alone if
      it alone exceeds the limit), so every surviving line keeps its intact
      `Role:` prefix — no mid-word/role-less fragment. Extended
      `build-entity-resolution-prompt-tail-truncation-test` to assert every
      surviving excerpt line matches `Role: …`, pinning line-boundary
      truncation against a regression to raw char-slicing.

- [x] **The success-envelope provenance clause (`:child-session-ids`) is
      asserted inconsistently across the sibling success tests — a regression
      dropping child-id provenance on the multi-mapping / model-flow /
      ambiguous-dropped paths would pass.** design.md's Acceptance criteria
      require helper session ids be "reported in
      `:turn-augmentation/child-session-ids`" on every success envelope. Only
      `entity-resolution-confident-mapping-success-test` asserts
      `(= ["helper-7"] (:turn-augmentation/child-session-ids env))`;
      `entity-resolution-multi-mapping-success-test` (child-id "helper-8"),
      `entity-resolution-selected-model-flows-into-run-test`, and
      `entity-resolution-ambiguous-dropped-test` (child-id "helper-9") assert
      only `:success` + `:content` and never the provenance field. This is a
      consistency gap in the success-path test cluster (test-shaper
      consistency/economy): the same envelope contract is checked on one
      sibling but silently omitted on the others, so `:child-session-ids`
      regressing to `[]` on the multi-mapping/model-flow/ambiguous paths
      slips through. Add the `:child-session-ids` assertion to the other
      success tests (or factor a shared success-envelope well-formedness
      helper mirroring the shared `:operations []` no-op assertion) so the
      provenance clause is pinned uniformly across the success cluster, not
      just on one representative.
      DONE: added `(= [helper-id] (:turn-augmentation/child-session-ids env))`
      provenance assertions to `entity-resolution-multi-mapping-success-test`
      ("helper-8"), `entity-resolution-selected-model-flows-into-run-test`
      ("helper-1"), and `entity-resolution-ambiguous-dropped-test` ("helper-9"),
      matching `entity-resolution-confident-mapping-success-test` so the
      `:child-session-ids` clause is pinned uniformly across the success
      cluster.

## Test-review follow-ups (turn 16)

- [x] **`default-select-model`'s `catch Exception` branch — the sole guard
      against a thrown model selection propagating onto 237's blocking
      pre-turn path — is untested.** `default-select-model` wraps its whole
      body in `(try … (catch Exception _ nil))`, so a throw from
      `(:query-session api)` or from `model-selection/resolve-selection`
      collapses to `nil` (→ `no-op "no local model"`). This catch is
      load-bearing: unlike `run-helper`, the augmenter does **not** wrap
      `select-model` in its own try/catch (turn 8 added `(try … (catch
      Throwable _ nil))` around `run-helper` only), so `default-select-model`'s
      internal catch is the *only* thing stopping a thrown selection from
      propagating out of `entity-resolution-augmentation` onto the blocking
      critical path. Yet every existing `default-select-model` test drives the
      happy `:ok` path (`-rejects-cloud-winner`, `-accepts-local-winner`) or,
      via the registered-handler test, the `:no-winner` empty-pool path — none
      exercises a *throwing* `query-session`/catalog. A regression that
      dropped the catch (or narrowed it) would pass all current tests while
      re-opening the exact "thrown-collaborator-must-not-propagate" hazard
      turn 8 closed for `run-helper`. Add a `default-select-model` test whose
      `:query-session` (or injected `:catalog`) throws, asserting the fn
      returns nil (→ `:no-op`, not a propagated exception); optionally also
      assert the augmenter-level path (`entity-resolution-augmentation` with a
      throwing real `default-select-model`) collapses to
      `no-op "no local model"` rather than throwing, pinning the select-side
      failure contract symmetrically with the run-side one.
      DONE: added `default-select-model-catches-thrown-selection-test` — a
      `:query-session` that throws drives the real `default-select-model`, and
      the test asserts it returns nil (caught → `:no-op`) rather than
      propagating, pinning the select-side failure guard symmetrically with the
      turn-8 run-side one.

- [x] **`history-line`'s whitespace-collapse (`str/replace text #"\s+" " "`)
      — which flattens a multi-line/multi-space snippet into a single intact
      excerpt line — is untested; every history fixture uses single-space
      snippets.** `render-history-excerpt`/`history-line` normalize each
      tail snippet's internal whitespace to single spaces so a multi-line
      snippet renders as *one* `Role: …` line, not several lines (only the
      first of which would carry the `Role:` prefix). This matters because the
      turn-15 line-boundary-truncation invariant ("every surviving excerpt
      line keeps a `Role:` prefix") and the anaphora excerpt's readability
      both depend on one-snippet-one-line: a snippet containing an embedded
      `\n` that was *not* collapsed would inject a role-less continuation line
      into the excerpt (the same class of corrupt-fragment defect turn 15
      fixed for char-slicing, but from the render side). All existing fixtures
      (`build-entity-resolution-prompt-test`, `-tail-truncation-test`) use
      single-space snippets, so the `\s+ → " "` collapse never runs against
      embedded newlines/tabs/runs. A regression dropping the collapse (or
      replacing only literal spaces) would pass every test yet re-admit
      multi-line excerpt lines. Add a `build-entity-resolution-prompt` /
      `render-history-excerpt` case with a `:snippet` containing an embedded
      newline / tab / multi-space run, asserting the rendered excerpt line is
      a single `Role: …` line with internal whitespace collapsed to single
      spaces (no embedded newline survives, no role-less continuation line).
      DONE: added a whitespace-collapse case to
      `build-entity-resolution-prompt-test` — a `:snippet` with an embedded
      newline, tab, and multi-space run (`"look at\nthe\t pathom   resolver"`)
      renders as the single line `"User: look at the pathom resolver"`; asserts
      the exact collapsed line and that the excerpt is exactly one line (no
      role-less continuation), pinning the `\s+ → " "` collapse from the render
      side (complementing the turn-15 char-slice line-boundary invariant).

## Test-review follow-ups (turn 17)

- [x] **The parent-session-model context inheritance in `default-select-model`
      is untested — a design-required behaviour (Required behaviour item 2 /
      plan decision 4: "inheriting the parent session's model as context, like
      `auto-session-name`") with zero coverage.** `default-select-model` calls
      `(:query-session api)` on the parent session-id to fetch
      `:psi.agent-session/model-provider` / `:psi.agent-session/model-id`, and
      `helper-model-selection-request` threads that into
      `resolve-selection`'s `:context {:session-model {:provider .. :id ..}}`
      (the `:same-provider-as-session` weak preference and provider-match
      tie-break depend on it). But every `default-select-model` test stubs
      `:query-session` to return `{}` (or omits it), so the parent model is
      always nil and the `:context`/`query-session` path is never exercised: a
      regression that dropped the `query-session` call, stopped reading the
      provider/id keys, or stopped building the `:session-model` context would
      pass all current tests while silently losing the design-required
      model-inheritance behaviour. Add a `default-select-model` (or
      `helper-model-selection-request`) test that supplies a `:query-session`
      returning a concrete
      `{:psi.agent-session/model-provider .. :psi.agent-session/model-id ..}`
      and asserts the parent model flows into the selection request's
      `:context {:session-model {...}}` (e.g. capture the `:request` passed to
      an injected/`resolve-selection` seam, or assert on
      `helper-model-selection-request` directly), pinning the parent-model
      context inheritance distinct from the happy-path ranking assertions.
      DONE: added two tests. `helper-model-selection-request-inherits-parent-
      model-test` pins the request builder — a parent model-ctx flows into
      `:context {:session-model {:provider (keywordized) :id}}` (and a nil
      parent → nil provider/id). `default-select-model-inherits-parent-model-
      context-test` drives the real `default-select-model` with a
      `:query-session` returning a concrete parent model, asserts the parent
      session is queried for `[:model-provider :model-id]`, and — via two
      equally-qualifying local candidates from different providers — asserts
      the parent-provider candidate wins through the inherited
      `:same-provider-as-session` context, proving the query→context wiring
      distinct from happy-path ranking.

- [x] **The `deref`-throws (`::error`) branch of `default-run-helper` is
      untested — only the `::timeout` and settled branches are covered.**
      `(try (deref fut budget-ms ::timeout) (catch Exception _ ::error))`
      guards against the run future's blocking call throwing an
      *uncaught* exception that surfaces through `deref` (e.g. an
      `ExecutionException`); on `::error` the fn falls to the settled branch
      where `(map? ::error)` is false → `:text nil` (→ `:no-op`).
      `default-run-helper-gates-on-run-ok-test` covers ok?-false, the timeout
      test covers `::timeout`, and the child-creation-failure test covers the
      pre-run gate, but no test drives a `run-agent-loop-in-session` that
      *throws* out of the future so the `::error` deref-catch runs. A
      regression that dropped the deref-catch (letting the exception propagate
      onto 237's blocking pre-turn path) or mis-handled `::error` would pass.
      Add a `default-run-helper` case whose `run-agent-loop-in-session` throws,
      asserting `:text` is nil (→ `:no-op`), the exception does not propagate,
      and the child is still closed/untracked by the future's `finally`.
      DONE: added `default-run-helper-run-throws-deref-error-branch-test` — a
      `run-agent-loop-in-session` that throws surfaces through `deref` as an
      `Exception`, caught → `::error` → settled branch (`map? ::error` false)
      → `:text nil` (→ `:no-op`), with no propagation (the fn returns a
      settled `{:child-session-id ..}` result), and the future's own `finally`
      still closes + untracks the child. Distinct from the ok?-false,
      `::timeout`, and pre-run-gate branches.

## Test-review follow-ups (turn 18)

- [x] **The no-op *diagnostic* — the field that distinguishes *why* a no-op
      occurred — is asserted for only one of the four entity-resolution
      no-op reasons; the other three are unpinned, so a swapped/dropped/
      wrong diagnostic passes.** `entity-resolution-augmentation` emits four
      distinct `:turn-augmentation/diagnostic` strings across its no-op paths
      (`"no effective cwd"`, `"slash-command-only prompt"`, `"no local
      model"`, `"no confident mapping"`) plus a *diagnostic-less*
      `(no-op-envelope)` for the tracked-helper recursion path. Only
      `"no local model"` is asserted
      (`entity-resolution-no-local-model-no-op-test`,
      `entity-resolution-registered-handler-threads-real-api-test`); the
      remaining no-op tests (`entity-resolution-blank-cwd-no-op-test`,
      `-slash-command-only-`, `-empty-run-`, `-nil-run-`, `-throwing-helper-`,
      `entity-resolution-helper-session-no-op-test`) assert only
      `:turn-augmentation/status = :no-op` and `:operations []` — never the
      diagnostic. The diagnostic is the sole observable that names the no-op
      *reason* (meaningful-failures: a failing test should explain which
      contract was violated), and this is exactly a consistency gap across the
      no-op cluster (test-shaper consistent-assertion-style): the
      status/operations clauses are checked uniformly but the reason-string is
      checked on one representative only. A regression that returned the wrong
      diagnostic on the slash-command path (e.g. `"no confident mapping"`),
      dropped the `"no confident mapping"`/`"slash-command-only prompt"` string
      entirely, or emitted a spurious diagnostic on the diagnostic-less
      recursion path would pass every current test. Add the diagnostic
      assertion to each entity-resolution no-op test — `"slash-command-only
      prompt"` (slash-command), `"no confident mapping"` (empty-run / nil-run /
      throwing-helper), `"no effective cwd"` (blank-cwd), and the *absence* of
      a `:diagnostic` key on the tracked-helper recursion no-op — so the reason
      each no-op reports is pinned uniformly, mirroring the shared
      `:operations []` assertion the turn-14 note added.
      DONE: added the diagnostic assertion to each entity-resolution no-op
      test — `"no effective cwd"` (`-blank-cwd-`), `"slash-command-only
      prompt"` (`-slash-command-only-`), `"no confident mapping"` (`-empty-run-`
      / `-nil-run-` / `-throwing-helper-`), and the *absence* of a
      `:turn-augmentation/diagnostic` key on the tracked-helper recursion no-op
      (`entity-resolution-helper-session-no-op-test`). The reason each no-op
      reports is now pinned uniformly, mirroring the shared `:operations []`
      assertion; a swapped/dropped/spurious diagnostic is caught.

- [x] **`default-run-helper`'s `:model`-forwarding branch is untested at the
      real-fn level — the `cond->` that threads the selected model into
      `run-agent-loop-in-session`'s params never runs the model-present arm in
      any `default-run-helper` test.** `default-run-helper` builds the run
      params as `(cond-> {:prompt user-prompt} model (assoc :model model))`, so
      the selected model reaches the actual `run-agent-loop-in-session` call
      only via that conditional `assoc`. Every `default-run-helper` test
      (`-gates-on-run-ok-`, `-settled-run-closes-`, `-timeout-branch-`,
      `-child-creation-failure-`, `-run-throws-deref-error-`,
      `entity-resolution-recursion-loop-end-to-end-test`) invokes
      `default-run-helper` **without** a `:model` run-opt, so the
      `model`-present arm never fires and the real fn is never asserted to
      forward `:model` into the params passed to `run-agent-loop-in-session`.
      The turn-9 `entity-resolution-selected-model-flows-into-run-test` covers
      the selection→run seam only through a **stubbed** `:run-helper` (it
      captures `opts` at the augmenter boundary), not through the real
      `default-run-helper`'s `cond->`/params construction. A regression that
      dropped the `(assoc :model model)` arm, mis-keyed it, or moved the model
      out of the params `run-agent-loop-in-session` receives would pass every
      test — the production default collaborator would silently run the helper
      under the wrong/default model. Add a `default-run-helper` case that
      supplies a concrete `:model`, captures the params passed to
      `run-agent-loop-in-session` (via `fake-run-api`), and asserts the
      selected `:model` is present in those params — pinning the model-present
      `cond->` arm at the real-fn level, distinct from the stub-boundary
      selection→run assertion.
      DONE: extended `fake-run-api` with a `:run-calls` atom recording the
      `run-agent-loop-in-session` params, and added
      `default-run-helper-forwards-selected-model-test` — supplies a concrete
      `:model`, drives the real `default-run-helper`, and asserts the selected
      `:model` (and `:prompt`) are present in the captured run params
      (model-present `cond->` arm); a second sub-case supplies no `:model` and
      asserts `:model` is absent from the params (nil arm), pinning both arms
      at the real-fn level distinct from the turn-9 stub-boundary assertion.

## Test-review follow-ups (turn 19)

- [x] **The test-suite split (`866f505db`/`d7103d389`/`4db2ff0da`) left the
      shared fixtures duplicated and — in one case — divergent across the six
      test files, violating test-shaper `consistent(fixtures ∧
      test_abstractions)` and `economical(minimal_incidental_variation)`.**
      `base-tp` is defined **three** times, byte-identical, in
      `context_manager_test.clj`, `context_manager_model_selection_test.clj`,
      and `context_manager_entity_resolution_flow_test.clj` — three copies of
      the same turn-projection fixture that must now be kept in lock-step by
      hand (a change to the projection shape, like the turn-6 `:tail` fix,
      would have to be applied in three places or silently drift). Worse, the
      `stub` collaborators helper is defined **twice under the same name but
      with different contracts**: `context_manager_test.clj`'s `stub` accepts
      `{:model :text :child-id :throw? :calls}` and its `:run-helper` returns
      `{:child-session-id :text}` (or throws), whereas
      `context_manager_model_selection_test.clj`'s `stub` accepts only
      `{:model :calls}` and its `:run-helper` returns **nil** and ignores
      `:text`/`:child-id`. Two same-named helpers with divergent shapes across
      sibling files is exactly the `locally_comprehensible`/consistency hazard
      test-shaper warns against — a reader who learns one `stub` will
      mis-read the other. Extract the shared `base-tp` (and a single
      canonical `stub`/collaborators builder, or clearly distinct names if the
      two shapes are genuinely different concerns) into a shared test-support
      ns the split files require, so the fixture is defined once and the two
      `stub` contracts stop colliding.
      DONE: created `extensions.context-manager-test-support` with a single
      canonical `base-tp` and `stub`. The two divergent `stub` contracts
      reconcile cleanly — the model-selection copy's only use passes
      `:model nil` (no-local path), where the augmenter no-ops before
      run-helper, so the canonical `stub`'s `:run-helper` return is
      irrelevant; both `:calls`-recording semantics preserved. All four
      files now `:refer` `base-tp`/`stub` from shared support; the three
      duplicate `base-tp` and two colliding `stub` defns removed.

- [x] **`await-untracked` (and its inlined poll-until-untracked loop) is
      duplicated four times across the split test files — the same
      settle-await ceremony, no shared helper.** The
      `(let [deadline (+ (System/currentTimeMillis) 2000)] (while (and
      (contains? @…helper-session-ids id) (< … deadline)) (Thread/sleep 5)))`
      poll appears as a named `await-untracked` defn in **both**
      `context_manager_test.clj` and `context_manager_helper_runtime_test.clj`
      (two copies of the identical fn), and is **inlined verbatim** a third
      time in `context_manager_helper_runtime_test.clj`'s
      `default-run-helper-timeout-branch-test` and a fourth in
      `context_manager_helper_failure_test.clj`'s
      `default-run-helper-run-throws-deref-error-branch-test`. This is
      ceremony that should be compressed into one shared helper
      (`helpers_that_compress(ceremony)`): four hand-maintained copies of a
      timing-sensitive async-settle poll invite drift (e.g. one copy's 2s
      deadline diverging) and obscure intent. Factor a single
      `await-untracked` into shared test support and call it from all four
      sites (the timeout/throws tests inline it only because the shared defn
      was not in scope after the split).
      DONE: `await-untracked` now lives once in
      `extensions.context-manager-test-support`. Both named defns removed
      and all four call sites (main recursion-loop, helper-runtime settled/
      forwards-model/timeout, helper-failure run-throws) call the shared
      helper; the two inlined poll loops replaced with `(await-untracked id)`.

- [x] **The `default-run-helper` collaborator double is abstracted as
      `fake-run-api` in `context_manager_helper_runtime_test.clj` but
      re-inlined ad hoc in the other files that drive the same seam —
      inconsistent test-double abstraction for one collaborator contract.**
      `context_manager_helper_runtime_test.clj` builds a reusable
      `fake-run-api` (records create/run/close params, returns a
      `:run-result`), but `context_manager_helper_failure_test.clj`
      (child-creation-failure, run-throws) and
      `context_manager_test.clj`'s `entity-resolution-recursion-loop-end-to-end-test`
      hand-roll their own bespoke `{:mutate-session … :mutate …}` maps for the
      **same** `default-run-helper` collaborator shape. The
      child-creation-failure/throwing cases need behaviours `fake-run-api`
      does not yet express (nil/thrown `create-child-session`, thrown
      `run-agent-loop-in-session`), so either extend `fake-run-api` with those
      injection points and route all `default-run-helper` tests through it, or
      document why a bespoke double is warranted per case — so there is one
      consistent test-double abstraction for the `default-run-helper` seam
      rather than four subtly different inline api maps.
      DONE: moved `fake-run-api` into shared support and extended it with the
      injection points the bespoke maps needed — `:create-result` (nil-child
      via `{}`), `:create-throws?`, `:run-throws?`, and `:block-until`/
      `:run-began` (uninterruptible blocking run for timeout/recursion). All
      `default-run-helper` seams — helper-runtime (gates/settled/forwards-
      model/prompt-selection/timeout), helper-failure (child-creation-failure/
      run-throws), and the main recursion-loop test — now build their api
      through the one `fake-run-api`; the four bespoke inline api maps removed.

- [x] **The augmenter's exception-safety is asymmetric and the `select-model`
      side is untested at the augmenter boundary.** turn-8 wrapped
      `run-helper` in `(try … (catch Throwable _ nil))` inside
      `entity-resolution-augmentation` so a throwing helper collapses to
      `:no-op`, and `entity-resolution-throwing-helper-no-op-test`
      (`stub {:throw? true}`) pins it. But `select-model` is **not** wrapped
      by the augmenter — turn-16 instead relies on `default-select-model`'s own
      internal `(catch Exception _ nil)`. That is a defensible design choice
      for the production default collaborator, but it means an **injected**
      `:select-model` collaborator that throws (or any future non-default
      select fn) propagates uncaught out of the augmenter onto 237's blocking
      pre-turn path — the exact hazard turn-8 closed on the run side — and no
      test exercises a throwing `:select-model` collaborator through
      `entity-resolution-augmentation` (the sibling `throw?`/no-op assertion
      exists only for `run-helper`). Either (a) make the augmenter symmetric —
      wrap the `select-model` call in the same defensive try/catch and add a
      throwing-`:select-model` → `:no-op` test mirroring the run-helper one; or
      (b) explicitly document that the augmenter trusts injected collaborators
      not to throw and that only the *default* select path is guarded (by
      `default-select-model`'s catch, already tested by
      `default-select-model-catches-thrown-selection-test`). Pick one so the
      select-side and run-side failure contracts are consistent rather than
      silently asymmetric.
      DONE: chose (a) — symmetric augmenter-boundary safety. Wrapped the
      `select-model` call in `entity-resolution-augmentation` in
      `(try … (catch Throwable _ nil))` mirroring the run-helper wrap, so a
      thrown selection collapses to the same no-model `:no-op`. Added
      `entity-resolution-throwing-select-model-no-op-test` (injected throwing
      `:select-model`) asserting a well-formed `:no-op` (`"no local model"`
      diagnostic, empty operations/child-ids, helper never runs), mirroring
      `entity-resolution-throwing-helper-no-op-test`.

## Test-review follow-ups (turn 20)

- [x] **`tail-lines-within`'s single-line-over-limit branch — the one place
      the excerpt is *deliberately* allowed to exceed `max-history-chars` —
      is untested, and it violates the length-bound invariant the turn-15
      test pins for the multi-line case.** `tail-lines-within` starts
      `kept = (list (last lines))` and only prepends earlier lines while the
      joined length stays `<= limit`; per its own docstring, "If the last
      (most recent) line alone exceeds `limit`, keep it alone … rather than
      emit nothing." So when a single most-recent snippet's rendered
      `Role: …` line exceeds `max-history-chars` (4000),
      `render-history-excerpt` returns that whole over-limit line and the
      excerpt is emitted *unbounded* (verified live: a 5000-char snippet →
      5006-char excerpt, `> 4000`). This is a distinct, documented behaviour
      (preserve the highest-value anaphora line over the char-bound) but it
      directly contradicts the turn-15
      `build-entity-resolution-prompt-tail-truncation-test`'s `(<= (count
      excerpt) 4000)` assertion, which only ever runs the *multi-line*
      drop-leading-lines path (its filler lines are ~3806 chars each, all
      individually under 4000). No test drives a single snippet whose
      rendered line exceeds the limit, so this accept-boundary is entirely
      unpinned: a regression that mid-cut the long single line (re-introducing
      the corrupt role-less fragment turn-15 fixed), emitted nothing, or
      changed the retained content would pass every current test, and the
      "excerpt is bounded" guarantee is silently false for this input class.
      Add a `render-history-excerpt` / `build-entity-resolution-prompt` case
      with a `:tail` of exactly one entry whose `:snippet` renders longer than
      `max-history-chars`, asserting the documented behaviour explicitly —
      either (a) if the over-limit-single-line-kept-whole behaviour is
      intended, assert the excerpt equals that one intact `Role: …` line
      (pinning it as a deliberate, documented exception to the char-bound and
      reconciling it with the turn-15 `<= 4000` assertion, e.g. by scoping
      that assertion to the multi-line case), or (b) if the char-bound must
      hold universally, change the behaviour to bound the single line too and
      assert `<= max-history-chars`. Pick one so the length-bound contract is
      a single coherent, tested statement rather than two silently-conflicting
      ones (one asserted for multi-line, the opposite documented-but-untested
      for single-line).
      DONE: chose (a) — the single-line-over-limit-kept-whole behaviour is the
      intended, already-documented contract (`tail-lines-within` docstring:
      "keep it alone … the highest-value anaphora context"), and bounding it
      (b) would re-introduce the mid-word/role-less fragment turn-15 fixed.
      Added `build-entity-resolution-prompt-single-line-over-limit-test`: a
      one-entry `:tail` whose rendered `User: …` line exceeds
      `max-history-chars` (4000), asserting the excerpt is kept whole
      (> 4000, deliberate exception), equals the one intact
      whitespace-collapsed `Role:` line, is exactly one line (no mid-word
      split / role-less fragment), and keeps its `User:` prefix. Scoped the
      turn-15 `<= 4000` assertion to the multi-line case (comment + message
      wording) so the length-bound contract is now one coherent, tested pair:
      multi-line ⇒ bounded (drop leading lines); single-over-limit ⇒
      kept-whole exception.

## Test-review follow-ups (turn 21)

- [ ] **The slash-command-only pre-filter has only one positive case
      (`"/status"`) and no negative-boundary test, leaving the augmenter's
      central purpose — resolving path-like references — unguarded against a
      mis-anchored predicate.** `entity-resolution-slash-command-only-no-op-test`
      pins that `"/status"` (a leading-slash whole-turn text) pre-filters to
      `:no-op` with no model selected and no helper created. But the
      *discriminating* case is the opposite: a normal prompt that merely
      *contains* a `/` mid-string — e.g. `"look at src/foo"` or
      `"fix components/pathom/resolver.clj"`, exactly the path-like references
      this augmenter exists to resolve — must **not** be pre-filtered; it must
      proceed to model selection / helper run. `slash-command-only?` is
      correctly anchored today (`str/starts-with? trimmed "/"`), but a
      regression to a naive `(str/includes? text "/")` (or a mis-anchored
      match) would silently route every path-bearing prompt into the
      slash-command `:no-op`, disabling the augmenter for its primary input
      class, and **no current test would catch it** — the only slash test uses
      a text that is *also* rejected by `includes?`, so it cannot distinguish
      the two predicates. Add a negative-boundary case (a mid-string-`/` user
      text through `entity-resolution-augmentation`) asserting the pre-filter
      does **not** fire: the turn reaches model selection / helper run
      (e.g. `:select`/`:run` recorded via the `stub` `:calls` atom, or a
      `:success`/`no confident mapping` diagnostic rather than
      `"slash-command-only prompt"`). Optionally also pin the leading-whitespace
      positive case (`"  /help"`) so both halves of the anchored predicate's
      contract — leading `/` after trim ⇒ skip; internal `/` ⇒ do not skip —
      are a single coherent, tested statement rather than one positive example
      that both candidate predicates satisfy.
