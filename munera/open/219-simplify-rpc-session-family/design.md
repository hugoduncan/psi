# Simplify `psi.rpc.session` architecture family

## Intent

Perform a behaviour-preserving architectural simplification of the RPC session family selected by Gordian. The work should reduce hidden coupling density and clarify ownership boundaries inside `psi.rpc.session` without changing RPC session behaviour, transport contracts, command semantics, Emacs/TUI-facing payloads, or workflow/runtime semantics.

This task is constrained to architecture simplification. It must establish a clean pre-simplification test net before implementation, then validate after implementation with Gordian compare/gate artifacts.

## Selected candidate

Authoritative selection source: `munera/open/219-simplify-rpc-session-family/architecture-targets.edn`.

The selected top-level `:winner` from `bb gordian architecture-targets --edn` is:

- `:candidate/id`: `[:family "psi.rpc.session"]`
- `:candidate/type`: `:family`
- `:candidate/label`: `"psi.rpc.session"`
- `:rank`: `1`
- `:eligible?`: `true`
- `:score`: `102.89000000000014`
- `:confidence`: `:low`
- `:members`:
  - `psi.rpc.session.command-pickers`
  - `psi.rpc.session.command-results`
  - `psi.rpc.session.command-resume`
  - `psi.rpc.session.command-tree`
  - `psi.rpc.session.commands`
  - `psi.rpc.session.emit`
  - `psi.rpc.session.frontend-actions`
  - `psi.rpc.session.navigation`
  - `psi.rpc.session.projections`
  - `psi.rpc.session.prompt`
  - `psi.rpc.session.streams`

Winner score breakdown copied from `architecture-targets.edn`:

- `:finding-score`: `95.89000000000014`
- `:concentration-bonus`: `6.0`
- `:cohesion-bonus`: `1.0`
- `:change-pressure-bonus`: `0.0`
- `:test-risk-bonus`: `0.0`
- `:penalties`: `0.0`

Winner ranking evidence summary copied from `architecture-targets.edn`:

- category counts: `{:hidden-change 3, :hidden-conceptual 90, :hub 7, :sdp-violation 2}`
- severity counts: `{:medium 56, :low 46}`
- top reason: `{:reason :concentration-bonus, :score 6.0}`
- top findings include hidden-change signals involving `extensions.mcp-tasks-run` / `extensions.plan-state-learning` with `psi.rpc.session.projections`, internal hidden-change between `psi.rpc.session.commands` and `psi.rpc.session.frontend-actions`, and hidden-conceptual signals involving `psi.rpc.session.streams` and `psi.rpc.session.command-results`.
- hidden-coupling hubs: `psi.rpc.session.commands` degree `9`, `psi.rpc.session.command-results` degree `7`, `psi.rpc.session.command-tree` degree `7`, `psi.rpc.session.navigation` degree `7`, `psi.rpc.session.command-pickers` degree `6`, `psi.rpc.session.command-resume` degree `6`, `psi.rpc.session.frontend-actions` degree `5`, `psi.rpc.session.projections` degree `4`, `psi.rpc.session.prompt` degree `4`, `psi.rpc.session.streams` degree `4`, and `psi.rpc.session.emit` degree `3`.

## Why this target was selected

The selection is based on the authoritative `architecture-targets.edn` ranking. The top-level winner is eligible, ranked first, and has a much larger architecture score than narrower namespace candidates. Its evidence indicates a family-level hotspot rather than a single isolated namespace problem:

- the finding score is dominated by hidden conceptual coupling (`90`) and hidden change (`3`) inside and around the family;
- the concentration bonus points to repeated coupling through the same small set of member namespaces;
- multiple sibling members are high-degree hidden-coupling hubs, so local cleanup in one file could merely shift orchestration sideways unless the family seam is bounded first;
- structural role warnings (`:sdp-violation` count `2`) suggest some member boundaries may not line up with stable architectural roles.

The task should therefore simplify the family-level RPC session seam and internal ownership split, not chase unrelated downstream RPC, TUI, extension, or workflow-runtime behaviour.

## Supplemental target-issues framing

Supplemental source: `munera/open/219-simplify-rpc-session-family/target-issues.edn`.

### Observations

`target-issues.edn` reports these issues:

- `:dense-hidden-conceptual-coupling` (`:high`, reliability `:high`): the selected family is dominated by hidden conceptual coupling rather than a single isolated structural warning.
- `:hidden-coupling-hotspot-concentration` (`:high`, reliability `:high`): a small subset of member namespaces concentrates most hidden-coupling evidence; top hubs are `psi.rpc.session.commands`, `psi.rpc.session.command-results`, and `psi.rpc.session.command-tree`.
- `:command-flow-or-ownership-blur` (`:medium`, reliability `:medium`): multiple sibling namespaces share hidden flow and ownership signals rather than one file clearly owning the behaviour.
- `:family-not-single-file-hotspot-shape` (`:medium`, reliability `:high`): narrower namespace candidates (`command-results`, `commands`, `command-resume`) also score strongly inside the same family, arguing for a bounded family-level seam instead of a single-file cleanup.
- `:structural-role-misalignment` (`:medium`, reliability `:high`): structural warning categories indicate current boundaries do not line up cleanly with architectural role.

### Hypotheses

`target-issues.edn` proposes these hypotheses; they must be treated as hypotheses to validate against source and tests, not as already-proven design decisions:

- `:implicit-shared-decision-surface` (`:high` confidence): the family likely hides a shared decision or shaping surface distributed across several member namespaces.
- `:family-boundary-misalignment` (`:medium` confidence): the family boundary likely groups behaviour that needs a clearer internal ownership split rather than more sibling coordination.
- `:overloaded-structural-roles` (`:medium` confidence): at least one member namespace likely mixes architectural roles, reinforcing the hidden-coupling cluster.

### Refactoring directions

`target-issues.edn` suggests these directions; implementation may choose only behaviour-preserving, test-net-covered changes that fit the existing architecture:

- `:extract-shared-decision-surface`: look for a smaller shared decision or data-shaping seam around `commands`, `command-results`, and `command-tree`.
- `:separate-family-internal-roles`: separate member namespaces by architectural role before broader extraction.
- `:bound-family-hotspot-before-splitting`: bound the family-level hotspot so follow-on refactors do not just move orchestration between sibling namespaces.

### Review questions

The implementation and review should answer:

- Which shared decision, shaping, or state transitions explain repeated conceptual overlap around `commands`, `command-results`, and `command-tree`?
- Which behaviour should clearly belong to one namespace instead of being coordinated across sibling members?
- Do member namespaces currently mix stable model, traversal/orchestration, and output-shaping responsibilities?

### Success signals

After implementation, success means:

- reduced hidden conceptual density for the target;
- reduced hotspot concentration;
- clearer ownership boundaries across sibling namespaces;
- fewer structural warnings;
- no shifted orchestration bloat into a different namespace.

These signals supplement, but do not replace, the mandatory Gordian validation commands below.

## Scope

### Target namespaces

`:target/namespaces` are exactly the selected winner's family `:members` from the captured ranking:

```edn
[psi.rpc.session.command-pickers
 psi.rpc.session.command-results
 psi.rpc.session.command-resume
 psi.rpc.session.command-tree
 psi.rpc.session.commands
 psi.rpc.session.emit
 psi.rpc.session.frontend-actions
 psi.rpc.session.navigation
 psi.rpc.session.projections
 psi.rpc.session.prompt
 psi.rpc.session.streams]
```

Do not recompute or expand this family membership later from a changed worktree.

### Target source areas

`:target/source-areas` are the resolved production source files for the target namespaces:

```edn
["components/rpc/src/psi/rpc/session/command_pickers.clj"
 "components/rpc/src/psi/rpc/session/command_results.clj"
 "components/rpc/src/psi/rpc/session/command_resume.clj"
 "components/rpc/src/psi/rpc/session/command_tree.clj"
 "components/rpc/src/psi/rpc/session/commands.clj"
 "components/rpc/src/psi/rpc/session/emit.clj"
 "components/rpc/src/psi/rpc/session/frontend_actions.clj"
 "components/rpc/src/psi/rpc/session/navigation.clj"
 "components/rpc/src/psi/rpc/session/projections.clj"
 "components/rpc/src/psi/rpc/session/prompt.clj"
 "components/rpc/src/psi/rpc/session/streams.clj"]
```

Resolution was performed from namespace declarations under production source roots. Conventional path mapping was not needed.

### Allowed adjacent source areas

`:target/allowed-adjacent-source-areas` default semantics: no adjacent production source changes are allowed unless the plan names the specific root-relative file, explains why the target cannot be simplified without it, and keeps the change behaviour-preserving. Any adjacent source change must remain a narrow contract-alignment or call-site migration for the target seam; it must not broaden the task into TUI, Emacs, extension, workflow-runtime, or agent-session redesign.

Initially allowed adjacent areas are empty:

```edn
[]
```

If planning discovers a necessary adjacent file, `plan.md` must list it explicitly before implementation.

### Affected test areas

`:target/affected-test-areas` semantics: include existing tests that exercise RPC session command/result/navigation/projection/prompt/stream behaviour, plus any new characterization tests required to close coverage gaps before simplification. Candidate existing areas include:

```edn
["components/rpc/test/psi/rpc_command_results_test.clj"
 "components/rpc/test/psi/rpc_prompt_command_test.clj"
 "components/rpc/test/psi/rpc_prompt_test.clj"
 "components/rpc/test/psi/rpc_session_navigation_test.clj"
 "components/rpc/test/psi/rpc_events_test.clj"
 "components/rpc/test/psi/rpc_invariants_test.clj"
 "components/rpc/test/psi/rpc_ops_test.clj"
 "components/rpc/test/psi/rpc_test.clj"]
```

The coverage review may include more RPC tests when they cover target behaviour. Tests should assert state or outputs, not implementation interactions.

## Non-goals

- Do not change externally observable RPC protocol payloads, event names, command result shapes, prompt/stream behaviours, or session navigation semantics.
- Do not redesign Emacs, TUI, app-runtime, workflow-runtime, extensions, or agent-session orchestration outside the named target seam.
- Do not introduce compatibility shims or adapters to hide an interface mismatch; prefer explicit contract simplification inside the target.
- Do not move functionality simply to reduce a metric if ownership becomes less clear or tests become weaker.
- Do not perform broad file reorganization, namespace renaming, or family expansion beyond the captured target members.
- Do not add mocks for logic dependencies. Use real/nullable infrastructure seams and assert outputs/state.

## Blast-radius limits

- Production source changes should be limited to `:target/source-areas` unless `plan.md` explicitly names a required adjacent file under `:target/allowed-adjacent-source-areas`.
- Test changes may touch affected RPC tests and add characterization coverage for target behaviour only.
- Documentation/changelog changes are only required if user-visible behaviour changes; this task intends no user-visible behaviour change.
- The implementation must remain a minimal semantics-preserving transformation guided by the pre-simplification test net.

## Existing behaviour that must remain unchanged

- RPC command dispatch, command-picker, command-tree, command-resume, and command-result semantics.
- RPC session prompt and stream behaviour, including payload shape and ordering guarantees already covered by tests.
- RPC projection and frontend-action outputs consumed by Emacs/TUI/app-runtime.
- Navigation semantics in `psi.rpc.session.navigation`.
- Public RPC transport behaviour and existing tests in `components/rpc/test`.
- Workflow/session lifecycle semantics observable through RPC session surfaces.

## Task-local artifacts

All references are worktree-root-relative:

- selection ranking: `munera/open/219-simplify-rpc-session-family/architecture-targets.edn`
- before baseline: `munera/open/219-simplify-rpc-session-family/before-diagnose.edn`
- supplemental framing: `munera/open/219-simplify-rpc-session-family/target-issues.edn`
- this design: `munera/open/219-simplify-rpc-session-family/design.md`

Do not rely on bare filenames resolving from the task directory.

## Pre-simplification test-net requirements

Implementation is forbidden until all gates below pass and are recorded in task artifacts.

1. Clean baseline gate:
   - Run the existing relevant RPC test set from a clean worktree.
   - Record command(s), result, and any failures.
   - If baseline tests fail for reasons unrelated to this task and cannot be resolved locally, stop before implementation.
2. Coverage review gate:
   - Map target behaviours and source areas to existing tests.
   - Identify behaviour gaps for command flow, result shaping, navigation, prompt/stream/projection outputs, and frontend-action payloads.
3. Characterization-test fix loop:
   - For each fixable coverage gap, add characterization tests before simplification.
   - Characterization tests must prove current behaviour, not desired refactored structure.
   - Re-run the focused test set until green.
4. Infeasible-coverage stop:
   - If required behaviour cannot be characterized safely, stop and record why. Do not implement simplification.
5. Diff/baseline gate:
   - Before implementation, verify the diff contains only task setup and characterization-test-net changes.
   - The focused baseline/characterization suite must be green.
6. No implementation before coverage and diff gates pass:
   - Refactoring production source before the clean baseline, coverage review, characterization loop, and diff/baseline gate pass is out of scope.

## Ownership constraints

### Shared command/navigation/action/result surfaces

If implementation extracts or consolidates any shared decision surface around `commands`, `command-results`, `command-tree`, `command-pickers`, `command-resume`, `navigation`, or `frontend-actions`, the extracted seam must preserve existing ownership boundaries:

- RPC may own protocol adaptation: request ids, RPC event names, wire payload encoding, response frames, connection-local focus needed for RPC delivery, and subscriber/transport-specific fanout.
- Adapter-neutral selector, session-tree, navigation, picker, frontend-action, and command-result semantics that are needed by both TUI and Emacs belong with existing `app-runtime` public-model/selector/navigation/action ownership, not in a new RPC-owned domain layer.
- A new shared RPC helper is acceptable only when its contract is narrow RPC protocol adaptation over already-shaped domain/app-runtime data. It must not become a semantic orchestration hub that decides UI-domain behaviour for multiple adapters.
- If a small adapter-neutral semantic helper is required to remove duplication, planning must either route it to an existing `app-runtime` owner or explicitly authorize a narrow adjacent `app-runtime` source area before implementation; it must not be hidden inside the RPC family.
- Behaviour-preserving simplification should make the semantic owner obvious at call sites: RPC translates and emits, while app-runtime/domain code shapes adapter-neutral meaning.

### Projection, stream, and emit delivery surfaces

Simplification of `projections`, `streams`, or `emit` must preserve the current projection delivery architecture:

- Canonical state and `app-runtime` public models remain authoritative for context, footer, extension UI, selector/session-tree, frontend-action, and other UI-facing projection semantics.
- RPC may perform subscriber-aware fanout, protocol payload recomputation/adaptation, response framing, and connection-local focus handling. These are delivery concerns, not a second source of projection truth.
- Projection payloads must be recomputed from the authoritative context/state/public-model functions when invalidations or progress events arrive; RPC must not maintain cached canonical projection snapshots, duplicated footer/context/session-tree models, or projection state that can drift from app-runtime state.
- Delivery must stay event/invalidation driven through existing listener/progress/event hooks. Do not replace it with polling-style projection refresh, Emacs/TUI-specific RPC timers, or RPC-local freshness models.
- Any refactor that touches retry/footer refresh, context updates, extension UI snapshots, or navigation emission must keep RPC as the subscriber-aware protocol fanout/adaptation layer over app-runtime-owned semantics.

## Implementation constraints

- Prefer extracting or clarifying one shared decision/data-shaping seam over moving orchestration sideways, subject to the ownership constraints above.
- Keep computation and flow-control separated where local code permits.
- Preserve simple, consistent data shapes across target namespaces.
- Make invalid states less representable where a target-local contract can be clarified without broad redesign.
- After every edit, re-read changed files and run relevant formatting/lint/test checks.

## Post-implementation Gordian validation

After implementation, write `munera/open/219-simplify-rpc-session-family/after-diagnose.edn`, `munera/open/219-simplify-rpc-session-family/after-architecture-targets.edn`, `munera/open/219-simplify-rpc-session-family/architecture-compare.edn`, and `munera/open/219-simplify-rpc-session-family/architecture-gate.edn` using these commands from the worktree root:

```bash
bb gordian diagnose --edn > munera/open/219-simplify-rpc-session-family/after-diagnose.edn
bb gordian architecture-targets --edn > munera/open/219-simplify-rpc-session-family/after-architecture-targets.edn
bb gordian compare munera/open/219-simplify-rpc-session-family/before-diagnose.edn munera/open/219-simplify-rpc-session-family/after-diagnose.edn --edn > munera/open/219-simplify-rpc-session-family/architecture-compare.edn
bb gordian gate --baseline munera/open/219-simplify-rpc-session-family/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn > munera/open/219-simplify-rpc-session-family/architecture-gate.edn
```

Validation acceptance:

- All focused and relevant baseline/characterization tests pass.
- `bb gordian gate ...` succeeds under the specified fail conditions.
- `architecture-compare.edn` shows no new cycles, no new high findings, and zero new medium findings.
- The target's hidden-coupling density or concentration improves, or any non-improvement is explicitly explained with source/test evidence and no regression under the gate.
- Review gates confirm behaviour preservation, test-net adequacy, architectural fit, and code shape.

## Acceptance criteria

- A plan is created only after this design is reviewed and unambiguous.
- The pre-simplification test net is established before production refactoring.
- Production changes are limited to the captured target source areas unless the plan explicitly authorizes a narrow adjacent source area.
- Existing RPC session behaviour remains unchanged.
- Post-implementation Gordian validation artifacts are captured at the worktree-root-relative paths listed above; the authoritative compare/gate artifact names are `architecture-compare.edn` and `architecture-gate.edn`, matching the architecture workflow validation-capture and review-gate contract.
- The final implementation passes tests, lint/format checks relevant to touched files, Gordian gate, and explicit review gates.
