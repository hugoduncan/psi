# 230 — Plan

How to implement the unresolved-`SCOPE_QUESTION` lifecycle gate. `design.md` is
the authority for *what* and *why*; this file is *how*. Decision tags `D1–D5`
refer to the settled decisions in `design.md`; `DI-n` are implementation
decisions made here (these resolve the resolver-vs-step wiring `design.md`
explicitly deferred to plan.md, per D2).

## Strategy

Vertical slices, **inner mechanism before outer wiring**, each independently
shippable and reviewable, each `small` (one intent, one rule + test cluster),
behaviour-preserving for paths it does not explicitly change.

1. **Slice 1 — pure scanner** (`routing.clj`): the content→route parser, with
   unit tests. Inert; nothing calls it yet.
2. **Slice 2 — task-artifact read resolver**: reads-through-resolvers file read
   feeding the scanner (D2). Inert.
3. **Slice 3 — deterministic gate operation**: registers
   `workflow/scope-question-gate-routing`, bridging ctx→resolver→pure scanner
   (D4). Inert until authored.
4. **Slice 4 — wire the gate into `task-lifecycle.edn`** + the handback summary
   (D1), and update the brittle `task-lifecycle-test` in the same slice.
5. **Slice 5 — docs + coherence** (`doc/workflows.md`, `CHANGELOG.md`,
   `mementum/state.md` if warranted).

229 is already landed in this worktree's `task-lifecycle.edn`
(`check-design-review-status` + `final-summary-design/plan-not-converged`), so
this task builds on the post-229 shape and reuses the gate idiom (D1).

## DI-1 — Detection is a deterministic operation with a pure scanner core

D4 mandates a dedicated deterministic operation (no LLM), distinct from the
LLM-`PASS_STATUS`-parsing `pass-status-routing`. Split it:

- **Pure scanner** in `components/agent-session/src/psi/agent_session/workflow/
  routing.clj`: `parse-scope-question-gate` takes the artifact content (string or
  `nil`), the authored `marker`, and the two authored route labels
  (`proceed-route`, `open-route`), and returns the standard routing result
  `{:status :ok :data <route> :summary <route> :details {…}}`. No IO; unit-test in
  isolation (Slice 1). This sits alongside the existing `parse-*-routing`
  functions and reuses their result shape.
- **Operation handler** in `workflow/core.clj` `register-built-in-deterministic-
  operations!`: does the IO (resolver read) then calls the pure scanner (Slice
  3). The handler is the only impure part; the routing decision logic is pure and
  directly unit-tested.

Route labels (D4 — dedicated vocabulary, avoid `pass-status-routing` collisions):
`proceed-route = "DONE"` (no open question → continue the lifecycle) and
`open-route = "SCOPE_QUESTION_OPEN"` (≥1 open → halt to handback). Both are
authored EDN args, not hardcoded in the scanner (generic primitive, per the
workflow-runtime boundary: route labels belong in authored definitions).

### Scanner semantics (D5 — the checkbox is the signal)

Canonical marker form (D5): `- [ ] SCOPE_QUESTION: <concern>` — an **unchecked**
markdown checklist item whose prose begins with the marker `SCOPE_QUESTION:`.
The scanner, per authored `marker` (default authored value `"SCOPE_QUESTION:"`):

- An **open** item is a line that, after `str/triml`, matches an unchecked
  checkbox followed by the marker: `- [ ]` then optional whitespace then
  `<marker>`. Leading indentation is tolerated (`triml`); the checkbox-state
  literal `[ ]` (one space) is the unchecked signal.
- A **checked** item (`- [x]` / `- [X]`) with the same marker does **not** count
  (resolved — AC-2).
- `nil`/absent content or zero matching lines → `proceed-route` (AC-2).
- ≥1 open item → `open-route`, with `:details {:open-questions [<concern…>]}` so
  the handback can name the question(s) (AC-1). Each `:open-questions` entry is the
  **trimmed concern substring** — the text after the `marker` on the open line,
  `str/trim`med — **not** the raw matched line. This is the single defined shape;
  the Slice-1 unit tests assert exactly it.

The scanner is independent of any design-review convergence/`PASS_STATUS` signal
(D2 — content-based detection).

## DI-2 — Reads-through-resolvers: a generic task-artifact-content resolver

D2 requires the file read go through a **generic task-artifact read resolver**
(mirroring `resolvers/session.clj`, which already `slurp`s git/worktree files
inside a `defresolver`), feeding the pure scanner; the workflow-specific
`design-steps.md` path and `SCOPE_QUESTION:` marker stay in **authored EDN**.

- Add resolver `agent-session-task-artifact-content` in
  `components/agent-session/src/psi/agent_session/resolvers/session.clj` (next to
  `agent-session-cwd`, which it depends on):
  - input: `[:psi.agent-session/worktree-path :psi.munera/task-path
    :psi.munera/artifact-name]`
  - output: `[:psi.munera/task-artifact-content]`
  - body: resolve `(io/file worktree-path task-path artifact-name)`; if the file
    exists, return its slurped **working-tree** content; else `nil`.
- **Working-tree, not git HEAD (decision).** D3 resume is a stateless re-scan
  after the human checks the box and re-invokes; reading the working-tree file
  honours the current content whether or not the human has committed yet, and
  matches "the gate reads current `design-steps.md` content on each run". (Git
  history remains recoverable; the gate just needs current truth.)
- `task-path` / `artifact-name` are generic params; the operation seeds them. The
  resolver knows nothing about `design-steps.md` or `SCOPE_QUESTION:`.
- Register the resolver in `session-resolvers/resolvers` so it is in
  `all-resolvers` and reachable via `resolvers/query-in`.

## DI-3 — Operation wiring: ctx → resolver → scanner

The deterministic operation `workflow/scope-question-gate-routing` handler
receives the invocation map. **Critically, the gate runs as the `:judge` of the
`check-scope-question-status` `:invoke` step**, and the judge and direct-invoke
paths carry *different* session keys (verified in source):

- judge path (`workflow_judge/execute-invoke-judge!`) supplies `:ctx` +
  `:parent-session-id` and **no `:session-id`**;
- direct `/operation invoke` path
  (`deterministic_operation_action/build-invocation`) supplies `:session-id`
  (+ a conditional `:parent-session-id`).

The handler MUST therefore resolve the owning session id as
`(or (:parent-session-id invocation) (:session-id invocation))`, **preferring
`:parent-session-id`** — on the production judge path that is the parent
agent-session whose `agent-session-cwd` resolves the task's `worktree-path`.
Reading only `:session-id` would be nil on the judge path → nil session →
`agent-session-cwd`/resolver returns nil → `parse-scope-question-gate` fails open
to `proceed-route` → the gate silently never fires (the exact silent-default the
task exists to prevent). It:

1. Reads `task-path`, `artifact` (artifact filename), `marker`, `proceed-route`,
   `open-route` from `args` (all authored in EDN; `task-path` resolved from
   `:workflow-input`). Validates presence/string-ness, returning a `:status
   :error` result on malformed args (mirroring the other operations' error
   shape).
2. Normalizes `task-path` to a worktree-relative task directory (DI-4).
3. Runs `resolvers/query-in` with the session entity
   (`{:psi.agent-session/session-id (or parent-session-id session-id)}`) plus
   extra-entity `{:psi.munera/task-path <dir> :psi.munera/artifact-name
   <artifact>}`, querying `[:psi.munera/task-artifact-content]`.
4. Calls `routing/parse-scope-question-gate` on the resolved content with the
   authored marker + route labels, and returns its result.

The handler is the single IO seam (resolver read); the route decision is pure.
**Test mandate (DI-3):** because the production path is the `:invoke`-step judge
(which supplies `:parent-session-id`, not `:session-id`), the gate MUST be
exercised by a test that drives it through the real `:invoke`-step judge
invocation — not only the direct-invoke
(`deterministic-operation-action`/registry) harness, which supplies `:session-id`
and would pass while production fails (test/prod divergence). See Slice 3.

## DI-4 — Task-path normalization from workflow input (non-blocking residual)

The gate needs a concrete worktree-relative task directory. The authored arg
`:task-path {:from :workflow-input :path [:input]}` yields whatever string
`task-lifecycle` was invoked with. The exact input shape is not pinned by
`design.md` (the only residual ambiguity — see "Open questions"); the operation
**normalizes defensively**, mirroring `parse-munera-open-task-path-routing`'s
existing path grammar:

- If the input contains a `munera/(open|closed)/NNN-slug` substring, use that
  path (the task is **open** during a pre-plan gate run, so this will be
  `munera/open/NNN-slug`).
- Else, if the input is a bare `NNN-slug` token, construct
  `munera/open/NNN-slug`.
- Else, the resolver read yields `nil` → `proceed-route` (fail-open to no-op,
  AC-2 — never a false halt from an unparseable input).

The implementer MUST confirm the real `:workflow-input` shape against an actual
`task-lifecycle` invocation (e.g. `/delegate task-lifecycle {NNN-slug}` and the
`extract-task-knowledge` `{NNN-slug}` convention) before finalizing the
normalization, and lock the chosen shape with an operation test.

## DI-5 — Gate placement and fall-through (D1)

Per D1 the scope gate sits in the design→plan boundary and, when a run is **both**
non-converged (229) **and** has an open `SCOPE_QUESTION`, **the `SCOPE_QUESTION`
handback wins** (D1, stated as a settled decision — it names the specific scope
decision; 229's handback is generic). To make the scope handback *genuinely* win
in the both-case, the scope gate is evaluated **before** the 229
`check-design-review-status` convergence handback can terminate the run — i.e.
immediately after `review-task-design` (which produces `design-steps.md`) and
before `check-design-review-status`. This realizes D1's governing precedence
decision. (Residual: design.md's D1 phrasing places the gate "after
`check-design-review-status`", which is jointly unsatisfiable with its own
governing "scope handback wins" under linear routing — see `implementation.md`;
the follow-up may not edit design.md, so the plan implements the governing
decision and flags the placement phrasing for human reconciliation.)

Concretely, in `.psi/workflows/task-lifecycle.edn`:

- **Insert** `check-scope-question-status` (`:invoke`) at `:steps` index 1,
  immediately after `review-task-design` and before `check-design-review-status`.
  Mirror the gate idiom:
  ```
  :operation "workflow/constant-routing" :args {:route "DONE"}
  :judge {:type :invoke
          :operation "workflow/scope-question-gate-routing"
          :args {:task-path {:from :workflow-input :path [:input]}
                 :artifact "design-steps.md"
                 :marker "SCOPE_QUESTION:"
                 :proceed-route "DONE"
                 :open-route "SCOPE_QUESTION_OPEN"}}
  :on {"DONE" {:goto "check-design-review-status"}
       "SCOPE_QUESTION_OPEN" {:goto "final-summary-scope-question-open"}}
  ```
- **Leave `check-design-review-status` unchanged** (its `:on` stays
  `{"DONE" {:goto "create-task-plan"} "REPEAT" {:goto
  "final-summary-design-not-converged"}}`). Routing outcomes:
  - both non-converged **and** open `SCOPE_QUESTION` → the scope gate fires first
    → `SCOPE_QUESTION_OPEN` → scope handback. **Scope wins** (D1). ✓
  - converged but open `SCOPE_QUESTION` (the 022 gap) → scope gate
    `SCOPE_QUESTION_OPEN` → scope handback. ✓
  - non-converged, no open scope question → scope gate `DONE` →
    `check-design-review-status` `REPEAT` → 229 handback. ✓
  - converged, no open scope question → scope gate `DONE` →
    `check-design-review-status` `DONE` → `create-task-plan`. ✓
- **Append** `final-summary-scope-question-open` (`:session`) **last** in
  `:steps` (after `final-summary-plan-not-converged`), mirroring the existing
  not-converged handbacks: `:tools ["read" "bash"]`, contributions from
  `:workflow-original`; template instructs the model to (a) state the lifecycle
  stopped **before plan creation** because one or more open `SCOPE_QUESTION:`
  items remain in the task's `design-steps.md`, (b) **name the open question(s)**
  by independently reading `design-steps.md`, (c) ask the human to decide, record
  the decision + rationale in `design.md` (D5), check the item in
  `design-steps.md`, then re-invoke `task-lifecycle` to resume (D3), (d) not
  proceed to plan and not extract knowledge; explicit-terminal
  `:judge constant-routing "DONE"` + `:on {"DONE" {:goto :done}}`.

Fall-through safety (the 229 DI-1/DI-5 hazard): the new gate is an `:invoke` step
with an explicit `:on` (no leaf fall-through). The new handback is appended after
`final-summary-plan-not-converged`, which is itself explicit-terminal
(`:goto :done`), so no preceding leaf can fall through into it; the new handback
is last → also `:completed` by order and itself explicit-terminal.

## Slice detail

### Slice 1 — pure scanner (`routing.clj`) + unit tests
- Add `parse-scope-question-gate` (DI-1 semantics) to `workflow/routing.clj`.
- `routing_test.clj`: open item → `open-route` (+ `:open-questions` detail);
  only-checked items → `proceed-route`; `nil`/empty content → `proceed-route`;
  mixed checked+unchecked → `open-route`; indented (`triml`) unchecked item →
  `open-route`; canonical form `- [ ] SCOPE_QUESTION: …` matched; a non-marker
  unchecked item ignored; route labels are taken from args (not hardcoded).
- Exit: focused routing Scry green; clj-kondo clean.

### Slice 2 — task-artifact-content resolver + test
- Add `agent-session-task-artifact-content` to `resolvers/session.clj` (DI-2),
  register in `resolvers`.
- Resolver test (mirror existing session-resolver tests): present file → slurped
  content; absent file → `nil`; path composed from worktree-path + task-path +
  artifact-name. Use a temp worktree dir fixture.
- Exit: focused agent-session resolver Scry green; clj-kondo clean.

### Slice 3 — deterministic gate operation + invocation test
- Register `workflow/scope-question-gate-routing` in `workflow/core.clj`
  (DI-3). Add a `:description`. The handler resolves the owning session id as
  `(or parent-session-id session-id)` (DI-3).
- Operation invocation test (via `deterministic-operation-action`/registry, like
  `deterministic_operation_registry_test` / `operation_command_test`): seed a
  temp worktree with a task dir containing a `design-steps.md` fixture; invoke
  the operation with authored args; assert `DONE` for checked/absent and
  `SCOPE_QUESTION_OPEN` (+ named questions) for an unchecked item — covering
  AC-1 (halt route), AC-2 (no-op route incl. absent file), AC-3 (resume:
  re-invoking after the item is checked returns `DONE`). Also lock the DI-4
  input-shape normalization with one case.
- **Judge-path test (DI-3, required):** add a test that drives the gate through
  the real `:invoke`-step judge invocation (the production path), where the
  invocation supplies `:parent-session-id` and **no `:session-id`**; assert the
  gate resolves the worktree from `:parent-session-id` and still fires
  `SCOPE_QUESTION_OPEN` on an unchecked item. Guards the test/prod divergence the
  direct-invoke harness would otherwise mask.
- Exit: focused operation Scry green; clj-kondo clean.

### Slice 4 — wire `task-lifecycle.edn` + update `task-lifecycle-test`
- Edit `.psi/workflows/task-lifecycle.edn` per DI-5 (insert gate at **index 1**,
  immediately after `review-task-design` and before `check-design-review-status`;
  leave `check-design-review-status` `:on` unchanged; append handback last).
- Update `task-lifecycle-test` (`workflow_definitions_test.clj:655`) **in this
  slice** (the test is positionally hard-coded): count `13→15`; add
  `check-scope-question-status` (**index 1**) and `final-summary-scope-question-open`
  (last) to the name vector and `:invoke` (index 1) / `:session` (last) to the
  type vector; bump `(repeat 13 {})`→`(repeat 15 {})`. The delegate-step
  assertions select by `:type` (already DI-5-restructured from 229) and stay 6
  delegates — unchanged. The design-gate `:on` `"DONE"` target assertion stays
  `"create-task-plan"` (unchanged — the design gate is no longer repointed). Add
  assertions: the scope gate's `:judge` is `workflow/scope-question-gate-routing`
  with the authored args, and its `:on` routes
  `"DONE" → check-design-review-status` and
  `"SCOPE_QUESTION_OPEN" → final-summary-scope-question-open`; the handback step
  has `:tools ["read" "bash"]`, a template that names the open `SCOPE_QUESTION`
  and stops before plan creation, and terminates `:goto :done` (AC-4 definition
  coverage). A `delegate-steps` context assertion (`repeat 6`) is unaffected.
- Exit: focused workflow-loader Scry green; clj-kondo clean.

### Slice 5 — docs + coherence
- `doc/workflows.md`: document the pre-plan `SCOPE_QUESTION` gate in
  `task-lifecycle` (content-based, halts and hands back, resume by checking the
  item + recording the decision in `design.md` and re-invoking).
- `CHANGELOG.md` `[Unreleased] Changed`: `task-lifecycle` now halts before plan
  creation and hands back to the human when the task's `design-steps.md` has one
  or more unchecked `SCOPE_QUESTION:` items, naming them, instead of silently
  defaulting the scope decision.
- Coherence: re-read edited files (`sync`); update `mementum/state.md`
  workflow-gate bullet if warranted.

## Risks

- **R1 — input-shape assumption (DI-4).** The `:workflow-input` shape passed to
  `task-lifecycle` is not pinned by `design.md`. Mitigated by defensive
  normalization that fails **open** (unparseable input → no-op `proceed`, never a
  false halt) and an explicit implementer check against a real invocation,
  locked by an operation test. Non-blocking.
- **R2 — working-tree vs committed content (DI-2).** Reading the working-tree
  file means an uncommitted check resolves the gate (intended for D3 resume) but
  also that uncommitted noise could mask/raise a question. Accepted: the gate's
  job is to reflect *current* `design-steps.md` truth on each run; git remains
  the durable record.
- **R3 — definition-test drift (Slice 4).** `task-lifecycle-test` is positionally
  hard-coded (count, name/type vectors, `repeat`-count). Updated in the same
  slice that edits the `.edn` (the delegate-by-type selection from 229 already
  absorbs the index shift for the delegate assertions).
- **R4 — design-gate vs scope-gate precedence (DI-5).** A run that is both
  non-converged and has an open `SCOPE_QUESTION` routes via the **scope**
  handback: the scope gate is evaluated before the 229 convergence handback, so
  the `SCOPE_QUESTION` handback wins exactly as D1 mandates. Residual: design.md's
  D1 phrasing places the gate "after `check-design-review-status`", in tension with
  its own governing "scope handback wins" decision (jointly unsatisfiable under
  linear routing). The plan implements the governing decision; design.md's
  placement phrasing should be reconciled by the human (the follow-up may not edit
  design.md). Recorded in `implementation.md`.

## Open questions (non-blocking)

- **Exact `:workflow-input` shape** for `task-lifecycle` (full `munera/open/…`
  path vs bare `NNN-slug` vs free text). Resolved at implementation time by
  inspecting a real invocation; DI-4 normalization + fail-open covers all shapes
  meanwhile. Does not block planning.

## Out of scope (restate from design.md)

- The 229 engine `:on-max-iterations` change (landed/closed).
- A design-review guardrail that treats "design defers a decision to the human"
  as a halt condition at the review source (separate task; cross-reference only).
- No pre-close backstop gate (D1 — `SCOPE_QUESTION`s are only produced by design
  review; the single pre-plan gate covers the lifecycle).
