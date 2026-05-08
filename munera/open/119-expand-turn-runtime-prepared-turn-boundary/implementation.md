2026-05-08

Review pass: current keep/move/split map and normalized prepared-turn input contract

Scope of this pass:
- inspect current `turn-runtime` component surface
- inspect current `prompt_request`, `prompt_recording`, `psi.turn`, and `psi.turn.handlers`
- record the concrete ownership split needed before implementation
- define the normalized input shape that should sit above the lower request builder boundary

Observed current lower ownership already present in `components/turn-runtime/`
- `psi.turn-runtime.core` already owns live prepared-request execution, stream wait/timeout/abort, and execution-result shaping from a prepared request
- `psi.turn-runtime.accumulator` already owns final assistant-message assembly from provider stream events, including tool-call assembly and error shaping
- `psi.turn-runtime.core/classify-assistant-message` already duplicates the assistant-message classification currently also present in `psi.agent-session.prompt-recording`
- this confirms the lower component already contains part of the eventual recording boundary and should absorb the remaining recording namespace rather than coexist with a sibling recording owner

Observed current `prompt_request` split
- clearly session-owned projection/policy:
  - `session->provider-messages`
  - `resolve-api-key`
  - `resolve-llm-stream-idle-timeout-ms`
  - `session->request-options`
  - `resolve-runtime-model`
  - `sorted-contributions`
  - `developer-prompt-section`
  - `effective-system-prompt`
  - `input-expansion`
  - `expand-user-message`
  - `replace-current-user-message`
  - `queued-steering-messages`
- lower assembly currently mixed into the same namespace:
  - `build-provider-conversation`
  - `build-prompt-layers`
  - the final prepared-request map assembly portion of `build-prepared-request`
- key boundary conclusion: the current namespace is not a whole-file move candidate; it needs a split between session-owned normalization/projection and lower request assembly

Observed current `prompt_recording` split
- `extract-tool-calls` and `classify-assistant-message` are purely lower recording helpers
- `build-record-response` is mixed:
  - lower part: classify assistant message and derive deterministic recording summary / next-event decision
  - higher part: `root-state-update` and `:persist/journal-append-message-entry` effect are dispatch/session-owned orchestration surfaces
- key boundary conclusion: the lower recording component should own classification and pure recording-result shaping, but dispatch-visible pure-result construction should remain above the boundary unless a narrower lower return value can be wrapped by higher orchestration

Observed current `psi.turn` split
- clearly higher facade/orchestration:
  - `prompt-dispatch!`
  - `prompt-in!`
  - `prompt-execution-result-in!`
  - `execute-prepared-request-and-journal!`
  - `steer-in!`
  - `follow-up-in!`
  - `queue-while-streaming-in!`
  - `request-interrupt-in!`
  - `abort-in!`
  - `consume-queued-input-text-in!`
  - `last-assistant-message-in`
- lower-looking wrappers currently misplaced in the facade:
  - `build-prepared-request`
  - `build-record-response`
- helper review:
  - `extract-text-from-content-blocks` and `merge-text-sources` are still session-facade helpers because they support queued steering/follow-up session policy rather than lower prepared-turn mechanics
- key boundary conclusion: `psi.turn` should stop owning direct wrappers for lower request/recording namespaces once the expanded `turn-runtime` APIs exist

Observed current `psi.turn.handlers` split
- higher dispatch/effect choreography that should remain above the boundary:
  - `queued-follow-up-batch`
  - `synthetic-user-prompt-effects`
  - `prompt-prepare-request-effects`
  - `prompt-prepare-request-handler`
  - `prompt-record-next-payload`
  - `prompt-record-next-event-effect`
  - `prompt-record-context-usage-effect`
  - `prompt-record-response-handler`
  - `prompt-continue-handler`
  - `prompt-finish-base-result`
  - `consume-follow-up-state-update`
  - `prompt-finish-follow-up-effects`
  - `prompt-finish-handler`
  - `prompt-execute-handler`
- lower helpers that should move with the expanded component if they remain useful after implementation:
  - `prepared-request-state-summary`
  - `prepared-request-query-text`
  - `execution-usage-tokens`
- key boundary conclusion: `psi.turn.handlers` is mostly correctly higher-level today; only small pure helper extraction looks warranted

Normalized prepared-turn input contract for the lower request builder

The lower request builder should consume one normalized map shaped approximately as:

```clojure
{:turn/id                       string
 :turn/session-id               string
 :turn/user-message             message-or-nil
 :turn/input-expansion          expansion-or-nil
 :turn/messages                 provider-visible-messages
 :turn/queued-steering-messages [message ...] | nil
 :turn/runtime-model            runtime-model
 :turn/ai-options               {:thinking-level ...
                                 :api-key ...
                                 :llm-stream-idle-timeout-ms ...
                                 ...provider-request-options}
 :turn/cache-breakpoints        #{...}
 :turn/tool-defs                canonical-tool-defs
 :turn/prompt-component-selection selection-or-nil
 :turn/base-system-prompt       string-or-nil
 :turn/developer-prompt         string-or-nil
 :turn/developer-prompt-source  source-or-nil
 :turn/prompt-contributions     canonical-contributions}
```

Normalization rules for that input:
- all session reads happen above the boundary
- all journal reads happen above the boundary
- all skill/template expansion happens above the boundary
- all prompt-contribution filtering/ordering policy happens above the boundary, or is passed down as already normalized contribution data if the lower builder still assembles final layers
- all auth/provider-request option resolution happens above the boundary
- lower request building may only:
  - build prompt layers from supplied prompt inputs
  - assemble the effective system prompt from supplied normalized layers/inputs
  - filter/shape tool defs only if that shaping is purely a function of supplied normalized values and not registry/session lookup
  - build provider conversation from supplied messages/system/tool inputs
  - build cache projection summaries
  - emit the final `:prepared-request/*` map

Recommended stricter variant of the normalized input
- to keep the lower boundary maximally clean, the higher layer should precompute contribution ordering and tool filtering inputs before the lower builder runs
- if practical during implementation, prefer passing:
  - `:turn/sorted-prompt-contributions`
  - `:turn/filtered-tool-defs`
  instead of the raw broader session prompt state
- this avoids reintroducing prompt-selection policy into `turn-runtime.request`

Expanded `turn-runtime` namespace map
- keep existing:
  - `psi.turn-runtime.core`
  - `psi.turn-runtime.stream`
  - `psi.turn-runtime.accumulator`
- add:
  - `psi.turn-runtime.request`
    - lower prompt-layer assembly
    - provider conversation assembly from normalized inputs
    - final prepared-request map assembly
    - helper summaries such as prepared-request query text if still needed
  - `psi.turn-runtime.recording`
    - assistant-message classification
    - tool-call extraction
    - deterministic lower recording summary / next-step classification
    - optional execution-usage helpers if they remain lower and reusable
- optional only if implementation pressure justifies it:
  - `psi.turn-runtime.summary`
  - not recommended initially; likely over-fragmentation for this step

Concrete keep / move / split map

1. `psi.agent-session.prompt-request`
- keep above boundary:
  - journal projection helpers
  - runtime/auth/provider option resolution
  - runtime-model resolution
  - input expansion through skills/templates
  - queued steering extraction
  - any prompt-contribution selection/ordering policy
  - session snapshot selection policy
- move down:
  - provider conversation assembly from already normalized messages/system/tools/cache inputs
  - prompt-layer packaging from already normalized prompt inputs
  - final prepared-request map assembly
- split note:
  - `build-prepared-request` should become a higher normalization function plus a call into `psi.turn-runtime.request/build-prepared-request`

2. `psi.agent-session.prompt-recording`
- move down:
  - `extract-tool-calls`
  - `classify-assistant-message`
  - pure lower classification of next outcome / next event
- keep above boundary:
  - dispatch-visible `root-state-update`
  - journal append effect shaping
  - session summary update policy
- split note:
  - likely target shape is a higher wrapper that calls a lower `turn-runtime.recording/build-recording-decision` or similarly named pure helper

3. `psi.turn`
- keep:
  - all public dispatch/session facade entrypoints
  - queued steering/follow-up/interrupt helpers
  - journal-facing helper `last-assistant-message-in`
- remove as authoritative lower wrappers once moved:
  - `build-prepared-request`
  - `build-record-response`
- migration preference:
  - `psi.turn` may temporarily keep wrapper fns during migration, but final ownership should be visibly downward to `psi.turn-runtime.request` and `psi.turn-runtime.recording`

4. `psi.turn.handlers`
- keep:
  - lifecycle event/effect choreography
  - follow-up batching
  - synthetic dispatch effects
  - prompt-finish and prompt-continue orchestration
- move if still needed after implementation:
  - `prepared-request-state-summary`
  - `prepared-request-query-text`
  - `execution-usage-tokens`
- split note:
  - `prepared-request-state-summary` may remain higher if it is treated as session-summary projection rather than lower request inspection; this is the main still-ambiguous helper

Ambiguous helpers needing extraction-time review
- `prepared-request-state-summary`
  - lower if treated as generic prepared-request inspection helper
  - higher if treated as session-state summary projection for dispatch-owned observability
- `prepared-request-query-text`
  - lower if reused broadly as a prepared-request inspection helper
  - higher if only needed for dispatch-owned memory-recovery effect choreography
- `execution-usage-tokens`
  - lower if it becomes part of generic execution-result inspection
  - higher if it remains only a dispatch-owned context-usage update helper
- current bias: move `prepared-request-query-text` and `execution-usage-tokens` down; leave `prepared-request-state-summary` above unless implementation reveals broader lower reuse

Direct-consumer decision
- no current production direct-consumer exception was identified from this review
- preferred rule remains:
  - `psi.turn` is the main higher production facade
  - direct production use of `psi.turn-runtime.request` or `psi.turn-runtime.recording` should be avoided unless a namespace genuinely needs helper-level lower prepared-turn APIs without dispatch/session orchestration
- if implementation introduces an exception, record it explicitly in this file

Verification/test ownership decisions
- keep under `components/agent-session/test/`:
  - `prompt_lifecycle_test.clj`
  - mixed dispatch lifecycle tests proving submit/prepare/record/continue/finish orchestration
  - end-to-end prompt-in / abort / follow-up / steering tests
- keep under `components/turn-runtime/test/` and expand there:
  - lower request builder tests for prompt-layer/provider-conversation/prepared-request assembly from normalized inputs
  - lower recording tests for assistant-message classification and recording-decision shaping
  - existing execution-runtime tests in `turn_runtime/core_test.clj`
- test movement preference:
  - add small focused new `turn-runtime` tests rather than moving mixed lifecycle files out of `agent-session`
  - any tests currently asserting `prompt-recording/classify-assistant-message` should migrate to the new `turn-runtime.recording` focused tests

Alignment with umbrella task `105`
- this review confirms `119` remains aligned with `105`
- the correct turn direction is still:
  - expand the existing lower `turn-runtime` component
  - keep `psi.turn` as higher orchestration
  - avoid reviving a sibling `turn-preparation` component

Recommended implementation order from this review
1. introduce `psi.turn-runtime.request` with lower assembly helpers and final prepared-request builder
2. refactor `psi.agent-session.prompt-request/build-prepared-request` into a higher normalization wrapper that calls the lower builder
3. introduce `psi.turn-runtime.recording` with assistant-message classification and lower recording decision helpers
4. refactor `psi.agent-session.prompt-recording/build-record-response` into a higher orchestration wrapper over the lower recording helper
5. move or copy focused lower tests into `components/turn-runtime/test/` before removing old lower owners
6. update `psi.turn` imports/wrappers so it no longer depends authoritatively on `psi.agent-session.prompt-request` or `psi.agent-session.prompt-recording`

2026-05-08 implementation pass

What landed
- added new lower namespaces under `components/turn-runtime/`:
  - `psi.turn-runtime.conversation`
  - `psi.turn-runtime.request`
  - `psi.turn-runtime.recording`
- moved authoritative lower provider-conversation translation into `turn-runtime.conversation`
- moved authoritative lower prompt-layer assembly, effective system prompt assembly, prepared-request query-text extraction, and final prepared-request map assembly into `turn-runtime.request`
- moved authoritative lower assistant-message classification, tool-call extraction, recording-decision shaping, and usage-token extraction into `turn-runtime.recording`
- updated `psi.turn-runtime.core` to delegate assistant-message classification to `turn-runtime.recording`
- refactored `psi.agent-session.prompt-request/build-prepared-request` into a session-owned normalization wrapper over `turn-runtime.request/build-prepared-request`
- refactored `psi.agent-session.prompt-recording/build-record-response` into a higher orchestration wrapper over `turn-runtime.recording/build-recording-decision`
- updated `psi.turn.handlers` to use lower helpers from `turn-runtime.request` and `turn-runtime.recording`
- added focused lower tests in:
  - `components/turn-runtime/test/psi/turn_runtime/request_test.clj`
  - `components/turn-runtime/test/psi/turn_runtime/recording_test.clj`

Important boundary note
- extracting lower request assembly also required moving the pure message->provider conversation translator below the boundary because `turn-runtime` cannot depend upward on `agent-session` without recreating the component-cycle problem fixed in task `103`
- `psi.agent-session.conversation` now remains only as a compatibility wrapper delegating to `psi.turn-runtime.conversation`

Compatibility / remaining wrappers
- `psi.agent-session.prompt-request` and `psi.agent-session.prompt-recording` still exist as compatibility/session-normalization wrappers for current callers
- `psi.turn` still calls those wrappers for its higher facade API, but the lower authoritative implementation now lives in `psi.turn-runtime.*`
- repo search confirms the lower authoritative helper implementations (`build-provider-conversation`, `build-prompt-layers`, `extract-tool-calls`, `classify-assistant-message`) now live in `turn-runtime`; remaining `agent-session` definitions are delegating wrappers rather than authoritative lower owners

Verification
- lint green for touched namespaces
- focused unit verification green:
  - `bb clojure:test:unit --focus psi.turn-runtime.core-test`
  - `bb clojure:test:unit --focus psi.turn-runtime.request-test --focus psi.turn-runtime.recording-test --focus psi.agent-session.prompt-lifecycle-test`
