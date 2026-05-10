# 137 — gh-issue-refine: deterministic discover step

## Intent

Replace the non-deterministic AI `discover` step in `gh-issue-refine` with a **deterministic workflow step** that invokes a GitHub deterministic operation to find the target issue. No AI sampling should occur during issue selection — the output is fully determined by the `gh` CLI and the selection rules.

## Problem

The current `discover` step delegates to the `builder` workflow, which spawns an AI agent to run `gh issue list` and format a handoff. This is:

- **Non-deterministic** — AI sampling can vary the result, misapply selection rules, or format handoff data inconsistently.
- **Slow** — spawning a full agent session for a simple shell command adds latency and cost.
- **Fragile** — prompt drift or model changes can break the downstream handoff data format.

## Scope

Two coordinated changes:

1. **`psi/github` extension** — a new psi extension that registers a `github/find-issue` deterministic operation providing deterministic issue lookup.
2. **`gh-issue-refine.md` update** — replace the `discover` `:delegate` step with an `:invoke` step referencing `github/find-issue`.

### Out of scope

- A new `:tool` workflow step type — not needed; the existing `:invoke` step type is the correct fit (see Architecture Decision below).
- Changes to other `gh-*` workflows (they may benefit later from the same extension; separate task).
- Changes to the `worktree`, `refine-design`, `design-status`, or `publish` steps.
- Authentication or OAuth for the `gh` CLI — assumes `gh` is already authenticated in the shell environment.

## Architecture Decision: `:invoke` + deterministic operation, not `:tool`

The codebase already has a `:invoke` step type that calls deterministic operations registered in `deterministic-operation-registry`. `gh-find-issue` is a synchronous, deterministic, no-session operation — exactly what the operation registry exists for.

Using `:invoke` + a new `github/find-issue` operation:
- requires **zero new step types** in the IR or target-IR compiler
- requires **zero new execution-adapter keys**
- requires **zero IR schema changes**
- integrates with the existing extension registration path (`(:register-operation api)`)

A `:tool` step type would be appropriate only if the operation needed to appear in the AI agent tool catalog (for AI-driven use). `gh-find-issue` is workflow-internal — it is never offered to an AI agent as a callable tool. Therefore `:invoke` is the correct choice.

## Acceptance criteria

- Running `gh-issue-refine` with no input selects the lowest-numbered open issue labeled `enhancement` + `refine` deterministically, without an AI agent in the discover step.
- Running `gh-issue-refine` with an issue-number narrowing hint selects exactly that issue.
- If no matching issue exists, the workflow stops with a clear error before the worktree step.
- The handoff data format emitted by the deterministic step is identical in structure to the current builder-produced handoff (`issue_number`, `issue_title`, `issue_url`, `worktree_description`).
- The `github/find-issue` operation is exercised by a focused unit test using a nullable shell adapter.
- The `:invoke` step in `gh-issue-refine.md` is exercised by a focused workflow-runtime integration test that proves no session is spawned.

---

## Implementation approach

### 1. `psi/github` extension

**Location**: `components/github/` (new component, parallel to `components/workflow-runtime/`).

**Namespace**: `psi.github.find-issue`

**Deterministic operation id**: `"github/find-issue"`

**Input args (passed via `:args` in the `:invoke` spec)**:
```clojure
{:labels [:vector :string]           ; required label filters (AND)
 :input  {:optional true} :string    ; optional narrowing hint: number, url, or text
 :state  {:optional true} :string}   ; default "open"
```

**Operation result**:
```clojure
;; Success
{:status :ok
 :data {:issue-number :int
        :issue-title  :string
        :issue-url    :string
        :worktree-description :string}   ; kebab-slug derived from title
 :summary "<Markdown handoff string>"}   ; canonical ## Handoff Data block

;; Failure
{:status :error
 :reason :psi.github/no-matching-issue
 :message "No open issue matching labels [...] and input [...]"}
```

The `:summary` field carries the serialized Markdown handoff block. This is the string exposed as `:yield :text` by the `:invoke` step (see below).

**Implementation**:
- Invokes `gh issue list --state <state> --label <l1> --label <l2> ... --json number,title,labels,state,url` via `clojure.java.shell/sh` (or a configurable `:github-shell-fn` ctx key for testing).
- Parses JSON output with `cheshire.core/parse-string` (project standard; declare `cheshire/cheshire "5.13.0"` in the github component `deps.edn`).
- Applies narrowing: if `input` parses as an integer → filter by issue number; if it looks like a URL → extract number from URL; otherwise → text substring match on title.
- Selects the lowest `number` among candidates.
- Derives `worktree-description` as a kebab-slug from the title (≤ 40 chars, `[a-z0-9-]`).
- Returns `{:status :ok :data {...} :summary "<markdown>"}` or `{:status :error :reason :psi.github/no-matching-issue :message "..."}`.

**Shell seam** (for testability):
```clojure
(defn invoke
  [{:keys [ctx args]}]
  (let [shell-fn (or (:github-shell-fn ctx) clojure.java.shell/sh)
        ...]
    ...))
```

The operation handler receives the standard invocation map `{:ctx ctx :args args ...}`. The ctx carries `:github-shell-fn` so tests inject a nullable stub.

**Output serialization** (`:summary` field):
```markdown
## Issue Selection

Selected issue #42: Add foo bar

## Handoff Data
- issue_number: 42
- issue_title: Add foo bar
- issue_url: https://github.com/.../issues/42
- worktree_description: add-foo-bar
```

A private `result->handoff-md` fn in `psi.github.find-issue` converts the structured result map to the canonical Markdown handoff format. This keeps downstream steps identical — they still parse `## Handoff Data` bullet lines.

**Extension registration** in `psi.github.extension`:
```clojure
(defn init [api]
  ((:register-operation api)
   {:id          "github/find-issue"
    :description "Find a GitHub issue matching labels and optional narrowing input"
    :handler     psi.github.find-issue/invoke}))
```

**Extension manifest** (added to `.psi/extensions.edn`):
```edn
psi/github {}
```

### 2. `gh-issue-refine.md` update

Replace the current `discover` step:
```clojure
;; Before (delegate to builder)
{:name "discover"
 :type :delegate
 :target "builder"
 :prompt-string {...}
 :context [...]}

;; After (deterministic invoke step)
{:name    "discover"
 :type    :invoke
 :operation "github/find-issue"
 :args    {:labels ["enhancement" "refine"]
           :input  {:from :workflow-input :path [:input]}}
 :outputs {:summary {:source :invoke/summary}}
 :yields  {:type :text :text :summary}}
```

The `:yields {:type :text :text :summary}` declaration means `step-yield-field-value` for `:text` returns the `:summary` output value — the Markdown handoff string. Downstream steps consuming `{:from {:step "discover" :yield :text}}` require no change.

No changes to downstream steps — they consume the same Markdown handoff format.

## Key invariants

- The `github/find-issue` operation never spawns an agent session or allocates a turn.
- Operation args are fully resolved before the handler is called — no partial application.
- If the operation returns `{:status :error :reason :psi.github/no-matching-issue ...}`, the workflow enters a terminal error state via the existing `:invoke` error path in `step_execution.clj`; it does not fall through to the next step.
- The handoff Markdown produced by the operation `:summary` is structurally identical to the handoff the builder agent previously produced — downstream steps require no change.

## Alternatives considered

**A. `:tool` step type** — appropriate for AI-callable tools in the tool catalog. `gh-find-issue` is workflow-internal and never exposed to AI agents. Rejected in favor of `:invoke` + deterministic operation registry, which requires zero new step types and zero IR changes.

**B. `:session` step with tightly constrained tools and scripted prompt** — still AI-driven, still non-deterministic by definition. Rejected.

**C. bb task invoked via a `:script` step type** — simpler in some ways, but does not leverage the existing psi operation/extension infrastructure and adds a new step type. Rejected.

**D. Inline shell exec in a new `:sh` step type** — avoids the extension layer but bypasses the capability catalog, permissions, and testability seams. Rejected.
