Goal: allow callers to specify the base branch for `work-on` from both the slash-command surface and the extension tool surface, while preserving current behavior when no base branch is specified.

Intent:
- make branch ancestry for `work-on` explicit when needed
- preserve one canonical operational path shared by command and tool
- keep the change small, extension-local, and behavior-preserving by default

Context:
- `extensions.work-on` already provides two entrypoints over one canonical operation path:
  - `/work-on`
  - tool `work-on`
- today `execute-work-on!` creates new worktrees through `git.worktree/add!` with `:base_ref nil`
- branch naming and worktree placement are already shared between command and tool surfaces
- reuse is already part of current `work-on` behavior:
  - if the target branch already exists, `work-on` can create a worktree for that branch
  - if the target worktree already exists and is a registered git worktree, `work-on` reuses it
  - if a session already exists for that reused worktree, `work-on` switches to it
- the extension already has default-branch discovery/caching used by `work-done!` and `work-rebase!`, but `work-on` itself does not currently let callers choose an explicit base branch

Problem:
- `work-on` currently chooses branch ancestry implicitly rather than from explicit caller intent
- implicit ancestry is acceptable for simple default-branch work, but becomes inadequate for workflows such as:
  - starting work from a release branch
  - creating follow-on work from a long-lived integration branch
  - making workflow-driven execution deterministic when a known base branch is required
- if only one surface gains explicit base-branch support, command/tool parity would drift
- if this task tries to redesign branch validation, command parsing, and reuse semantics all at once, it will become larger than needed

Scope:
- extend the canonical `work-on` operation to accept an optional base branch
- extend `/work-on` command parsing to collect that optional base branch
- extend the `work-on` tool schema and execution wrapper to accept the same concept
- thread the selected base branch into git worktree creation
- preserve shared command/tool operational semantics, side effects, and result shaping
- add focused tests for command parsing, tool schema/execution, and operational parity

Non-goals:
- changing branch-name slug generation
- changing worktree layout derivation
- changing `work-done!` or `work-rebase!`
- redesigning extension argument parsing generally
- broadening this into general revision syntax or multi-ref selection
- changing default behavior for callers that do not specify a base branch
- enforcing ancestry constraints on reused branches/worktrees in this slice
- adding separate pre-validation logic for base-branch existence before attempting git worktree creation

Minimum concepts:
- work description
- optional base branch
- canonical `work-on` request map or equivalent internal representation
- shared command/tool execution path
- explicit-vs-default base branch selection
- reuse vs new creation

Preferred surface shape:
- tool surface:
  - keep required `description` string
  - add optional `base_branch` string
- command surface:
  - support an explicit base-branch flag rather than ambiguous freeform parsing
  - supported forms in this slice are:
    - `/work-on <description>`
    - `/work-on --base <branch> <description>`
  - trailing or mixed `--base` placements are out of scope and need not be accepted
- internal shape:
  - canonical execution takes both `description` and optional base branch, either as separate args or a small request map
  - wrappers own parsing only; execution owns semantics
  - tool input key `base_branch` maps to internal key `:base-branch`

Reason for the preferred command shape:
- branch names and descriptions are both freeform strings and can contain overlapping tokens
- a flag-based command form keeps parsing deterministic and avoids heuristics
- it aligns naturally with the tool surface, where `base_branch` is already explicit structured input
- restricting support to a leading `--base` form keeps the parser small and predictable

Canonical behavior expectations:
- when no base branch is provided:
  - `work-on` preserves current behavior
  - preserving current behavior specifically means continuing to call `git.worktree/add!` with `:base_ref nil`
- when a base branch is provided and a new branch/worktree is created:
  - `git.worktree/add!` is called with `:base_ref` set to the requested branch
  - the canonical result records that the requested base branch was applied
  - in this task, “applied” means the requested base branch was used as the `:base_ref` input on a successful new-creation path
  - “applied” does not imply separate ancestry verification beyond successful git creation
- when a base branch is provided but the target is reused:
  - current reuse behavior is preserved
  - the requested base branch is not re-applied or reinterpreted
  - this task does not attempt to prove or enforce that the reused branch descends from the requested base branch
  - the canonical result records that a base branch was requested but was not applied because an existing target was reused
- if the target branch already exists and this invocation creates a new worktree for that existing branch:
  - that still counts as reuse for the purposes of base-branch reporting
  - the requested base branch is recorded but not considered applied
- existing session targeting, worktree-path updates, session create/switch behavior, and reuse semantics remain unchanged

Reuse semantics:
- reuse remains a valid and supported `work-on` outcome in this task
- explicit base-branch selection affects only new creation
- explicit base-branch selection does not constrain or invalidate reuse in this slice
- if stricter ancestry enforcement for reused targets is desired later, that should be handled by a separate task with explicit semantics

Canonical result shaping:
- continue to return one canonical success/error result contract for both command and tool presenters
- when a base branch is requested, the canonical result should include it under `:requested-base-branch`
- the canonical result should also record whether the requested base branch was actually applied under `:base-branch-applied?`, so callers can distinguish creation from reuse
- any effective/derived base-branch field beyond the explicit request is optional and should not require broad extra resolution work in this slice
- tool `:details` mirrors the canonical result
- command/tool presentation text may mention the base branch when it was explicitly requested and applied; if reuse prevented application, presentation may stay brief while the canonical result carries the detail

Error behavior:
- usage errors for the command should clearly describe the supported forms, including `--base`
- malformed `--base` usage should be handled by command parsing rather than by ad hoc deeper failures
- a missing value after `--base` should be treated as a specific command-parse error rather than silently falling through
- missing description should still produce clear usage guidance
- tool validation remains schema-driven plus runtime validation for blank strings
- blank `base_branch` on the tool surface is invalid and should not be silently treated as absent
- invalid or nonexistent base branch errors should surface from the canonical execution path, not from duplicated wrapper logic
- this slice does not add separate pre-validation of base-branch existence; git mutation remains the source of truth for creation failure

Architecture alignment:
- keep the solution inside `extensions.work-on`
- preserve the shared extension-local execution boundary established by task `039`
- prefer a small parsing helper for the command surface over broad command-framework changes
- do not introduce a second semantic implementation path
- if needed, a small request-map refactor is acceptable when it improves parity and local clarity without changing behavior

Acceptance:
- `/work-on` supports:
  - `/work-on <description>`
  - `/work-on --base <branch> <description>`
- tool `work-on` supports optional `base_branch`
- command and tool still share one canonical execution path
- explicit base branch is threaded to git worktree creation for new creation paths
- default behavior remains unchanged when no base branch is provided
- reuse behavior remains unchanged when the target already exists, including:
  - existing-branch paths where a new worktree is created for that branch
  - existing-worktree paths where the worktree is reused directly
  - existing-session paths where the session for a reused worktree is switched to
- canonical result records `:requested-base-branch` and `:base-branch-applied?` when a base branch is supplied
- focused tests prove:
  - tool registration/schema includes optional `base_branch`
  - command parsing for plain description and `--base <branch> <description>`
  - error behavior for missing `--base` values and missing descriptions
  - blank tool `base_branch` is invalid
  - happy-path explicit-base invocation on both command and tool surfaces
  - default-path behavior remains unchanged when base branch is omitted
  - reuse-path behavior remains unchanged and reports non-application of the requested base branch when appropriate

Notes:
- this task is a follow-on to `039-work-on-tool-surface` and preserves its core architectural decision: one canonical extension-local operational path with command/tool-specific presentation only
- the main design choice in this task is to add explicit base-branch selection without broadening into ancestry enforcement or generalized revision parsing
