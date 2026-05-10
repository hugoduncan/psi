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
