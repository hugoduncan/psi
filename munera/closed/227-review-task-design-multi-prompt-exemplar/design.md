# 227 — review-task-design multi-prompt exemplar

## Status

Design — prerequisite satisfied; ready to plan. Task **226 (multi-prompt
workflow steps)** has landed, so this task can now consume the real
multi-prompt grammar against a real authored workflow.

## Depends on

- **226** — satisfied. The repository now has multi-prompt `:session` steps
  (`:prompts` queue, per-prompt named groups, per-prompt text output
  addressing, post-drain single judge). This task uses those existing surfaces;
  it adds no new runtime capability.

## Intent

Rewrite `.psi/workflows/review-task-design.edn` so the three review phases —
`architecture-review`, `ambiguity-review`, `inconsistency-review` — run as **one
multi-prompt `:session` step** (three back-to-back turns in one shared child
session) instead of three separate session steps. The task design + architecture
sources are assembled into the conversation **once** (turn 1) and reused by the
later review turns via the shared live session, for token efficiency and shared
conversational context across the three reviews.

## Problem

Today each review phase is its own `:session` step with its own child session,
so each phase independently re-reads `design.md` and the architecture sources
(`AGENTS.md`, `META.md`, `doc/architecture.md`). That re-embeds the same source
material three times per review pass. The phases are also independent
conversations: the ambiguity/inconsistency reviews cannot see the
architecture review's reasoning.

## This is a deliberate topology redesign, not a faithful refactor

The live `review-task-design` topology is per-phase
**review → follow-up (mutates `design.md`/`design-steps.md`) → next review**:
each `*-follow-up` executes the recorded items and mutates the artifacts
*before* the next phase reviews. A multi-prompt step is architecturally *N turns
in one session → one post-drain route* (226), so it **cannot** reproduce the
interleaved per-phase follow-up. The merge therefore deliberately **trades**
interleaved per-phase follow-up for **batch-review-then-follow-up**:

- the three reviews run as three turns in one shared session against the **same
  un-followed-up** design (each later review sees the earlier reviews' replies in
  the live session context);
- the three per-phase `*-follow-up` steps **collapse into a single**
  `design`-profile follow-up step placed *after* the merged review step's
  post-drain route, executing the items accumulated across all three reviews in
  one batch.

Sanctioned by `λα. ¬compat(backward)` (topology change allowed; behaviour
preservation is not a goal). The "faithful to the live three-phase topology"
framing is explicitly **not** claimed.

## Merged `design-review` step session config

The merged `design-review` step must declare the shared session config at the
**step level**. Under the 226 multi-prompt grammar, a prompt-group's
`:prompt-workflow` imports the markdown body for that group; prompt frontmatter
(`tools`, `skills`, model config, etc.) is not a per-prompt config surface. The
workflow therefore must not rely on the existing review prompt frontmatters after
moving those prompts under `:prompts`.

Use the exact union required by the three current review prompt frontmatters:

```clojure
{:name "design-review"
 :type :session
 :tools ["read" "bash" "edit" "write"]
 :skills ["work-independently"
          "review-task-architecture"
          "task-design"]
 :prompts
 [{:name "architecture"
   :prompt-workflow "review-task-design-architecture-review.md"}
  {:name "ambiguity"
   :prompt-workflow "review-task-design-ambiguity-review.md"}
  {:name "inconsistency"
   :prompt-workflow "review-task-design-inconsistency-review.md"}]
 ;; post-drain judge/on routing as described below
 }
```

This preserves the capabilities the three separate review steps currently get
from their prompt frontmatter while still using one shared child session for all
three turns.

## Merged topology and route map

The workflow topology after the merge is exactly:

```text
design-review --DONE--> final-summary
design-review --REPEAT--> design-follow-up
design-follow-up --DONE, :max-iterations 6--> design-review
```

`design-review` is the only review step. `design-follow-up` is the only
follow-up step and uses the existing `review-follow-up-design.md` design
profile. The old `architecture-review`, `architecture-follow-up`,
`ambiguity-review`, `ambiguity-follow-up`, `inconsistency-review`,
`inconsistency-follow-up`, and `clarity-status` steps are removed rather than
kept as shims.

In EDN shape, the merged route-bearing steps are:

```clojure
{:name "design-review"
 :type :session
 :tools ["read" "bash" "edit" "write"]
 :skills ["work-independently"
          "review-task-architecture"
          "task-design"]
 :prompts
 [{:name "architecture"
   :prompt-workflow "review-task-design-architecture-review.md"}
  {:name "ambiguity"
   :prompt-workflow "review-task-design-ambiguity-review.md"}
  {:name "inconsistency"
   :prompt-workflow "review-task-design-inconsistency-review.md"}]
 :judge
 {:type :invoke
  :operation "workflow/pass-feedback-routing"
  :args {:architecture-text
         {:from {:step "design-review"
                 :prompt "architecture"
                 :output :final-llm-reply}}
         :ambiguity-text
         {:from {:step "design-review"
                 :prompt "ambiguity"
                 :output :final-llm-reply}}
         :inconsistency-text
         {:from {:step "design-review"
                 :prompt "inconsistency"
                 :output :final-llm-reply}}}}
 :on {"REPEAT" {:goto "design-follow-up"}
      "DONE" {:goto "final-summary"}}}

{:name "design-follow-up"
 :type :session
 :prompt-workflow "review-follow-up-design.md"
 :judge {:type :invoke
         :operation "workflow/constant-routing"
         :args {:route "DONE"}}
 :on {"DONE" {:goto "design-review" :max-iterations 6}}}
```

The `:max-iterations 6` bound lives on the transition **targeting**
`design-review`, not on `design-review`'s `REPEAT` transition to the follow-up.
Workflow iteration limits count target-step entries, so placing the bound on
`design-follow-up --DONE--> design-review` preserves the current "at most six
total review passes" contract. Placing the bound on
`design-review --REPEAT--> design-follow-up` would instead bound follow-up
entries and would not express the review-pass limit.

The merged step's judge routes on the **post-drain step result's per-prompt
reply outputs**, using `workflow/pass-feedback-routing` directly on those three
outputs. The operation must be tightened before the workflow is rewired so it is
both a validator and a pass-level disjunction:

- For every supplied prompt-reply arg, parse the text with the same
  `PASS_STATUS` grammar as `workflow/pass-status-routing`, with allowed statuses
  exactly `ACTIONABLE_FEEDBACK` and `REVIEW_COMPLETE`.
- If any reply is missing a `PASS_STATUS` line, has duplicate lines, has a
  malformed line, or uses a disallowed status, return a deterministic operation
  error and do **not** choose a route. This preserves the validation guard that
  the current unmerged workflow gets from each phase's separate
  `pass-status-routing` judge.
- If every reply is valid, route `REPEAT` when any parsed status maps to
  `ACTIONABLE_FEEDBACK`; otherwise route `DONE` when all parsed statuses are
  `REVIEW_COMPLETE`.

This is an in-place semantic tightening of the existing generic review-pass
operation, not a new multi-prompt runtime capability and not a filesystem-state
router. Existing `review-task-design`/`review-task-plan` pass-level clarity
routing is unchanged for valid phase outputs, but malformed pass outputs now
fail fast instead of being silently treated as "not actionable". The operation's
result details should identify the actionable keys and, on error, the per-key
validation failures so blocked workflow runs are diagnosable.

Because all three phases are merged, this validated three-key disjunction is the
same pass-level decision the live `clarity-status` judge computes over the
unmerged phase outputs, but the separate `clarity-status` step is no longer
needed: the pass-feedback judge is attached directly to `design-review`.

**Why not filesystem-state routing.** An alternative routed on whether
`design-steps.md` still has unchecked `- [ ]` items via a new
`workflow/open-checklist-items-routing` re-reading the file. Rejected: routing on
mutable external file state makes the decision depend on data **outside** the
workflow data-flow / event log, fighting `doc/workflows.md`'s deliberate design
that `clarity-status` "remembers whether any phase returned `ACTIONABLE_FEEDBACK`
from the phase outputs rather than re-reading task artifacts," and the VSM
`∀change → event → log → replayable` ethos (the judge normalizes a *step result*,
not a filesystem snapshot). Per-prompt reply routing keeps the merged exemplar
replay-faithful and deterministic and introduces no new routing operation.

## Prompt-body changes for shared context

The prompt files remain separate markdown bodies referenced by prompt-group
`:prompt-workflow`, but their body text must be adjusted for the single shared
session:

- `review-task-design-architecture-review.md` is the **turn-1 loader**. Its body
  must explicitly instruct the model to read the task's `design.md` and consult
  the architecture sources needed for the batch review (`AGENTS.md`, `META.md`,
  and `doc/architecture.md`) before producing the architecture review note and
  any design follow-up items.
- `review-task-design-ambiguity-review.md` runs after the architecture turn in
  the same child session. Its body must say to use the already-loaded `design.md`,
  architecture sources, and architecture-review reply from the shared session
  context. It should perform only targeted re-reads when specific referenced
  material is missing, ambiguous, or plausibly stale; it must not unconditionally
  re-read the whole design and architecture source set.
- `review-task-design-inconsistency-review.md` follows the same rule: use the
  shared session context and prior review replies by default, with targeted
  re-reads only for missing/stale facts or specific referenced artifacts needed
  to decide an inconsistency.

This is the concrete mechanism for acceptance criterion 1: the design and
architecture sources are assembled once by the first prompt, and later prompts
reuse them through the live session rather than re-embedding the same material.

## Single design follow-up semantics

`design-follow-up` executes newly added design-review follow-up items from the
**immediately preceding `design-review` batch**. In this merged workflow,
"preceding review pass" means the whole three-prompt batch
(`architecture` + `ambiguity` + `inconsistency`), not any one prompt within it.
The follow-up must therefore execute every unchecked `design-steps.md` item newly
added by any of the three prompt replies in that batch, while leaving unchecked
items that predate that batch untouched.

Because `design-follow-up` intentionally remains a shared design-profile prompt
(`:prompt-workflow "review-follow-up-design.md"`) rather than a bespoke runtime
operation, item selection is an agent-side evidence rule over the task files and
git history, not workflow routing:

1. At follow-up start, read the task's `design-steps.md` and `implementation.md`
   and inspect task-scoped git history for the immediately preceding review
   batch. The batch is the contiguous set of latest commits/implementation notes
   produced by the just-finished `design-review` prompts (`architecture`,
   `ambiguity`, and `inconsistency`) since the previous `design-follow-up`
   completion for the same task, or since task creation if no previous follow-up
   exists.
2. Determine the batch baseline as the parent of the oldest commit in that
   contiguous review-batch segment, then compare the baseline to current `HEAD`
   for the task's `design-steps.md` (for example,
   `git diff <baseline>..HEAD -- <task>/design-steps.md`). The candidate work
   set is exactly the checklist lines added by that diff that match unchecked
   design-step items and still exist unchecked in `design-steps.md` at follow-up
   start.
3. Execute only those candidate items. Pre-existing unchecked items, edited
   stale items whose addition cannot be attributed to the just-finished batch,
   and checked items are not in scope for this follow-up.
4. If the review-batch segment or baseline cannot be identified confidently, or
   if a diff-added checklist item cannot be matched unambiguously to a current
   unchecked item, leave the item unchecked and record the blocking reason
   tersely in `implementation.md` rather than guessing.

Update `review-follow-up-design.md` wording/context to make this batch meaning
and evidence rule explicit. The prompt should continue to say not to execute
stale unchecked items, but it should clarify that, for batch review workflows,
"newly added" spans all review prompts in the immediately preceding batch and is
identified by the git/task-file rule above.

## `final-summary` migration

The surviving `final-summary` step currently consumes the three phases via three
`{:step "<phase>-review" :yield :text}` contributions. The merge eliminates those
three step names, and `:yield` cannot recover per-phase text (per-prompt `:yield`
is invalid under 226; the merged step-level `:yield :text` resolves only to the
last prompt's reply). So `final-summary`'s three contributions migrate to
per-prompt `:output` refs:

```clojure
{:step "design-review" :prompt "architecture"   :output :final-llm-reply}
{:step "design-review" :prompt "ambiguity"      :output :final-llm-reply}
{:step "design-review" :prompt "inconsistency"  :output :final-llm-reply}
```

This is the only legal per-phase text addressing after the merge, and reuses the
same per-prompt reply addressing the post-drain routing already requires — no new
surface.

## Scope

In scope:

- Rewrite `review-task-design.edn`: replace the three review steps + three
  `*-follow-up` steps with one multi-prompt `design-review` step (three named
  prompt-groups reusing the existing
  `review-task-design-{architecture,ambiguity,inconsistency}-review.md` prompts)
  + one post-route `design`-profile follow-up step.
- Wire the post-drain `pass-feedback-routing` judge over the three per-prompt
  reply outputs; preserve the REPEAT pass loop (`:max-iterations` as today).
- Tighten `workflow/pass-feedback-routing` so it validates every supplied
  prompt/phase reply with the `ACTIONABLE_FEEDBACK|REVIEW_COMPLETE`
  `PASS_STATUS` grammar before choosing `REPEAT` or `DONE`.
- Migrate `final-summary` contributions to per-prompt `:output` refs.
- Update the review prompts only as needed for the shared-session, single-read
  framing (e.g. later reviews need not re-read sources already loaded).
- Workflow-loader / definition tests for the merged topology; update
  `doc/workflows.md`.

Out of scope:

- Any change to the multi-prompt runtime capability (that is 226).
- The plan/ and implementation review-workflow analogues (`review-task-plan`,
  `review-task-implementation`) — separate follow-ons if the exemplar proves out.

## Acceptance criteria

1. `review-task-design.edn` runs the three reviews as three turns of one
   multi-prompt `design-review` step in one shared child session; the design +
   architecture sources are assembled into the conversation once (turn 1).
2. Post-drain routing reuses `pass-feedback-routing` over the three per-prompt
   reply outputs; it first validates that every prompt reply contains exactly one
   allowed `PASS_STATUS: ACTIONABLE_FEEDBACK|REVIEW_COMPLETE` line, then routes
   REPEAT iff any phase returned `ACTIONABLE_FEEDBACK`, with the same pass-loop
   bound as today.
3. A single `design`-profile follow-up step after the route executes the items
   accumulated across all three reviews, identifying the batch-added unchecked
   `design-steps.md` items via the explicit git/task-file evidence rule and
   leaving ambiguous or stale unchecked items untouched with a terse block note.
4. `final-summary` recovers all three phases' text via per-prompt
   `{:step "design-review" :prompt "<phase>" :output :final-llm-reply}` refs.
5. Workflow-loader/definition tests cover the merged topology and routing;
   deterministic routing tests cover `pass-feedback-routing` valid DONE/REPEAT
   and invalid/missing/malformed status errors; `doc/workflows.md` describes the
   batch-review-then-follow-up shape and notes the deliberate departure from
   interleaved per-phase follow-up.
