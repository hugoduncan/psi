# Steps — Session profiles for workflow step configuration

## Slice 1 — Profile domain and config resolution

- [x] Add a profile domain namespace/helper that defines the supported field set, reserved profile names, canonical thinking/speed/effort values, and normalized resolved-profile data shape.
- [x] Implement effective `:agent-session :session-profiles` loading from user config, project shared config, and project local config without changing unrelated `:agent-session` config resolution.
- [x] Implement profile-specific recursive merge with precedence `user < project-shared < project-local`, preserving partial profile override behavior.
- [x] Filter profile resolution to exactly `:model-provider`, `:model-id`, `:thinking-level`, `:speed-mode`, and `:effort-override`, ignoring unknown keys in resolved/applied/snapshotted profile data.
- [x] Validate model identity as an all-or-nothing `:model-provider` plus `:model-id` pair through the existing model registry/model-selection path.
- [x] Validate `:thinking-level`, `:speed-mode`, and `:effort-override` values against the design's canonical sets, including `nil` effort where representable.
- [x] Mark empty/no-concrete-setting profiles and `:clear` profiles invalid with actionable diagnostic data.
- [x] Add tests for profile deep merge, precedence, unknown-key ignoring, partial valid profiles, invalid model pairs, invalid enum values, empty profiles, and reserved `:clear`.

## Slice 2 — Live session command surface and observability

- [x] Add session selected-profile metadata storage on canonical session state with fields for profile name and concrete resolved settings applied.
- [x] Add a session-owned mutation/dispatch path that atomically applies an already-validated profile's concrete model/thinking/speed/effort values plus selected-profile metadata.
- [x] Ensure model application reuses existing session-model mutation behavior, including thinking-level clamp and model journal entry semantics.
- [x] Ensure thinking-level application reuses existing thinking-level mutation behavior and journal entry semantics.
- [x] Ensure speed-mode and effort-override application stays session-scoped/transient like `/speed ... session` and `/effort ... session`, with no config write or journal entry.
- [x] Add a session-owned mutation/dispatch path for `/session-profile clear` that clears only selected-profile metadata and does not change concrete settings.
- [x] Expose effective session profiles, readable resolved settings/invalid reasons, and current selected-profile metadata through EQL resolvers or equivalent session-owned read helpers.
- [x] Add `/session-profiles` and `/session-profile` entries to the backend single-source built-in command spec table with appropriate exact/prefixed routing metadata.
- [x] Wire slash command dispatch for `/session-profiles`, `/session-profile`, `/session-profile <name>`, and `/session-profile clear` through backend command handlers.
- [x] Format `/session-profiles` output to list valid profiles with readable settings and invalid profiles with terse reasons.
- [x] Format `/session-profile` output to show selected-profile metadata when present and current concrete model/thinking/speed/effort settings.
- [x] Format unknown/invalid profile command failures with the requested profile name, invalid reasons where applicable, and available effective profile names.
- [x] Add tests proving invalid/unknown live profile selection leaves model/thinking/speed/effort and selected-profile metadata unchanged.
- [x] Add tests proving `/session-profile clear` clears metadata only and does not revert concrete settings.
- [x] Add tests proving built-in command resolver/help/autocomplete surfaces include the new backend-defined commands without adapter-local lists.

## Slice 3 — Workflow authoring grammar and canonical IR

- [x] Extend workflow authoring validation to accept compact top-level `:session-profile` on supported `:session` steps and compile it into the canonical step `:session` config.
- [x] Extend workflow authoring validation to accept compact top-level `:session-profile` on supported `:delegate` steps and compile it into the canonical delegate inherited-default-shaping session-config surface.
- [x] Extend single-step markdown frontmatter parsing/compiler support for `:session-profile`, compiling to the same canonical `:session` config as an EDN `:session` step.
- [x] Keep direct authored workflow overrides limited to currently supported `:model` and `:thinking-level`; do not add direct `:speed-mode` or `:effort-override` authored keys.
- [x] Add loader/compiler rejection tests for unsupported nested `{:session {:session-profile ...}}` spelling if that path is currently distinguishable.
- [x] Add loader/compiler rejection tests for `:session-profile` on `:invoke` steps and LLM judge specs if those validation paths are present.
- [x] Add loader/compiler tests proving existing workflows without `:session-profile` compile unchanged.
- [x] Add loader/compiler tests proving top-level EDN and markdown frontmatter `:session-profile` land in canonical IR at the expected location.

## Slice 4 — Workflow snapshot and step resolution semantics

- [x] Add a dedicated canonical workflow-run field for the session-profile snapshot, storing valid resolved settings and invalid-profile diagnostics without ignored unknown keys.
- [x] Capture the effective session-profile snapshot once at top-level workflow invocation before any step executes.
- [x] Ensure blocked/resumed workflow runs reuse the persisted session-profile snapshot rather than re-reading config.
- [x] Ensure delegated workflow runs copy or derive their profile snapshot from the delegating run's immutable snapshot rather than reading config.
- [x] Extend workflow step session-config resolution to resolve `:session-profile` against the workflow-run profile snapshot.
- [x] Merge profile-derived settings into effective step config with precedence `explicit step setting > resolved profile setting > inherited workflow-run default > existing fallback`.
- [x] Preserve explicit `:model` and `:thinking-level` step override precedence over profile-derived model/thinking values.
- [x] Include profile-derived `:speed-mode` and `:effort-override` in effective step config when present, despite the absence of direct authored speed/effort keys.
- [x] Fail/block unknown or invalid workflow profiles before creating the child session or attempt that would consume it.
- [x] Add workflow runtime tests proving mid-run config edits do not affect later steps in the same run.
- [x] Add workflow runtime tests proving resumed runs and delegated runs resolve profiles from snapshots rather than mutable config.
- [x] Add workflow runtime tests proving invalid workflow profiles fail atomically before child-session creation.
- [x] Add workflow runtime tests proving workflows without `:session-profile` preserve existing task-207 inheritance behavior.

## Slice 5 — Delegate inherited-defaults projection

- [x] Update delegated inherited-default snapshot projection so effective config fields `:model`, `:thinking-level`, `:speed-mode`, and `:effort-override` can flow as concrete defaults to the child run when profile resolution supplies them.
- [x] Preserve task-207 fallback for delegated `:speed-mode` and `:effort-override` when the delegating effective config does not contain those fields.
- [x] Distinguish field presence from truthiness when projecting effort so an explicit resolved concrete nil/clear cannot be replaced accidentally by the parent snapshot if the implementation supports nil as a concrete setting.
- [x] Ensure `:inherited-defaults` remains narrow and contains no profile names, raw profile maps, unknown profile keys, or profile diagnostics.
- [x] Add delegate tests proving profile-derived speed/effort outrank parent snapshot values when present.
- [x] Add delegate tests proving absent profile speed/effort falls back to parent snapshot values.
- [x] Add delegate tests proving callee workflow explicit overrides still outrank inherited profile-derived defaults.

## Slice 6 — Docs and changelog

- [x] Update `doc/configuration.md` with `:agent-session :session-profiles` shape, supported fields, merge precedence, partial override examples, invalid profile behavior, and existing config file locations.
- [x] Update workflow authoring docs with `:session-profile` examples for session/delegate steps and markdown frontmatter, plus explicit override precedence.
- [x] Document workflow snapshot semantics: profile definitions are captured at run start; mid-run config edits do not affect later steps, delegated runs, or resumed runs.
- [x] Document `/session-profiles`, `/session-profile`, `/session-profile <name>`, and `/session-profile clear` command behavior.
- [x] Add a `CHANGELOG.md` `[Unreleased]` entry for the new session profile config, slash commands, workflow key, and deterministic snapshot behavior.


## Plan/steps ambiguity review follow-ups

- [x] PA1: Pin the exact canonical IR path for delegate-step session-profile config (for example `[:delegate :session]` or another named path), and name the loader/compiler/runtime consumers that must read that path instead of the current session-step-only `:session` lookup.
  - Done: plan now pins delegate profile config at `[:delegate :session :session-profile]`, direct delegate model/thinking at `[:delegate :session]`, and names target IR compiler, normalized run storage, `resolve-step-session-config`, and the delegate inherited-defaults resolver as consumers.
- [x] PA2: Decide whether a profile containing `:effort-override nil` is a valid concrete effort-clear setting or is treated as no concrete setting; align validation, snapshots, live application, delegate projection, and tests with that presence-vs-value decision.
  - Done: plan chooses explicit `:effort-override nil` as a valid concrete effort-clear; absence alone means no effort setting; validation/snapshots/live application/delegate projection/tests must preserve key presence.
- [x] PA3: Specify `/session-profile <profile-name>` token normalization: whether users type `planning`, `:planning`, or both; how the token becomes the keyword profile name; and how this interacts with the reserved `clear` action.
  - Done: plan accepts bare or EDN-style unqualified keyword tokens, normalizes them to keywords, treats raw bare `clear` as the clear action before normalization, and treats `:clear` as reserved/unavailable.
- [x] PA4: Specify live profile application's final thinking-level semantics when a profile supplies both model and thinking-level, including ordering/clamping for non-reasoning models, so the atomic mutation and journal entries cannot diverge.
  - Done: plan specifies validate-first atomic application with model-before-thinking ordering; model mutation clamps current thinking first, explicit profile thinking then clamps against the resulting model and journals the clamped final value.
- [x] PA5: Pin the workflow-run snapshot capture boundary: compute the session-profile snapshot at the impure top-level invocation boundary and pass it into pure workflow-run creation, while delegated runs copy/derive from the parent run snapshot without config reads.
  - Done: plan pins snapshot computation to impure top-level invocation boundaries (`psi.workflow/create-run`, psi-tool `workflow create-run`) and stores supplied `:session-profile-snapshot` in pure `workflow-runtime.core/create-run`; delegated runs copy/derive from parent snapshots without config reads.

## Plan/steps inconsistency review follow-ups

- [x] PI1: Extend the canonical workflow IR/runtime model schemas and validation tests to allow the PA1 delegate session-config path `[:delegate :session]` with `:session-profile`, `:model`, and `:thinking-level`; prove target compilation, normalization, and `validate-workflow-ir` preserve that path instead of rejecting or dropping it.
  - Done: plan now pins the canonical delegate `:session` map/schema surface and the exact preserving consumers/tests required.
- [x] PI2: Carry PA2's explicit `:effort-override nil` concrete-clear semantics through all workflow boundaries, not only delegate projection: profile snapshots, `resolve-step-session-config`, nested `effective-config->snapshot`, child-session creation, and tests must distinguish key presence from nil value so a profile-derived nil can clear an inherited parent effort for session and delegated children.
  - Done: plan now requires presence-aware handling across resolved settings, snapshots, resolver output, nested projection, delegate inherited-defaults, and child-session creation.
- [x] PI3: Add command work/tests for PA3 token normalization: bare `planning` and EDN-style `:planning` select the same profile, raw bare `clear` performs the clear action, `:clear` fails as reserved/unavailable, and multi-token or EDN map/vector tokens are rejected without state changes.
  - Done: plan now adds a command-parser/normalizer seam and concrete command-token test matrix.
- [x] PI4: Make the PA5 snapshot-capture boundary explicit in implementation steps/tests: both Pathom `psi.workflow/create-run` and psi-tool workflow create-run compute/pass `:session-profile-snapshot`, pure `workflow-runtime.core/create-run` only stores the supplied snapshot and never reads config, and delegate runtime passes a copied/derived child snapshot alongside narrow `:inherited-defaults`.
  - Done: plan now names Pathom, psi-tool, pure create-run, and delegate runtime wiring/test obligations.

## Slice 7 — Verification and coherence

- [x] Run focused tests for profile domain/config resolution.
- [x] Run focused tests for session profile commands, resolvers, and built-in command specs.
- [x] Run focused workflow-loader/compiler tests covering `:session-profile` grammar.
- [x] Run focused workflow runtime/session-config tests covering snapshot, precedence, delegation, invalid-profile blocking, and unchanged no-profile workflows.
- [x] Run targeted `clj-kondo` over changed Clojure namespaces.
- [x] Run relevant broader unit/Scry suites if focused changes touch shared workflow/session command paths.
- [x] Re-read `design.md`, `plan.md`, `steps.md`, docs, and changed tests/code to verify cross-artifact coherence before implementation review.

## Implementation review follow-ups

- [x] IR1: Fix canonical workflow IR/session schema so supported session-step direct `:thinking-level` validates alongside `:model` and `:session-profile`; add/repair focused coverage proving a session step with `:session-profile` plus explicit `:thinking-level` creates a run and explicit thinking overrides profile thinking.
- [x] IR2: Restore task-207 no-profile delegate speed/effort fallback by distinguishing profile-derived `:speed-mode`/`:effort-override` from inherited effective-config values before `effective-config->snapshot`; add coverage proving no-profile delegate effective speed/effort still falls back to the parent run snapshot while profile-derived speed/effort outrank it.
- [x] IR3: Fix the workflow session-profile test invalid-profile helper so diagnostic `:message` is always a string and does not shadow `clojure.core/name`; rerun the invalid-profile pre-attempt failure test so it exercises profile resolution failure rather than workflow-run schema rejection.
- [x] IR4: Reject canonical LLM judge `[:judge :session :session-profile]` configs and add focused validation coverage; current rejection test only places `:session-profile` beside `:type :llm`, while the closed judge `:session` schema still accepts the profile key contrary to the design's judge-profile out-of-scope boundary.
  - Done: split LLM judge session schema from general session-step schema so judge `:session` retains model/thinking/tool/etc. fields but does not accept `:session-profile`; added canonical `[:judge :session :session-profile]` rejection coverage while preserving the existing top-level judge rejection case.
- [x] IR5: Fix session profile resolution so non-keyword or mixed keyword/string profile names in config are reported as invalid profile-name diagnostics instead of throwing during sorted-map construction; add focused coverage for `/session-profiles` or `profile-snapshot` with mixed `:coding` and `"oops"` profile keys.
  - Done: `resolve-profiles` now uses a deterministic heterogeneous comparator before validation so mixed keys do not throw, non-keyword names resolve to `:invalid-profile-name`, workflow-run snapshots accept/retain invalid non-keyword entries for diagnostics while valid-name lists remain keywords, and command/snapshot tests cover mixed `:coding` + `"oops"` configs.
- [x] IR6: Align profile-name validation with the command grammar by rejecting namespaced keyword profile names (or otherwise making them selectable/displayed unambiguously); add coverage proving `:team/coding` is not listed as a selectable valid `coding` profile and is surfaced with an actionable diagnostic through `/session-profiles` and workflow snapshots.
  - Done: profile-name validation now requires unqualified keywords, so `:team/coding` resolves to `:invalid-profile-name`; `/session-profiles` displays it as `team/coding` with an unqualified-keyword diagnostic instead of a selectable `coding` line; workflow snapshots list it under invalid profile names and retain diagnostics.
- [x] IR7: Fully align profile-name validation with `/session-profile` token grammar, not only namespace rejection: reject or make selectable unqualified keyword names containing command-unparseable characters such as `:fast+coding`; add coverage proving `/session-profiles` does not list such a profile as valid/selectable and workflow snapshots surface an actionable invalid-profile-name diagnostic.
  - Done: shared profile-name validation now reuses the `/session-profile` selectable-token grammar (`[A-Za-z0-9][A-Za-z0-9._-]*`), so `:fast+coding` resolves to `:invalid-profile-name`; command parsing rejects bare/EDN `fast+coding`; `/session-profiles`, Pathom create-run snapshots, and psi-tool workflow create-run snapshots surface invalid diagnostics instead of listing it as selectable. Updated configuration docs.
- [x] IR8: Make `/session-profile :clear` fail as globally reserved/unavailable even when config does not define a `:clear` profile; add command coverage proving both configured and absent `:clear` leave state unchanged and produce the reserved-profile diagnostic rather than an unknown-profile error.
  - Done: `find-valid-profile` now checks reserved names before config presence, synthesizing the same reserved invalid-profile diagnostic for absent `:clear`; command coverage proves configured and absent `:clear` leave session state unchanged and report reserved rather than unknown.

## Task-test-review follow-ups

- [x] TT1: Add workflow runtime/statechart coverage for a session step that requests an unknown or invalid `:session-profile`, proving the failure is recorded before any child execution session is created. Assert state outcomes (for example no workflow-owned child session / nil `:execution-session-id` and actionable failure payload) rather than only the pure `resolve-step-session-config` exception.
  - Done: added statechart runtime coverage for unknown and invalid workflow profiles that drives `runtime/send-and-drain!`, asserts the run/attempt fail before an execution session is recorded, checks nil `:execution-session-id`, no workflow-owned child sessions in canonical state, and actionable profile error data on the recorded attempt.
- [x] TT2: Replace the `commands_session_profile_test.clj` `with-redefs` of `user-config/user-config-file` with a real injectable/nullable config-location seam or a scoped real temp user config location that exercises `user-config/read-config` without monkeypatching the var; keep the command/resolver assertions state-based over real temp config files.
  - Done: command/resolver tests now write real config files beneath a scoped temp `user.home` and exercise `user-config/read-config` through its normal `user-config-file` path; removed the `with-redefs` of `user-config/user-config-file`.
- [x] TT3: Add delegate runtime coverage proving a delegated workflow run copies or derives its `:session-profile-snapshot` from the parent run snapshot, without re-reading mutable config. Assert the child run stores the copied/equivalent snapshot and that a callee step requesting a profile can resolve from that child snapshot after config changes, rather than only testing delegate `:inherited-defaults` projection.
  - Done: added delegate runtime coverage that drives `delegate-step-runtime-result`, mutates real user config after the parent run snapshot exists, asserts the child workflow run stores the copied parent `:session-profile-snapshot`, and proves the callee `:session-profile` step resolves model/thinking/speed/effort from the child snapshot rather than edited config.
- [x] TT4: Add tests proving selected-profile metadata is session-local only: after `/session-profile <name>`, new/fork/workflow-child sessions and cold journal resume may inherit concrete model/thinking/speed/effort according to their existing lifecycle rules, but must not carry `:selected-session-profile` metadata or claim the profile is selected.
  - Done: command/lifecycle coverage now applies a real configured profile and proves new, forked, and workflow-child sessions inherit concrete model/thinking/speed/effort where their lifecycle paths do so while `:selected-session-profile` stays nil; cold journal resume restores journaled model/thinking, drops transient speed/effort, and never restores selected-profile metadata.
- [ ] TT5: Add workflow runtime/session-config coverage for a top-level multi-step workflow where an earlier step has already run, mutable profile config is edited, and a later `:session-profile` step resolves from the run's original `:session-profile-snapshot` rather than the edited config. Assert the later step's concrete model/thinking/speed/effort or child-session request uses the stored snapshot, complementing the existing resume and delegate snapshot tests.
