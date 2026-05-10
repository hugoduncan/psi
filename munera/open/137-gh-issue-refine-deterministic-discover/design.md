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
- The `:invoke` step in `gh-issue-refine.md` is exercised by a focused workflow-runtime integration test (tagged `^:integration`, located in `extensions/github/test`) that proves no session is spawned.

---

## Implementation approach

### 1. `psi/github` extension

**Location**: `extensions/github/` (new extension, parallel to `extensions/work-on/`).

**Namespace**: `psi.github.find-issue`

**Deterministic operation id**: `"github/find-issue"`

**Input args (passed via `:args` in the `:invoke` spec)**:
```clojure
{:labels [:vector :string]             ; required label filters (AND)
 :input  {:optional true} [:maybe :string]  ; optional narrowing hint: number, url, or text; nil accepted (absent workflow-input)
 :state  {:optional true} :string}     ; default "open"
```

**`:input` nil handling**: when `:input` is absent from `workflow-input`, `resolve-invoke-args` resolves `{:from :workflow-input :path [:input]}` to `nil`. The operation input schema uses `[:maybe :string]` (not `:string`) so `nil` is valid and treated as "no narrowing". The authored step keeps `:input {:from :workflow-input :path [:input]}` in `:args` — no conditional omission needed.

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
- Parses JSON output with `(cheshire.core/parse-string json)` — **string keys, no keywordize flag**. The `gh` CLI returns camelCase JSON fields (`"number"`, `"title"`, `"url"`, `"state"`) and labels as objects with a `"name"` subfield (`[{"name" "enhancement" ...}]`). All field access uses string keys: `(get issue "number")`, `(get issue "title")`, `(get issue "url")`, `(map #(get % "name") (get issue "labels"))`.
- Applies narrowing (see narrowing rules below).
- Selects the lowest `number` among candidates.
- Derives `worktree-description` as a kebab-slug from the title using hard truncation: lower-case the title, extract `[a-z0-9]+` words, join with `-`, hard-truncate the joined string at 40 chars, strip any trailing `-`. Result is `[a-z0-9-]`, ≤ 40 chars, never ends with `-`. Example: `"Add foo-bar baz"` → `"add-foo-bar-baz"` (15 chars, no truncation needed).
- Returns `{:status :ok :data {...} :summary "<markdown>"}` or `{:status :error :reason :psi.github/no-matching-issue :message "..."}`.
- On non-zero `gh` CLI exit: returns `{:status :error :reason :psi.github/shell-error :message (:err result)}` where `result` is the map returned by `clojure.java.shell/sh`.

**Narrowing rules** (applied when `input` is non-nil):

1. **Integer match**: `(re-matches #"^\d+$" input)` → parse with `(Long/parseLong input)` → filter by `(= (get issue "number") parsed-number)`. Matches `"0"`, `"007"` (parsed as 0 and 7 respectively), does NOT match `"-1"` or `"1.5"`. Leading zeros are accepted by `re-matches` and `Long/parseLong` treats them as decimal (not octal).
2. **URL match**: `(str/starts-with? input "https://")` → extract issue number with `(re-find #"/issues/(\d+)" input)` → parse the capture group with `Long/parseLong` → filter by number. If the URL does not match the pattern (no `/issues/NNN` segment), return `{:status :error :reason :psi.github/invalid-url-input :message "Cannot extract issue number from URL: <input>"}`.
3. **Text substring match** (fallback): case-insensitive — `(str/includes? (str/lower-case (get issue "title")) (str/lower-case input))`.

When `input` is `nil`: no narrowing applied; all label-filtered candidates are eligible.

**Shell error handling**: when `(:exit result)` is non-zero, return immediately:
```clojure
{:status :error
 :reason :psi.github/shell-error
 :message (:err result)}
```
Unit test: stub returning `{:exit 1 :out "" :err "gh: not authenticated"}` must produce this error result.

**Integration test "no session spawned" assertion**: run `workflow-execution/execute-run!` with a test ctx that has the `github/find-issue` operation registered but no `:workflow-execution-adapter` wired. Assert the run reaches `:completed` status. If the `:invoke` step were to spawn a session, `execution-adapter/adapter` would throw `"Workflow execution adapter is required"` — the test passing at `:completed` is the proof. Also assert the handler `calls*` atom has exactly one entry. Pattern: identical to `invoke-step-executes-through-deterministic-operation-registry-test` in `psi.agent-session.workflow-invoke-runtime-test`.

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

**Testing `psi.github.extension/init`**: use `create-extension-api` (from `psi.agent-session.extensions`) with a captured `register-deterministic-operation-fn` override — the pattern shown in `extensions_test.clj` (`extension-api-registration-test`). The nullable API (`create-nullable-extension-api`) does NOT expose `:register-operation` and cannot be used for `init` registration tests. For `psi.github.find-issue/invoke` unit tests, call the fn directly with a stub `ctx` carrying `:github-shell-fn` — no extension API needed.

**Extension manifest** (added to `.psi/extensions.edn`):
```edn
psi/github {}
```

**Root `deps.edn` wiring** (required for classpath):

Extensions are not listed under root `deps.edn` `:deps` — no existing extension has a `:local/root` entry there. Wiring is via `:extra-paths` in aliases only.

Under `:run`, `:psi`, `:tui-demo`, and `:test` aliases, add to `:extra-paths`:
```
"extensions/github/src"
```

Under `:test-paths` alias, add to `:extra-paths`:
```
"extensions/github/test"
```

Under `:test` alias, also add:
```
"extensions/github/test"
```

Note: `extensions/github/src` must NOT be added to `:test-paths` alias. The `:test-paths` alias contains only test paths (e.g. `extensions/work-on/test`), never extension `src` paths. Extension `src` paths appear in `:test` alias only.

Also add `"extensions/github/src"` to the `:unit` suite `:source-paths` in `tests.edn` — all other extension `src` paths are listed there for compilation of component tests that import extension code. Parity requires `extensions/github/src` to be included.

Also add `"extensions/github/test"` to the `:integration` suite `:test-paths` and `"extensions/github/src"` to the `:integration` suite `:source-paths` in `tests.edn`. The Phase 2 workflow-runtime integration test lives in `extensions/github/test` tagged `^:integration`; the `:integration` kaocha suite uses `:focus-meta [:integration]` so it will pick it up. The `:extensions` suite uses `:skip-meta [:integration]` so it will skip the integration test but still run the unit tests in `extensions/github/test`.

**`extensions/tests.edn`**: no change required. `extensions/tests.edn` is a standalone kaocha config for running tests within the `extensions/` directory using relative paths (`src`/`test`). It is not the authoritative suite config; root `tests.edn` owns all suite definitions. No existing extension is listed in `extensions/tests.edn` by path — it uses `src`/`test` relative to the working directory. `psi/github` tests are covered by root `tests.edn` `:extensions` suite.

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

**`:outputs` override behavior**: `target-ir-compiler/compile-common-step-fields` uses `(or outputs (step-default-outputs type))` — full replacement, not merge. Specifying `{:outputs {:summary {:source :invoke/summary}}}` drops the default `:data` and `:result` outputs. This is intentional: no downstream step in `gh-issue-refine.md` references `{:output :data}` or `{:output :result}` from the `discover` step. All downstream steps use `{:from {:step "discover" :yield :text}}` exclusively. Explicitly declaring only `:summary` is correct and avoids unnecessary output bindings.

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
