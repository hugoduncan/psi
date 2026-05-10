# 137 — gh-issue-refine: deterministic discover step

## Intent

Replace the non-deterministic AI `discover` step in `gh-issue-refine` with a **deterministic workflow step** that invokes a GitHub extension tool to find the target issue. No AI sampling should occur during issue selection — the output is fully determined by the `gh` CLI and the selection rules.

## Problem

The current `discover` step delegates to the `builder` workflow, which spawns an AI agent to run `gh issue list` and format a handoff. This is:

- **Non-deterministic** — AI sampling can vary the result, misapply selection rules, or format handoff data inconsistently.
- **Slow** — spawning a full agent session for a simple shell command adds latency and cost.
- **Fragile** — prompt drift or model changes can break the downstream handoff data format.

## Scope

Three coordinated changes:

1. **`psi/github` extension** — a new psi extension that registers a `gh-find-issue` tool providing deterministic issue lookup.
2. **`:tool` workflow step type** — a new deterministic step type in the workflow runtime that invokes a named psi tool directly (no AI session).
3. **`gh-issue-refine.md` update** — replace the `discover` `:delegate` step with a `:tool` step referencing `gh-find-issue`.

### Out of scope

- Changes to other `gh-*` workflows (they may benefit later from the same extension; separate task).
- Changes to the `worktree`, `refine-design`, `design-status`, or `publish` steps.
- Authentication or OAuth for the `gh` CLI — assumes `gh` is already authenticated in the shell environment.

## Acceptance criteria

- Running `gh-issue-refine` with no input selects the lowest-numbered open issue labeled `enhancement` + `refine` deterministically, without an AI agent in the discover step.
- Running `gh-issue-refine` with an issue-number narrowing hint selects exactly that issue.
- If no matching issue exists, the workflow stops with a clear error before the worktree step.
- The handoff data format emitted by the deterministic step is identical in structure to the current builder-produced handoff (`issue_number`, `issue_title`, `issue_url`, `worktree_description`).
- The `:tool` step type is exercised by a focused workflow-runtime test that proves deterministic execution (no session spawn).
- The `gh-find-issue` tool is exercised by a focused unit test using a nullable shell adapter.

---

## Implementation approach

### 1. `psi/github` extension

**Location**: `components/github/` (new component, parallel to `components/workflow-runtime/`).

**Namespace**: `psi.github.find-issue`

**Registered tool name**: `gh-find-issue`

**Tool schema (malli)**:
```clojure
;; Input
[:map
 [:labels [:vector :string]]           ; required label filters (AND)
 [:input  {:optional true} :string]    ; optional narrowing hint: number, url, or text
 [:state  {:optional true} :string]]   ; default "open"

;; Output
[:map
 [:issue-number :int]
 [:issue-title  :string]
 [:issue-url    :string]
 [:worktree-description :string]]      ; kebab-slug derived from title
```

**Implementation**:
- Invokes `gh issue list --state <state> --label <l1> --label <l2> ... --json number,title,labels,state,url` via `clojure.java.shell/sh` (or a configurable `:shell-fn` seam for testing).
- Parses JSON output with `clojure.data.json` (already available).
- Applies narrowing: if `input` parses as an integer → filter by issue number; if it looks like a URL → extract number from URL; otherwise → text substring match on title.
- Selects the lowest `number` among candidates.
- Derives `worktree-description` as a kebab-slug from the title (≤ 40 chars, `[a-z0-9-]`).
- Returns the structured map or throws `ex-info` with `:psi.github/no-matching-issue` if no candidates.

**Extension manifest** (added to `.psi/extensions.edn`):
```edn
psi/github {}
```

**Extension registration** in `psi.github.extension/manifest`:
```clojure
{:tools [{:name    "gh-find-issue"
          :fn      psi.github.find-issue/invoke
          :schema  psi.github.find-issue/schema}]}
```

**Shell seam** (for testability):
```clojure
;; Default
(def default-shell-fn clojure.java.shell/sh)

;; Nullable seam — tests inject this
(defn invoke [{:keys [shell-fn] :or {shell-fn default-shell-fn}} params]
  ...)
```

The ctx carries `:github-shell-fn` so the extension assembly can inject a nullable stub in tests.

### 2. `:tool` workflow step type

**Affected namespaces**:
- `psi.workflow-runtime.model` — add `:tool` to the step-type enum; add `:tool-name` and `:tool-params` to the step spec.
- `psi.workflow-runtime.statechart-runtime.step-execution` — add a `:tool` branch that calls the tool via the execution adapter instead of spawning a session.
- `psi.workflow-runtime.ir` (and target-IR compiler if applicable) — thread `:tool` step through the IR.
- `psi.workflow-runtime.execution-adapter` — add `execute-tool` to the adapter contract (alongside the existing session-execution seam).

**Step shape in workflow EDN**:
```clojure
{:name       "discover"
 :type       :tool
 :tool-name  "gh-find-issue"
 :tool-params {:labels    ["enhancement" "refine"]
               :input     {:from :workflow-input :path [:input]}}}
```

`:tool-params` values support the same template-var resolution that `:prompt-string :vars` uses (`:from :workflow-input`, `:from {:step "..." :yield :text}`, literals).

**Step execution contract**:
1. Resolve `:tool-params` template vars against current workflow state.
2. Look up the tool in the capability catalog by `:tool-name`.
3. Call the tool's `:fn` with `(ctx, resolved-params)`.
4. Serialize the return value to a Markdown handoff block (see below) and store as the step's `:yield :text`.
5. If the tool throws with `:psi.github/no-matching-issue`, transition the workflow to a terminal error state instead of propagating into the next step.

**Output serialization** (`:tool` steps → Markdown handoff):
```markdown
## Issue Selection
...auto-generated from tool return value...

## Handoff Data
- issue_number: 42
- issue_title: Add foo bar
- issue_url: https://github.com/.../issues/42
- worktree_description: add-foo-bar
```

A shared `psi.workflow-runtime.step-execution/tool-result->handoff-md` fn converts the tool output map to the canonical Markdown handoff format. This keeps downstream steps identical — they still parse `## Handoff Data` bullet lines.

**Execution adapter extension**:
```clojure
;; New key in the adapter map
:execute-tool-fn  (fn [ctx tool-name resolved-params] ...)
```

The higher `psi.agent-session.context/workflow-execution-adapter` assembly wires the real tool dispatch here. Tests inject a stub.

### 3. `gh-issue-refine.md` update

Replace the current `discover` step:
```clojure
;; Before (delegate to builder)
{:name "discover"
 :type :delegate
 :target "builder"
 :prompt-string {...}
 :context [...]}

;; After (deterministic tool step)
{:name "discover"
 :type :tool
 :tool-name "gh-find-issue"
 :tool-params {:labels ["enhancement" "refine"]
               :input  {:from :workflow-input :path [:input]}}}
```

No changes to downstream steps — they consume the same Markdown handoff format.

## Key invariants

- The `:tool` step type never spawns an agent session or allocates a turn.
- Tool params are fully resolved before the tool fn is called — no partial application.
- If a tool step fails, the workflow enters a terminal error state; it does not fall through to the next step.
- The handoff Markdown produced by a `:tool` step is structurally identical to the handoff the builder agent previously produced — downstream steps require no change.

## Alternatives considered

**A. `:session` step with tightly constrained tools and scripted prompt** — still AI-driven, still non-deterministic by definition. Rejected.

**B. bb task invoked via a `:script` step type** — simpler in some ways, but does not leverage the existing psi tool/extension infrastructure and adds a new step type with different wiring than the tool catalog. Rejected in favor of `:tool` which integrates more cleanly.

**C. Inline shell exec in a new `:sh` step type** — avoids the extension layer but bypasses the capability catalog, permissions, and testability seams. Rejected.
