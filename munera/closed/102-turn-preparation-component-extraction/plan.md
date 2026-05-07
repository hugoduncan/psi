Approach:
- treat this as a structural extraction, not a semantic redesign
- create `components/turn-preparation/` with two authoritative namespaces: `psi.turn-preparation.request` and `psi.turn-preparation.recording`
- use `clj-surgeon` first for structural inspection (`-op :deps`, `-op :ls`) and use structural moves where helpful, but keep the slice small and comprehensible rather than maximizing tool usage for its own sake
- move the pure request namespace first, then move the pure recording namespace, then update `psi.turn` and remaining consumers in one slice so the new namespaces become authoritative immediately
- prefer no compatibility shim; introduce a temporary shim only if the edit sequence concretely requires it to keep the tree compiling during migration, and remove it before completion

Authoritative target namespaces:
- `components/turn-preparation/src/psi/turn_preparation/request.clj` -> `psi.turn-preparation.request`
- `components/turn-preparation/src/psi/turn_preparation/recording.clj` -> `psi.turn-preparation.recording`

Structural findings from `clj-surgeon`:
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/prompt_request.clj`
  - shows a coherent request-projection namespace with `build-prepared-request` as the assembly root over provider-message projection, request option projection, system-prompt assembly, prompt-layer projection, and input expansion helpers
- `clj-surgeon -op :deps -file components/agent-session/src/psi/agent_session/prompt_recording.clj`
  - shows a coherent response-classification namespace with `build-record-response` as the assembly root over `classify-assistant-message`
- `clj-surgeon -op :deps -file components/agent-session/src/psi/turn.clj`
  - confirms that `psi.turn` currently delegates directly to these two namespaces only for `build-prepared-request` and `build-record-response`
- this supports a narrow pure extraction seam that complements task `101`'s impure runtime extraction

Implementation sequence:
1. create `components/turn-preparation/` directories and destination namespaces/files
2. update project configuration so the new component participates in normal source and test paths
   - root `deps.edn`
   - root `tests.edn`
   - `components/agent-session/deps.edn`
   - any additional explicit alias path lists only if required
3. move `prompt_request.clj` implementation to `psi.turn-preparation.request`
4. move `prompt_recording.clj` implementation to `psi.turn-preparation.recording`
5. update `psi.turn`
   - require the new preparation namespaces
   - keep `psi.turn` as the public turn lifecycle API and the callback boundary used by `context.clj`
6. update direct production consumers
   - any remaining production namespaces requiring the old preparation namespaces
   - preserve `context.clj` binding through `psi.turn` rather than rebinding context directly to `psi.turn-preparation.*`
7. update direct test consumers and move clearly component-owned tests when that improves ownership clarity without widening scope
8. if any temporary compatibility shim was introduced, remove it before verification; otherwise remove the old source files in this slice rather than leaving inert duplicates
9. run focused verification for the new component plus at least one higher-level consuming path
10. record final ownership and migration notes in `implementation.md`

Consumer migration expectations:
- public turn API should call `psi.turn-preparation.request` and `psi.turn-preparation.recording` rather than `psi.agent-session.prompt-request` and `psi.agent-session.prompt-recording`
- preferred steady-state production dependency slope should be:
  - `components/agent-session/src/psi/agent_session/context.clj` -> `psi.turn`
  - `psi.turn` -> `psi.turn-preparation.request`
  - `psi.turn` -> `psi.turn-preparation.recording`
  - `psi.turn` -> `psi.turn-runtime.core`
- lower-level consumers that need helper-level APIs may depend directly on `psi.turn-preparation.*` when that is the cleaner dependency shape; each such case should be minimized and recorded in `implementation.md`
- do not add wrapper functions to `psi.turn` solely to hide legitimate helper-level usage if that would duplicate API surfaces
- completion requires a final repo search confirming no remaining authoritative uses of:
  - `psi.agent-session.prompt-request`
  - `psi.agent-session.prompt-recording`

Configuration changes expected:
- `deps.edn`
  - add local component dep for `psi/turn-preparation`
  - add `components/turn-preparation/src` and `components/turn-preparation/test` where explicit path lists require them
- `tests.edn`
  - add `components/turn-preparation/src`
  - add `components/turn-preparation/test`
- `components/agent-session/deps.edn`
  - add local component dep on `../turn-preparation`
- `tests-component-isolated.edn`
  - update only if this slice adds a dedicated isolated component test run

Testing strategy:
- preserve existing proof where possible
- move only the tests that clearly belong to the extracted component boundary
- rename moved component-owned tests to `psi.turn-preparation.*-test` namespaces so namespace ownership matches component ownership
- avoid turning this task into a broad prompt-test architecture rewrite
- keep higher-level lifecycle tests where they are unless ownership clearly changes
- record in `implementation.md` which tests moved into `components/turn-preparation/test/psi/turn_preparation/` versus which stayed under `components/agent-session/test`, and why

Likely proof split:
- component-owned tests for request preparation and recording behavior move under `components/turn-preparation/test`
- higher-level integration/lifecycle tests remain under `components/agent-session/test`
- preferred movement rule:
  - move whole focused test files whose primary subject is request option projection, system-prompt assembly, prompt-layer projection, input expansion, `build-prepared-request`, assistant-message classification, or `build-record-response`
  - keep mixed higher-level lifecycle/integration files in place and update their requires/usages only
  - add a small new component-owned focused test file if clearer than splitting a mixed-purpose higher-level file
  - keep tests whose primary subject is lifecycle orchestration, dispatch handlers, context wiring, journaling effect execution, scheduling, workflow progression, or session mutation behavior

Verification intent:
- focused tests from the new component location proving request preparation and response recording behavior
- focused higher-level agent-session tests proving `psi.turn` and surrounding lifecycle still prepare and record through the extracted component unchanged in behavior
- no behavior changes beyond namespace/component ownership

Representative focused verification surfaces after migration:
- moved component-owned tests under `components/turn-preparation/test/psi/turn_preparation/`
- higher-level consuming-path tests including:
  - `psi.agent-session.prompt-lifecycle-test`
- exact focused commands depend on the final moved test namespaces and must be recorded in `implementation.md` during execution

Refactoring guardrails:
- minimize the namespace dependency tree; aim for a tree rather than a broader graph
- maximize orthogonality
- do not leave wrapper-local duplicate behavior in the old namespaces
- do not pull lifecycle/runtime responsibilities down into the extracted preparation component
- avoid opportunistic cleanup in prompt lifecycle orchestration, workflow code, or session handlers

Main risks:
- incomplete consumer migration
- ownership blur if lifecycle/runtime concerns get pulled into the extracted component
- overusing compatibility shims and accidentally leaving them in place
- unnecessary test churn that obscures the structural change
- accidental re-opening of runtime-boundary decisions already settled by completed task `101`
