# 219 — Plan

Derived from the stable `design.md` after architecture-fit, ambiguity, and
inconsistency design follow-ups were resolved (`design-steps.md` unchecked count
0). The design is complete enough to plan: it fixes the selected Gordian family,
target source membership, blast-radius limits, ownership boundaries between RPC
and `app-runtime`, the pre-simplification test-net gate, and the required
post-implementation Gordian validation artifacts.

## Approach

Make a behaviour-preserving architectural simplification inside the captured
`psi.rpc.session` family, guarded by a pre-refactor characterization test net.
The implementation should first prove current RPC behaviour, then choose the
smallest target-local seam that clarifies ownership and reduces hidden conceptual
coupling without changing wire events, response frames, command result payloads,
prompt/stream behaviour, projection semantics, or workflow/session semantics.

The initial implementation strategy is:

1. Establish and record a clean focused RPC baseline before any production
   refactor.
2. Build `munera/open/219-simplify-rpc-session-family/coverage-map.md`
   from target behaviours to existing/new tests, especially command
   dispatch/results, command tree/resume/pickers, navigation, prompt/stream
   progress, projection delivery, and frontend action results.
3. Add characterization tests for fixable behaviour gaps before production code
   changes. If a required behaviour cannot be safely characterized, stop before
   simplification and record why.
4. Inspect the target family and select one bounded ownership seam to simplify.
   Prefer clarifying target-local RPC protocol adaptation responsibilities over
   moving domain/UI semantics into a new RPC-owned hub.
5. Refactor only the recorded target source files unless this plan is explicitly
   amended before implementation to authorize a narrow adjacent source file.
6. Re-run the focused RPC verification suite pinned below, targeted
   lint/format checks, and the mandatory Gordian after/compare/gate validation
   commands.
7. Run the explicit review gates required by the design and record any
   follow-ups in `steps.md` / `implementation.md`.

## Focused RPC verification suite

The focused RPC baseline/characterization suite for Slices 1, 2, and 5 is the
following exact command from the worktree root:

```bash
bb clojure:test:scry --dir components/rpc/test \
  --namespace psi.rpc-command-results-test \
  --namespace psi.rpc-prompt-command-test \
  --namespace psi.rpc-prompt-test \
  --namespace psi.rpc-session-navigation-test \
  --namespace psi.rpc-events-test \
  --namespace psi.rpc-invariants-test \
  --namespace psi.rpc-ops-test \
  --namespace psi.rpc-test
```

Those namespaces correspond to these design-listed affected test files:

- `components/rpc/test/psi/rpc_command_results_test.clj`
- `components/rpc/test/psi/rpc_prompt_command_test.clj`
- `components/rpc/test/psi/rpc_prompt_test.clj`
- `components/rpc/test/psi/rpc_session_navigation_test.clj`
- `components/rpc/test/psi/rpc_events_test.clj`
- `components/rpc/test/psi/rpc_invariants_test.clj`
- `components/rpc/test/psi/rpc_ops_test.clj`
- `components/rpc/test/psi/rpc_test.clj`

Characterization tests should normally be added inside those namespaces so the
same suite remains stable across Slices 1/2/5. If Slice 2 must add a new RPC test
namespace, update this exact command, `steps.md`, and
`characterization-baseline.edn` before committing Slice 2; do not leave an
unlisted characterization namespace outside the focused suite.

## Coverage map artifact

The authoritative coverage map/gap record is
`munera/open/219-simplify-rpc-session-family/coverage-map.md`. Slice 2 fills it
before production edits; Slice 5 rechecks it when recording final focused-suite
verification. Minimal shape:

- `## Verification command`: the focused RPC command above, plus any explicitly
  authorized additions.
- `## Source-area coverage`: one subsection per target source file naming covered
  behaviours and tests/vars.
- `## Behaviour coverage`: command/result, picker/model/thinking/frontend-action,
  command-tree/resume/navigation, prompt/stream, and projection/emit coverage.
- `## Gaps and disposition`: each gap as `covered-by`, `added-test`,
  `infeasible-stop`, or `accepted-existing-coverage`, with terse evidence.

## Key decisions from design

- The selected target is exactly the captured `[:family "psi.rpc.session"]`
  winner from `architecture-targets.edn`; do not recompute or expand membership
  from the changed worktree.
- Production source changes are initially authorized only under these target
  files:
  - `components/rpc/src/psi/rpc/session/command_pickers.clj`
  - `components/rpc/src/psi/rpc/session/command_results.clj`
  - `components/rpc/src/psi/rpc/session/command_resume.clj`
  - `components/rpc/src/psi/rpc/session/command_tree.clj`
  - `components/rpc/src/psi/rpc/session/commands.clj`
  - `components/rpc/src/psi/rpc/session/emit.clj`
  - `components/rpc/src/psi/rpc/session/frontend_actions.clj`
  - `components/rpc/src/psi/rpc/session/navigation.clj`
  - `components/rpc/src/psi/rpc/session/projections.clj`
  - `components/rpc/src/psi/rpc/session/prompt.clj`
  - `components/rpc/src/psi/rpc/session/streams.clj`
- No adjacent production source files are authorized at planning time. If source
  inspection shows that a small adapter-neutral semantic helper must move to an
  existing `app-runtime` owner, stop and update the plan/design before editing
  that adjacent file.
- RPC may own request ids, RPC event names, wire payload encoding, response
  frames, subscriber fanout, protocol payload recomputation/adaptation, and
  connection-local focus. Adapter-neutral selector/navigation/picker/action/result
  semantics must remain with existing `app-runtime`/domain owners.
- Projection/stream/emit changes must keep canonical state and `app-runtime`
  public models authoritative. Do not introduce RPC-local cached projection
  source-of-truth state, polling refresh, or adapter-specific freshness models.
- Documentation and changelog changes are not expected because the task intends
  no user-visible behaviour change. If implementation changes observable
  behaviour, treat that as scope drift unless the design is deliberately revised.

## Risks

- **R1 — Weak or dirty baseline.** RPC tests may depend on mutable local state or
  unrelated dirt. Mitigation: record `git status`, focused commands, and results
  in task artifacts before refactoring; stop if target/source areas are already
  ambiguous.
- **R2 — Coverage gap discovered late.** Hidden coupling may sit in behaviour not
  currently characterized by tests. Mitigation: coverage review and
  characterization-test fix loop are mandatory before production edits.
- **R3 — Ownership seam hypothesis is wrong.** `target-issues.edn` hypotheses are
  evidence, not proof. Mitigation: inspect source and tests after the test net,
  record the chosen seam, and choose no-op/stop over speculative broad moves.
- **R4 — New RPC semantic hub.** Extracting shared command/tree/result logic could
  accidentally move adapter-neutral UI semantics into RPC. Mitigation: keep new
  helpers narrow protocol adapters over already-shaped domain/app-runtime data;
  stop before adjacent `app-runtime` work unless explicitly authorized.
- **R5 — Projection drift.** Simplifying `projections`, `streams`, or `emit` could
  duplicate canonical projection state. Mitigation: preserve event/invalidation
  driven recomputation from authoritative context/state/public-model functions.
- **R6 — Metric-only refactor.** A change could reduce one Gordian signal while
  moving orchestration sideways or weakening local comprehension. Mitigation:
  require source/test evidence, focused tests, Gordian compare/gate, and
  architecture/code-shape reviews.
- **R7 — Non-improving Gordian result.** A behaviour-preserving minimal change may
  pass the gate but not materially improve density/concentration. Mitigation:
  explain non-improvement with evidence and ensure no new cycles/high/medium
  findings under the mandatory gate.

## Slice order

1. **Slice 1 — Preflight and clean baseline.** Verify task artifacts, confirm the
   worktree state, run the focused RPC baseline from a clean pre-refactor state,
   and write a baseline artifact with commands/results/status.
2. **Slice 2 — Coverage review and characterization gate.** Fill
   `coverage-map.md` by mapping all target behaviours/source areas to existing
   tests, add characterization tests for fixable gaps, rerun the pinned focused
   RPC suite, and enforce a pre-implementation diff gate that permits only task
   artifacts and characterization work.
3. **Slice 3 — Ownership seam selection.** Inspect the target family after the
   test net is green, record the selected shared decision/data-shaping seam, and
   confirm whether the implementation will touch command/result/navigation
   surfaces, projection/stream/emit delivery surfaces, or both. Stop before
   source edits if the chosen seam requires unauthorized adjacent production
   files.
4. **Slice 4 — Target-local architecture simplification.** Apply the smallest
   production refactor inside the authorized target files. Likely candidates are
   duplicated command result/rehydration/navigation emission flow, unclear
   division between command dispatch and result adaptation, or projection/stream
   delivery helpers that can be made more obviously subscriber/fanout-only. Keep
   semantic owners obvious at call sites.
5. **Slice 5 — Focused verification and Gordian validation.** Re-run the pinned
   focused RPC suite and targeted lint/format checks, recheck `coverage-map.md`
   for stale gaps, then capture `after-diagnose.edn`,
   `after-architecture-targets.edn`, `architecture-compare.edn`, and
   `architecture-gate.edn` with the exact design commands.
6. **Slice 6 — Review gates and closure.** Run/record the architecture
   workflow's exact `review-step` skill sequence in order:
   `task-implementation-review`, `task-test-review`,
   `review-implementation-architecture`, `test-shaper`, `review-task-docs`, and
   `code-shaper`. The architecture gate uses the implementation-specific
   `review-implementation-architecture` skill, not the design-only
   `review-task-architecture` skill. Execute any follow-up checklist items;
   confirm docs/changelog remain unnecessary unless behaviour changed; summarize
   final verification.

## Non-blocking notes

- The current design has no blocking ambiguities. The only planning-time caution
  is that the exact production seam must be chosen from source/test evidence
  after the pre-simplification test net is established.
- The affected test list in `design.md` is a starting set, not a cap on tests;
  additional RPC tests may be included when coverage review shows they exercise
  the recorded target behaviour.
- `architecture-compare.edn` and `architecture-gate.edn` are the authoritative
  compare/gate artifact names for post-implementation validation.
