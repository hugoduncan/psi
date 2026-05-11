# 138 — GitHub Extension: Label Operations and Workflow Adoption

## Intent

Extend the `psi/github` extension with deterministic label-mutation and PR-discovery
operations, then migrate all GitHub workflows to use these operations via `:invoke`
steps rather than delegating label changes and issue/PR selection to AI sessions.

## Problem

Two categories of friction exist in the current workflow graph:

**Fragile non-determinism in discovery.**  
`gh-bug-discover-and-read`, `gh-bug-triage`, `gh-issue-ingest`, `gh-issue-implement`,
and `gh-pr-fix-checks` each embed a `gh issue list` / `gh pr list` shell call inside an
AI session prompt.  AI misreads or misselects candidates.  There is no structured output
contract.  Discovery logic cannot be unit-tested in isolation.

**Fragile non-determinism in label mutation.**  
`gh-bug-post-repro`, `gh-bug-request-more-info`, `gh-bug-triage`, `gh-issue-ingest`,
`gh-issue-refine`, `gh-issue-implement`, and `gh-bug-fix-and-pr` all ask the AI to run
`gh issue edit` / `gh pr edit` for label changes.  The AI can silently skip or mis-order
label updates.  There is no deterministic post-condition, no retry contract, and no
auditable record separate from the session transcript.

## Scope

### In scope

**New extension operations (3):**

1. `github/find-pr`  
   Deterministic PR selection, parallel to `github/find-issue`.  
   Args: `{:labels [...] :input nil-or-hint :state "open"}`.  
   Output data: `{:pr-number N :pr-title T :pr-url U :pr-branch B :base-branch B :worktree-description slug}`.  
   Summary: markdown handoff.  
   Underlying shell: `gh pr list --state <state> --label ... --json number,title,url,state,labels,headRefName,baseRefName`.

2. `github/add-label`  
   Adds one or more labels to an issue or PR.  
   Args: `{:number N :labels [...] :target "issue"|"pr"}` — `:target` defaults to `"issue"`.  
   Output data: `{:number N :target "issue"|"pr" :added-labels [...]}`.  
   Underlying shell: `gh issue edit <N> --add-label <csv>` or `gh pr edit <N> --add-label <csv>`.

3. `github/remove-label`  
   Removes one or more labels from an issue or PR.  
   Args: `{:number N :labels [...] :target "issue"|"pr"}` — `:target` defaults to `"issue"`.  
   Output data: `{:number N :target "issue"|"pr" :removed-labels [...]}`.  
   Underlying shell: `gh issue edit <N> --remove-label <csv>` or `gh pr edit <N> --remove-label <csv>`.

**Workflow migrations (all in `.psi/workflows/`):**

| Workflow | Change |
|---|---|
| `gh-bug-discover-and-read` | Replace inline `gh issue list` AI step → `:invoke github/find-issue` step; AI session reads the issue only |
| `gh-bug-triage` | Add `:invoke github/find-issue` discover step; add unconditional `:invoke github/remove-label` (remove `triage`) step after AI classify step; conditional add (`waiting` vs `fix`) remains AI-driven — out of scope here |
| `gh-issue-ingest` | Add `:invoke github/find-issue` discover step; add `:invoke` label steps after AI triage step |
| `gh-issue-implement` | Replace inline `gh pr list` AI step → `:invoke github/find-pr`; replace inline label AI step → `:invoke` label steps |
| `gh-pr-fix-checks` | Replace inline `gh pr list` AI step → `:invoke github/find-pr` |
| `gh-bug-post-repro` | Add unconditional `:invoke github/remove-label` (remove `triage`) step after AI classify step; conditional add (`waiting` vs `fix`) remains AI-driven — out of scope here; strip unconditional label instructions from AI prompt |
| `gh-bug-request-more-info` | Add `:invoke` label steps after the AI post step; remove label instructions from AI prompt |
| `gh-issue-refine` | Add `:invoke` label steps after the AI publish step; remove label instructions from AI prompt |
| `gh-bug-fix-and-pr` | Add `:invoke` label step to remove `fix` label; remove label instruction from AI prompt |

### Out of scope

- GitHub comment posting (keep AI-driven; determinism not needed).
- Any extension operations beyond the three above.
- `github/find-issue` changes (already correct).
- PR check healing or worktree creation logic.
- `gh-bug-triage-modular` discovery migration — already uses `:invoke github/find-issue`
  for discovery; the monolithic `gh-bug-triage` is the migration target here.
  `gh-bug-triage-modular` is not deprecated by this task.
- Conditional label add (`waiting` vs `fix`) in `gh-bug-triage` and `gh-bug-post-repro`
  — the workflow IR has no conditional branching; the AI session remains responsible for
  the conditional label add until that capability exists.

## Architecture Alignment

The new operations follow the same pattern as `psi.github.find-issue`:

- Each operation is a separate namespace: `psi.github.label-ops`, `psi.github.find-pr`.
- `psi.github.extension/init` registers all operations via `:register-operation`.
- The shell function is injected via `ctx` key `:github-shell-fn` so tests use a nullable
  stub without touching the real `gh` binary.
- `psi.github.label-ops/add-label` and `psi.github.label-ops/remove-label` live in the
  same namespace (shared CSV formatting, shared shell dispatch).
- All operations return `{:status :ok/:error :data {...} :summary "..."}`.

### Data flow in migrated workflows

```
:invoke github/find-issue  →  issue-number in :data  →  AI read step (issue context)
:invoke github/find-pr     →  pr-number in :data     →  AI implement step
...AI classify/implement step (no label changes)...
:invoke github/remove-label  :number from upstream :data
:invoke github/add-label     :number from upstream :data
```

**Explicit :number wiring for label-ops steps (item H):**

`find-issue` outputs `:issue-number`; `find-pr` outputs `:pr-number`.  Label-ops steps
wire `:number` from the upstream discover step's `:data` output using the appropriate
path:

```edn
;; After a find-issue "discover" step:
:args {:number {:from {:step "discover" :output :data} :path [:issue-number]}
       :labels [...]
       :target "issue"}

;; After a find-pr "discover" step:
:args {:number {:from {:step "discover" :output :data} :path [:pr-number]}
       :labels [...]
       :target "pr"}
```

The discover step must expose `:data` in its `:outputs` map:

```edn
:outputs {:summary {:source :invoke/summary}
          :data    {:source :invoke/data}}
```

Workflows that already have a `find-issue` discover step with only `:summary` in
`:outputs` (`gh-issue-refine`, `gh-bug-fix-and-pr`) must add `:data {:source
:invoke/data}` to that step's `:outputs` before adding label-ops steps.

The AI step narrows its responsibility to content: reading, analysing, composing
replies, implementing changes.  Label mutation is a post-condition executed
deterministically after the AI step succeeds.

### `:number` wiring for gh-bug-post-repro and gh-bug-request-more-info (item K)

Neither `gh-bug-post-repro` nor `gh-bug-request-more-info` has a discover step.  The
issue number arrives through workflow input — the upstream repro/handoff text from
`gh-bug-reproduce`.  That handoff includes `issue_number:` as a machine-friendly bullet
under `## Handoff Data`.

**Decision (K):** Wire `:number` from `:workflow-input :path [:issue_number]` in both
workflows.  No new discover step is needed.

```edn
:args {:number {:from :workflow-input :path [:issue_number]}
       :labels ["triage"]
       :target "issue"}
```

This matches the structured handoff contract already emitted by `gh-bug-reproduce` and
`gh-bug-post-repro` (the `issue_number:` bullet is present in both workflows' handoff
output specs).

### Step name decisions for migrated discovery steps (items L and M)

**Decision (L) — `gh-issue-implement`:** Keep the existing step name `search` after
replacing the AI delegate with `:invoke github/find-pr`.  The downstream `prep`,
`design`, `implement`, `review`, and `push` steps all wire from
`{:step "search" :yield :text}`.  Renaming would require updating all five downstream
wiring references.  Keeping `search` means zero downstream changes.  The `:outputs`
block must add `:data {:source :invoke/data}` so label-ops steps can wire `:number`
from it.

Label-ops steps wire `:number` as:

```edn
:args {:number {:from {:step "search" :output :data} :path [:pr-number]}
       :labels [...]
       :target "pr"}
```

**Decision (M) — `gh-pr-fix-checks`:** Keep the existing step name `select` after
replacing the AI delegate with `:invoke github/find-pr`.  The downstream `heal-checks`
delegate step wires from `{:step "select" :yield :text}`.  Keeping `select` means zero
downstream changes.  No label-ops steps are added to `gh-pr-fix-checks` (PR discovery
only; no label mutation in this workflow).

### `gh-bug-fix-and-pr` discover `:input` (item N)

**Decision (N):** `:input nil` is intentional for `gh-bug-fix-and-pr`.  The `fix`
label already narrows the candidate set to a single issue in practice; no caller-supplied
narrowing hint is needed.  Design §D's `:input {:from :workflow-input :path [:input]}`
wiring applies only to `gh-issue-implement` and `gh-pr-fix-checks`, where multiple
candidates may exist.

### PR number source for gh-issue-refine label-ops (item J)

**Decision (J):** Use option (a) — update the `publish` delegate step prompt to
output `pr_number:` as a structured bullet under `## Handoff Data`, and add
`:outputs {:data {:source :delegate/handoff}}` to the publish step so downstream
label-ops steps can wire `:number` from it.

Rationale: the publish delegate already creates the PR and has the PR number
available at that point.  Requiring it to emit `pr_number:` in the structured
handoff costs nothing extra and avoids an additional `find-pr` round-trip.

Wiring for the add-label step that follows publish:

```edn
:args {:number {:from {:step "publish" :output :data} :path [:pr-number]}
       :labels ["waiting"]
       :target "pr"}
```

The publish step's `:outputs` must include:

```edn
:outputs {:summary {:source :delegate/summary}
          :data    {:source :delegate/handoff}}
```

And the publish prompt must include `pr_number:` under `## Handoff Data` (alongside
the existing `pr_url:` bullet).

### `github/find-pr` slug derivation

**Decision (B):** Extract `derive-slug` into `psi.github.slug` shared ns — eliminates
duplication, is a one-file change, and is the natural first step before `find-pr` is
added.  Do not inline a parallel copy.  Both `find-issue` and `find-pr` require
`psi.github.slug/derive-slug`.

Input to slug is `headRefName` (branch name).

### `github/find-pr` URL narrowing regex

**Decision (C):** `find-pr` uses `#"/pull/(\d+)"` for URL extraction — not
`#"/issues/(\d+)"`.  PR URLs contain `/pull/NNN`; issue URLs contain `/issues/NNN`.
The `find-pr` unit tests must include a narrow-by-url case using a `/pull/NNN` URL.

### `:input` wiring for `find-pr` in migrated workflows

**Decision (D):** In `gh-issue-implement` and `gh-pr-fix-checks`, the `:invoke
github/find-pr` step wires `:input` as `{:from :workflow-input :path [:input]}` —
matching the `find-issue` pattern used in `gh-bug-discover-and-read`.  This passes the
optional narrowing hint through from the workflow caller.

## Implementation Approach

1. **`psi.github.slug`** — extract `derive-slug` from `find-issue` into a shared helper
   ns so both `find-issue` and `find-pr` use it without duplication.

2. **`psi.github.find-pr`** — implement parallel to `find-issue`:
   - same narrowing logic (number, URL, title substring)
   - `worktree-description` derived from `headRefName` via `derive-slug`
   - handoff markdown includes `pr_branch` and `base_branch` lines

3. **`psi.github.label-ops`** — implement `add-label` and `remove-label`:
   - shared `label-csv` helper: `(str/join "," labels)`
   - dispatch on `:target` (`"issue"` → `gh issue edit`, `"pr"` → `gh pr edit`)
   - error returned as `{:status :error :reason :psi.github/shell-error :message ...}`

4. **`psi.github.extension/init`** — register all four operations.

5. **Tests** — add focused tests for each new operation handler using the
   `nil`-injectable `:github-shell-fn` pattern established in `find-issue-test`.
   Update `extension-test` to assert all four registrations.

6. **Workflow migrations** — update each listed `.psi/workflows/*.md` file:
   - Convert flat single-session discovery into a leading `:invoke` step.
   - Add trailing `:invoke` label steps that receive `number`/`pr-number` from the
     upstream `:data` output of the discover step.
   - Strip label-change instructions from AI prompt text.
   - Preserve AI prompt intent for non-label work (reading, analysing, posting, implementing).

## Acceptance Criteria

- `github/find-pr`, `github/add-label`, `github/remove-label` are registered by
  `psi.github.extension/init` and exercised by focused unit tests with a stub shell-fn.
- `extension-test` asserts four registrations (not one).
- All nine listed workflows no longer instruct the AI to perform label changes or
  shell-based issue/PR discovery; these are done via `:invoke` steps.
- `clj-kondo --lint` reports zero errors and zero warnings.
- All existing focused github extension tests pass.

## Alternatives Considered

**Single `github/set-labels` operation with add/remove lists.**  
Rejected: two orthogonal operations are simpler to reason about in workflow steps, easier
to test in isolation, and produce clearer audit trails in the event log.

**Leave PR discovery as AI-driven.**  
Rejected: PR discovery has the same fragility as issue discovery.  Adding `find-pr` now
completes the deterministic boundary consistently.

**Single `github/edit-labels` that takes `:add` and `:remove` in one shot.**  
Worth revisiting if compound label transitions become common, but the current workflow
usages always separate add from remove into sequential steps, so two operations is the
natural fit.
