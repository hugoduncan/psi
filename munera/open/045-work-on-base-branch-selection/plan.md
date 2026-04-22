Approach:
- keep the change small and extension-local within `extensions.work-on`
- preserve the existing shared command/tool execution boundary
- add explicit base-branch input at the wrappers, then thread it into the canonical operation path
- use deterministic command parsing with a leading `--base` flag rather than heuristic freeform parsing
- preserve current reuse behavior and treat explicit base-branch selection as applying only to new creation paths

Implementation shape:
1. Inspect current `work-on` execution and wrapper boundaries
- confirm the smallest change to `execute-work-on!` that can accept optional base-branch input
- prefer a small canonical request shape such as `{:description ... :base-branch ...}` if it makes parity and result shaping clearer
- keep one canonical operational path for both command and tool

2. Add command parsing for explicit base-branch selection
- introduce a small private parser for `/work-on` args
- support only:
  - `/work-on <description>`
  - `/work-on --base <branch> <description>`
- do not accept trailing or mixed `--base` placements in this slice
- return clear parse/usage errors for malformed flag usage, including:
  - missing branch after `--base`
  - missing description
- keep parsing logic local to `extensions.work-on`

3. Extend the canonical execution path
- thread optional base-branch input into the `work-on` operation
- update worktree creation so `git.worktree/add!` receives `:base_ref` when a base branch is specified
- preserve current behavior when it is absent by continuing to call `git.worktree/add!` with `:base_ref nil`
- keep success/error result shaping canonical and shared
- add stable canonical result fields when a base branch is supplied:
  - `:requested-base-branch`
  - `:base-branch-applied?`
- define `:base-branch-applied?` narrowly:
  - true when the requested base branch was used as `:base_ref` on a successful new-creation path
  - false for reuse paths, including when a branch already existed and only a new worktree was attached to it

4. Preserve reuse semantics
- keep current reuse behavior unchanged for:
  - existing-branch paths where a new worktree is created for an existing branch
  - existing-worktree paths where a registered worktree is reused
  - existing-session paths where the session for a reused worktree is switched to
- when a base branch was requested during a reuse path, record it in `:requested-base-branch` but leave `:base-branch-applied?` false
- do not add ancestry verification or reuse constraints in this slice

5. Extend the tool surface
- update the registered tool schema to include optional `base_branch`
- keep `description` required
- map tool input key `base_branch` to internal `:base-branch`
- make blank `base_branch` invalid rather than silently treating it as absent
- make the tool wrapper pass both fields into the shared execution path
- preserve the existing tool return contract `{:content ... :is-error ... :details ...}`

6. Preserve command presentation behavior
- keep `/work-on` as a presenter over the canonical result
- use parse errors for malformed `--base` syntax and canonical execution errors for git/runtime failures
- only adjust success wording if necessary to mention an explicitly applied base branch; keep wording otherwise stable
- do not introduce separate command-side operational semantics

7. Add focused tests
- extend init registration coverage for the new optional tool parameter
- add parser-focused command tests for:
  - plain description
  - `--base <branch> <description>`
  - missing branch after `--base`
  - missing description
  - unsupported trailing/mixed `--base` forms if parser behavior needs proof
- add focused tool validation tests for blank `base_branch`
- add happy-path command/tool tests proving explicit base branch is threaded to `git.worktree/add!`
- add or extend tests proving omitted base branch preserves current `:base_ref nil` behavior
- add reuse-path tests proving explicit requested base branch is recorded but not considered applied when the target already exists
- keep assertions focused on shared operational behavior and canonical payloads, not incidental implementation details

8. Verify and record findings
- run focused `extensions.work-on` tests
- fix any regressions
- record syntax decisions, result-field decisions, and reuse semantics in `implementation.md`

Key decisions to preserve:
- one canonical operational path for command and tool
- deterministic command syntax via leading `--base`
- no change to behavior when base branch is omitted
- explicit base branch affects only new creation, not reuse semantics
- no pre-validation of base-branch existence before git worktree creation
- no broader command-framework or extension-architecture redesign in this slice

Risks / watchpoints:
- avoid ambiguous command parsing that could confuse branch names with descriptions
- avoid changing existing `/work-on <description>` behavior
- avoid treating existing-branch attach as an applied base-branch path
- avoid silently collapsing blank tool `base_branch` to nil
- keep the result contract stable and explicit for tests and future tool callers
