# Implementation notes

## 2026-05-10 — Task created

Motivation: The `gh-issue-refine` `discover` step is a full builder-delegate AI invocation for what is a deterministic shell-command + selection-rule operation. Replace with a `:tool` step that executes synchronously via a registered psi extension tool.

### Key decisions recorded at design time

**`:tool` step type vs `:script` step type**: chose `:tool` because it integrates with the existing capability catalog, permission model, and execution-adapter seam — rather than adding an ad-hoc subprocess step type that bypasses all of that.

**Output serialization format**: `:tool` steps serialize their result map into the canonical `## Handoff Data` Markdown bullet format so all downstream steps require zero changes.

**Shell seam**: `gh` CLI invocation is behind a `:github-shell-fn` ctx key (defaulting to `clojure.java.shell/sh`) so the extension is testable without spawning real `gh` processes.

**Error handling**: a tool step that throws `:psi.github/no-matching-issue` drives the workflow to a terminal error state (not a retry/loop). This matches the existing builder behavior ("stop and report nothing to process").

**Component placement**: new `components/github/` component. Does not live inside `components/workflow-runtime/` because it is domain-specific (GitHub) not workflow-generic.

### Open questions at design time

- Does the execution-adapter's `execute-tool-fn` receive the full `ctx` or a stripped capability map? Decide during Phase 2 based on what the tool fn needs (likely just `ctx` with `:github-shell-fn` threaded through).
- Is `clojure.data.json` the right JSON parser or should this use `cheshire` (check transitive deps in the github component)?
- Does the target-IR compiler need changes, or does the step-execution branch consuming `:tool` steps short-circuit before IR compilation? Clarify during Phase 2.

## 2026-05-10 — Design review pass 2

**G. `implementation.md` initial entry is stale.** The "Task created" section still describes `:tool` as the chosen step type and poses open questions about tool registration and `clojure.data.json`. These were superseded by pass 1 resolutions. The stale entry is misleading noise but is historical record — no edit needed; noted here for clarity.

**H. `:outputs` partial override drops compiler defaults.** The design's authored step specifies `{:outputs {:summary {:source :invoke/summary}}}`. `target-ir-compiler/compile-common-step-fields` applies `(or outputs (step-default-outputs type))` — full replacement, not merge. The compiled IR will have only `{:summary ...}`, losing `:data` and `:result`. Design must explicitly confirm no downstream step consumes `{:output :data}` or `{:output :result}` from the discover step, OR recommend omitting `:outputs` to keep all three defaults.

**I. `components/github/` placement contradicts extension pattern.** All domain-specific extensions live in `extensions/` and are listed in `.psi/extensions.edn`. The design says `components/github/` ("parallel to `components/workflow-runtime/`") but the extension registration path (`(:register-operation api)`) and `.psi/extensions.edn` entry are extension patterns, not component patterns. The placement must be resolved: `extensions/github/` (extension) or `components/github/` (core infrastructure with no `.psi/extensions.edn` entry).

**J. `deps.edn` wiring not specified.** The design mentions `.psi/extensions.edn` registration but omits the required root `deps.edn` changes: `psi/github {:local/root "..."}` under `:deps`, and source paths added to `:run`/`:psi` aliases. Without this the extension code is not on the classpath at runtime.

**K. `tests.edn` suite target depends on placement.** Plan says "Wire `components/github/` into Kaocha `tests.edn`". If placed in `extensions/`, the test paths belong in the `:extensions` suite; if in `components/`, the `:unit` suite. Must align with the placement decision (issue I).

## 2026-05-10 — Design review pass 1 follow-up (design-steps A–F resolved)

**A resolved**: `:invoke` + deterministic-operation-registry. `gh-find-issue` is workflow-internal, never AI-callable. Zero new step types. Phase 2 (`:tool` step type) removed from scope.

**B resolved**: No IR schema changes. `:invoke` step type handles this directly. Target-IR compiler already supports `:invoke`.

**C resolved**: Moot — not a tool. Extension uses `(:register-operation api)` with `{:id :handler :description?}` matching `operation-definition-schema`.

**D resolved**: `cheshire/cheshire "5.13.0"` in `components/github/deps.edn`. `clojure.data.json` removed from design.

**E resolved**: `{:outputs {:summary {:source :invoke/summary}} :yields {:type :text :text :summary}}`. Operation handler returns Markdown handoff as `:summary`. `step-yield-field-value :text` → `:summary` output → Markdown string. Downstream steps unchanged.

**F resolved**: `plan.md` written.

## 2026-05-10 — Design review pass 1

**A. `:tool` step type vs `:invoke` step type — architectural overlap unresolved.**
The codebase already has a `:invoke` step type that calls deterministic operations registered in `deterministic-operation-registry`. The proposed `:tool` step type is structurally identical in purpose (synchronous, deterministic, no session). The design does not justify why `gh-find-issue` should be a *tool* (in the tool catalog, used by AI agents) rather than a *deterministic operation* (in the operation registry, used by workflow steps). Using `:invoke` + a new `github/find-issue` operation would require zero new step types, zero IR schema changes, and zero new execution-adapter keys. This is the primary architectural ambiguity.

**B. `:tool` IR schema not specified.**
The design says "thread `:tool` through the IR" but the IR only supports `:invoke`, `:session`, `:delegate`. No `:tool` IR step schema is defined. If `:tool` is a distinct IR step type, its malli schema, source-ref traversal, and semantic validation rules must all be specified. If `:tool` compiles to `:invoke` in the IR (which would be clean), the design should say so explicitly.

**C. Tool registration contract mismatch.**
The design shows `{:name "gh-find-issue" :fn ... :schema ...}` in the extension manifest, but `normalize-tool-def` (tool-registry) uses `{:name :description :parameters :execute}`. The keys `:fn` and `:schema` do not exist in the tool-def contract. The design must either use the existing tool-def shape or explicitly extend it.

**D. `clojure.data.json` not present — use `cheshire`.**
The design states `clojure.data.json` is "already available" but the project uses `cheshire/cheshire` for JSON parsing throughout (tool-runtime, provider-auth, tool-registry, etc.). `clojure.data.json` appears in no `deps.edn`. The github component must declare `cheshire` as its JSON dependency.

**E. `:yield :text` compatibility for downstream steps not fully specified.**
The `worktree` step consumes `{:from {:step "discover" :yield :text}}`. For a `:delegate` step this resolves via `:delegated` yields. A `:tool` step needs its own `:yields` spec — the design proposes serializing the result to Markdown and storing it as `:yield :text`, but does not specify the IR `:yields` shape for `:tool` steps (e.g. `{:type :text :text :final-llm-reply}`) or where the Markdown string is stored in the result envelope.

**F. No `plan.md` — Munera protocol requires it before execution.**
The task has `design.md`, `steps.md`, and `implementation.md` but no `plan.md`. Per the Munera protocol, `plan.md` must exist and be written before execution begins.
