# Pre-turn request augmentation

## Goal

Add a core-owned pre-turn request augmentation phase so privileged extensions such as `extensions/context-manager` can enrich a submitted user prompt before the parent turn is prepared and executed.

The phase runs after the user prompt has been submitted and assigned a `turn-id`, and before `prompt-prepare-request` builds the prepared request. It is intentionally part of the critical path: the parent turn waits for augmentation to complete, fail, or be canceled before execution continues.

## Problem

The current `context-manager` extension only observes `session_turn_finished` via fire-and-forget extension notification. That event is suitable for post-turn observation, but not for modifying the upcoming request:

- notification handlers return no data to the turn pipeline;
- returned values are discarded by `:notify/extension-dispatch`;
- post-turn events occur after the request has already executed;
- unrecorded extension output would break replay if it influenced the request.

We need a different mechanism: a synchronous-in-lifecycle, two-phase augmentation protocol whose outputs are recorded before request preparation.

## Intent

Introduce a new request-augmentation contract, not a new event-bus notification.

The contract lets privileged extensions register as turn augmenters. A turn augmenter receives a bounded core-owned turn projection, may perform internal work, including launching augmentation child sessions through the core session lifecycle, and returns data only. Core validates, records, orders, diagnoses, and applies the returned augmentation operations.

Replay uses the recorded augmentation operations and diagnostics and never reruns augmenters, child sessions, file reads, model calls, or other live augmentation work.

## Scope

This task covers the architecture, runtime protocol, and initial implementation for pre-turn augmentation.

In scope:

- Add a core-owned pre-turn augmentation lifecycle before request preparation.
- Add an extension registration mechanism for turn augmenters, distinct from `(:on api)` notification subscriptions.
- Require turn-augmentation registration and execution to be capability- and permission-gated.
- Invoke registered turn augmenters during the pre-turn phase.
- Provide augmenters with a bounded core-owned projection/resolver payload, not raw `ctx`, direct atom access, or hidden runtime handles.
- Allow augmentation providers to return typed augmentation operations as data.
- Record accepted augmentation operations and diagnostics as canonical turn-scoped session state before request preparation.
- Apply recorded augmentation operations when building the prepared request.
- Make augmentation turn-scoped and tied to a specific `turn-id`.
- Ensure replay uses recorded augmentation operations instead of rerunning augmentation providers.
- Support augmentation providers that launch independent/child sessions to compute context.
- Route augmentation child-session creation through the core/session lifecycle with parent session/turn provenance.
- Suppress pre-turn augmentation inside augmentation child sessions by default to avoid recursion.
- Ensure cancellation/stale-result handling prevents late augmentation results from applying to the wrong turn.
- Update the `extensions/context-manager` scaffold to use the new mechanism for a minimal augmentation behavior.
- Add tests proving lifecycle ordering, registration gating, recording/replay behavior, deterministic ordering, recursion suppression, cancellation/stale-result guarding, diagnostics, and request inclusion.

## Out of scope

- Explicit augmentation deadlines or timeouts.
- Explicit augmentation token budgets.
- Global trimming or prioritization of augmentation blocks beyond deterministic ordering.
- Arbitrary prepared-request mutation by extensions.
- Allowing extensions to change model, API key, tools, command list, system prompt, developer prompt, runtime metadata, or user message.
- Interactive user prompts during pre-turn augmentation.
- Making child augmentation sessions ordinary unrestricted parent-turn participants.
- UI polish beyond inspectable diagnostics needed to verify behavior.
- General post-turn context compaction policy beyond what is needed for this pre-turn hook.

## Required semantics

### Two-phase prepare

The parent turn follows this shape:

1. user submits a prompt;
2. `:session/prompt-submit` creates or returns the canonical `turn-id` for that submitted prompt and appends the prompt/repair journal entries;
3. core enters `:session/pre-turn-augment` with the same `session-id`, `turn-id`, submitted user message, and workflow run id if any;
4. if the session is an augmentation child session with `:suppress-turn-augmentation? true`, core records a suppressed/no-op augmentation record and skips augmenter invocation;
5. otherwise, runtime invokes currently registered and authorized turn augmenters;
6. augmenters return augmentation data or failures;
7. core validates, deterministically orders, and records accepted operations plus provider diagnostics for the `turn-id`;
8. `:session/prompt-prepare-request` receives the same `turn-id` and builds the prepared request from the original prompt plus recorded accepted augmentation operations;
9. augmentation state remains addressable by `turn-id` for replay/diagnostics but is never read for later turns;
10. normal prompt execution and post-turn notification lifecycle continues.

`prompt-prepare-request` must not run live augmentation itself. It only reads the canonical augmentation record for its `turn-id`.

### Capability and permission gating

Turn augmentation is a privileged extension capability.

- Core adds a named capability, `:psi.capability/turn-augmentation`, to the capability catalog.
- An extension may register a turn augmenter only when both are true:
  - the extension manifest/effective permissions declare `:psi.capability/turn-augmentation`;
  - the current session's available capabilities include `:psi.capability/turn-augmentation` for that extension.
- Invocation is gated again at pre-turn execution time; stale registrations whose extension is no longer authorized are skipped and diagnosed as `:unauthorized`.
- Registration is exposed by a new API function such as `(:register-turn-augmenter api)`, not by `(:on api)` and not by generic event-bus subscription.
- Permission failure must not leave a callable augmenter registration.

### Registration API

The extension API exposes turn augmentation through exactly one registration function:

```clojure
((:register-turn-augmenter api)
 {:augmenter-id "project-context"
  :handler (fn [turn-projection]
             {:turn-augmentation/status :no-op
              :turn-augmentation/operations []
              :turn-augmentation/child-session-ids []})})
```

Registration contract:

- `:register-turn-augmenter` is distinct from `:on` notification subscription and from generic `:events` bus registration.
- The argument is a map with required keys:
  - `:augmenter-id` — non-blank stable string unique within the registering extension;
  - `:handler` — function of one argument, the bounded turn projection described below.
- Optional registration keys are metadata only: `:description` and `:version`. They do not affect ordering or authorization.
- `extension-id` is derived by core from the registering extension identity; callers must not supply or spoof it.
- A successful call returns `{:extension-id <extension-id> :augmenter-id <augmenter-id> :registered? true}`.
- Invalid registration arguments throw `ex-info` with `:reason :invalid-registration` and leave no registration.
- Missing manifest/effective permission or missing session-available capability throws `ex-info` with `:reason :unauthorized` and leaves no callable registration.
- Handler exceptions during pre-turn invocation do not escape the parent turn lifecycle; core records that provider with status `:failed` and accepts no operations from it.

Registrations are extension-scoped entries in the runtime registration table. They are not session-scoped. Per-session authorization is checked again at invocation time, so a registered augmenter can be skipped as `:unauthorized` for a session where its capability is unavailable.

### Registration and deterministic ordering

Each registered augmenter has a stable key:

```clojure
{:extension-id "context-manager"
 :augmenter-id  "project-context"}
```

Registration with the same stable key replaces the previous registration for that key. Invocation and result application order are deterministic:

1. sort registered authorized augmenters by `extension-id`, then `augmenter-id`;
2. keep operations in provider-returned order within one augmenter result;
3. apply accepted operations in the sorted provider order.

The implementation may execute augmenters sequentially in that order or execute them concurrently and sort the collected results before validation/recording. In both cases, the same state and same turn event must produce the same recorded operation order.

### Augmenter input contract

An augmenter receives exactly one bounded map, not raw runtime context. The v1 input shape is:

```clojure
{:turn-augmentation/turn-id "..."
 :turn-augmentation/session-id "..."
 :turn-augmentation/user-message {:role "user" :content [...]}
 :turn-augmentation/user-text "original submitted user text, when available"
 :turn-augmentation/effective-cwd "/worktree/path-or-nil"
 :turn-augmentation/session {:session-id "..."
                             :session-name "..."
                             :worktree-path "..."
                             :model {:provider :openai :id "..."}
                             :prompt-mode :lambda
                             :response-mode :default
                             :context-tokens 123
                             :context-window 200000}
 :turn-augmentation/history {:message-count 42
                             :tail [{:index 40
                                     :role "assistant"
                                     :content-types [:text]
                                     :snippet "bounded snippet"}]}
 :turn-augmentation/provider {:extension-id "context-manager"
                              :augmenter-id "project-context"}}
```

Rules for the projection:

- `:turn-augmentation/user-message` is the original submitted user message for this turn before template/skill expansion.
- `:turn-augmentation/user-text` is the first text block from that original user message, or `nil`.
- `:turn-augmentation/effective-cwd` is the session worktree path after normal session cwd resolution, or `nil`.
- `:turn-augmentation/session` contains only stable scalar/session-summary fields needed for context choice; it must not contain atom handles, dispatch functions, credentials, tool implementations, or provider API keys.
- `:turn-augmentation/history` is a bounded core projection equivalent to existing context-message summaries: message count plus tail summaries with snippets capped by core. It is not raw journal access.
- Extensions needing more information must request a future resolver-backed field; they must not receive `ctx`, root state, or mutable session data.

### Critical path without deadline

Pre-turn augmentation is on the critical path. The parent turn waits for augmentation to return, fail, or be canceled.

No explicit deadline is required in this task. Because there is no deadline, cancellation and stale-result guarding are required safety properties.

### Data-only extension output

Turn augmenters must not mutate parent session state directly. They must not dispatch arbitrary parent mutations. They must return data to core.

Core owns:

- validation;
- deterministic ordering;
- recording;
- application to request building;
- replay semantics;
- cancellation/stale-result checks;
- final authority over supported operation types.

### Augmenter result envelope

An augmenter handler must return one data envelope. Core must not infer provider outcome from arbitrary return shapes.

Valid v1 envelopes are exactly:

```clojure
{:turn-augmentation/status :success
 :turn-augmentation/operations [<operation> ...]
 :turn-augmentation/child-session-ids ["child-session-id" ...]
 :turn-augmentation/diagnostic "optional terse provider diagnostic"}

{:turn-augmentation/status :no-op
 :turn-augmentation/operations []
 :turn-augmentation/child-session-ids []
 :turn-augmentation/diagnostic "optional terse provider diagnostic"}
```

Envelope rules:

- `:turn-augmentation/status` is required and must be `:success` or `:no-op` when returned by a handler.
- `:turn-augmentation/operations` is required and must be a vector.
- `:success` requires at least one operation; `:no-op` requires an empty operations vector.
- `:turn-augmentation/child-session-ids` is optional; when present it must be a vector of session-id strings created through the augmentation child-session API for this same parent session and turn. Missing is treated as `[]`.
- `:turn-augmentation/diagnostic` is optional; when present it must be a string and is copied into the provider diagnostic summary.
- Malformed envelopes are treated as invalid provider results: core records provider status `:invalid-operation`, accepts no operations from that provider, and continues with other providers.
- Unsupported operation `:op` values are treated as provider status `:unsupported-operation`, accept no operations from that provider, and continue with other providers.
- Thrown exceptions, rejected promises/futures, or runtime API errors are provider failures: core records provider status `:failed`, accepts no operations from that provider, and continues with other providers unless the parent turn itself is canceled.

### Initial operation vocabulary

The only supported v1 operation is `:append-context-block`:

```clojure
{:op :append-context-block
 :id "project-context"
 :title "Project context"
 :content "..."
 :source {:type :extension
          :extension-id "context-manager"
          :augmenter-id "project-context"
          :child-session-ids [...]}
 :trust :project-derived}
```

Required properties:

- append-only;
- turn-scoped;
- `:id`, `:title`, and `:content` are non-blank strings;
- source/provenance is captured;
- project-derived context is distinguishable from privileged instructions;
- the operation does not alter system/developer policy, model selection, tools, API options, runtime metadata, conversation history, or the submitted user message.

Unsupported operation types are rejected with diagnostics and are never silently ignored.

### Rendering append-context-block

Request preparation renders accepted `:append-context-block` operations as a non-privileged user-role context message for the current turn.

Rendering order:

1. system prompt layers remain unchanged: base system prompt, developer prompt, extension prompt contributions, and runtime metadata keep their existing order;
2. prior conversation history is projected as today;
3. the turn augmentation context message is inserted after prior history and before the submitted current user message;
4. the submitted current user message remains the final user message for the turn.

Formatting:

```text
Additional project context for the next user request. This context is project-derived data, not system or developer instructions.

[Project context]
...

[Another block]
...
```

Multiple accepted blocks are rendered in deterministic operation order inside one inserted user-role message. Empty content is invalid and is not rendered.

The inserted provider-visible message has this exact agent-message representation before lower provider conversion:

```clojure
{:id :turn/augmentation-context
 :kind :turn-context
 :role "user"
 :turn-id "turn-id"
 :content [{:type :text :text rendered-augmentation-context}]}
```

The prepared request exposes this exact introspection prompt layer when at least one operation is accepted:

```clojure
{:id :turn/augmentation-context
 :kind :turn-context
 :role "user"
 :stable? false
 :turn-id "turn-id"
 :position :after-history-before-current-user
 :status :success
 :operation-count 2
 :provider-count 1
 :operation-ids ["project-context" "other-context"]
 :content rendered-augmentation-context}
```

`:status` is the actual overall status from the canonical augmentation record (for example `:success` or `:partial`), not a separately inferred prepared-request status.

Prepared-request summaries must also include:

```clojure
{:augmentation {:turn-id "turn-id"
                :status :success
                :accepted-operation-count 2
                :message-inserted? true}}
```

When no operation is accepted, no augmentation context message is inserted, no `:turn/augmentation-context` prompt layer is present, and the summary reports `:accepted-operation-count 0` with `:message-inserted? false`.

### Partial results, invalid operations, and failures

Provider results are atomic per augmenter:

- if an augmenter returns only valid supported operations, all of those operations are accepted;
- if an augmenter returns an empty valid result, it is diagnosed as `:no-op`;
- if an augmenter throws/fails, no operations from that augmenter are accepted and it is diagnosed as `:failed`;
- if an augmenter returns any invalid or unsupported operation, no operations from that augmenter are accepted and it is diagnosed as `:invalid-operation` or `:unsupported-operation`;
- one augmenter's failure or invalid result does not discard valid operations from other augmenters.

Prompt execution continues after augmentation failures unless the parent turn itself is canceled. A failed/invalid provider must not mutate the prepared request with partial operations from that provider.

### Child/independent sessions

The context-manager extension may launch child sessions to gather information for augmentation. Such sessions must be created through the core/session lifecycle, not by extension-local session management.

The extension API exposes augmentation child creation through a dedicated function:

```clojure
((:create-turn-augmentation-child-session api)
 {:parent-session-id (:turn-augmentation/session-id turn-projection)
  :parent-turn-id    (:turn-augmentation/turn-id turn-projection)
  :session-name      "context-manager augmentation"
  :system-prompt     "optional child prompt"
  :tool-ids          ["read" "bash"]})
;; => {:psi.agent-session/session-id "child-session-id"}
```

API rules:

- The function is available only to a registered, currently invoked turn augmenter with `:psi.capability/turn-augmentation`.
- `:parent-session-id` and `:parent-turn-id` are required and must match the active augmentation invocation. Mismatches throw `ex-info` with `:reason :invalid-parent-provenance` and create no child session.
- The function dispatches through the canonical `:session/create-child` lifecycle. It must not allocate extension-local sessions.
- Core supplies or enforces these child-session fields regardless of caller options:

```clojure
{:parent-session-id "parent-session"
 :parent-turn-id "turn-id"
 :purpose :turn-augmentation
 :suppress-turn-augmentation? true}
```

- Caller options may narrow the child prompt/tool/model shape, but must not remove parent provenance, enable parent-state mutation, or disable recursion suppression.
- The returned child session id is data; if the provider wants it represented in augmentation diagnostics, it must include that id in `:turn-augmentation/child-session-ids` in its result envelope.

Augmentation child sessions must be distinguishable from ordinary user sessions. They carry parent provenance:

- parent session id;
- parent turn id;
- purpose `:turn-augmentation`;
- marker `:suppress-turn-augmentation? true` by default.

Child augmentation sessions must not recursively trigger pre-turn augmentation unless a future explicit opt-in mechanism is designed. Canceling the parent turn prevents child results from being applied to that parent turn.

### Cancellation and stale results

Core applies an augmentation result only when all are true at record time:

- result `session-id` matches the parent session;
- result `turn-id` matches the current turn being augmented;
- the parent turn has not been canceled;
- the pre-turn augmentation phase for that `turn-id` is still accepting results.

If a result arrives for a known but canceled/stale turn, core records a diagnostic with status `:canceled` or `:stale` and accepts no operations from that result. If the session/turn record no longer exists, the result is ignored and only bounded runtime logging is allowed; no new turn state is recreated for the late result.

### Canonical storage and diagnostics

Accepted operations and diagnostics live in canonical turn-scoped session state or journal-equivalent session state, not only in runtime locals, extension diagnostics, or the bounded dispatch log.

The v1 augmentation record shape is:

```clojure
{:turn-id "..."
 :session-id "..."
 :status :success
 :replay? false
 :operations [<accepted-operation>]
 :providers [{:extension-id "context-manager"
              :augmenter-id "project-context"
              :status :success
              :operation-count 1
              :accepted-operation-count 1
              :rejected-operation-count 0
              :child-session-ids ["..."]
              :diagnostic "optional terse message"}]}
```

Minimum status taxonomy:

- overall statuses: `:success`, `:no-op`, `:partial`, `:failed`, `:canceled`, `:stale`, `:suppressed`, `:replay-used`, `:replay-missing`, `:replay-invalid`;
- provider statuses: `:success`, `:no-op`, `:failed`, `:invalid-operation`, `:unsupported-operation`, `:unauthorized`, `:canceled`, `:stale`.

A resolver/query surface must expose the latest or addressed turn augmentation summary, including `session-id`, `turn-id`, overall status, accepted operation count, provider statuses, child-session ids, and replay status. The prepared-request summary should include the augmentation status and accepted operation count for the prepared turn.

### Replay

Replay mode is detected from the dispatch/replay context that suppresses effects, or an explicit replay flag on the lifecycle event if the replay path already carries one. Replay must not rely on timing, extension registry state, or live runtime availability.

During replay for a turn:

- pre-turn augmentation looks up the recorded augmentation record by `session-id` and `turn-id`;
- augmenters, child sessions, file reads, model calls, and other live work are not invoked;
- if the record exists, is well-formed, and matches the requested `turn-id`, request preparation uses its accepted operations and records/returns replay status `:replay-used`;
- if the record is missing, malformed, or for a different `turn-id`, replay fails closed with `:replay-missing` or `:replay-invalid`; core must not rerun augmenters or prepare a silently different replay request.

Child session ids and diagnostics are provenance, not replay dependencies.

## Acceptance criteria

- A new turn-augmentation registration path exists for extensions and is distinct from event notification subscription.
- Registration and invocation are gated by `:psi.capability/turn-augmentation` manifest/effective permissions and session-available capabilities.
- Unauthorized registration does not leave a callable augmenter; unauthorized stale registrations are skipped with diagnostics.
- Augmenters receive only the bounded v1 input contract fields, not raw `ctx`, direct atom access, credentials, or dispatch/runtime handles.
- The prompt lifecycle includes a pre-turn augmentation phase after `:session/prompt-submit` assigns `turn-id` and before prepared-request construction.
- A registered augmenter can return an `:append-context-block` operation that appears in the prepared request for that turn as non-privileged user-role context before the current user message.
- Multiple augmenters and multiple operations are recorded/applied in deterministic order.
- The exact operations applied to the request and diagnostics for rejected/failed/no-op providers are recorded before request execution.
- Replaying the relevant lifecycle uses the recorded operation and does not invoke the augmenter again.
- Replay with missing, malformed, or wrong-turn augmentation records fails closed and does not rerun live augmentation work.
- Augmentation output is scoped to one `turn-id` and does not leak into a later turn.
- Augmentation child sessions are created through the core/session lifecycle and marked with parent session/turn provenance.
- Pre-turn augmentation is suppressed by default inside augmentation child sessions.
- Parent cancellation or turn mismatch prevents late augmentation results from being applied and records/omits diagnostics according to the cancellation/stale rules above.
- Extension failures are captured as diagnostics and do not mutate the prepared request with partial/invalid data from the failed provider.
- Unsupported operations from an extension are rejected with diagnostics.
- A resolver or summary surface exposes augmentation status, accepted operation count, provider statuses, child-session ids, and replay status.
- The context-manager extension demonstrates the new mechanism with a minimal safe context block.
- Tests cover normal augmentation, no-op augmentation, extension failure, invalid operation, unsupported operation, multiple-augmenter ordering, replay success, replay missing/invalid fail-closed behavior, recursion suppression, diagnostics, and stale/canceled turn handling.

## Design constraints

- Preserve dispatch ownership of state changes and side effects.
- Preserve effects-as-data at impure boundaries.
- Preserve deterministic replay by recording augmentation data before it influences a request.
- Keep request construction core-owned.
- Keep extension authority narrow: extensions propose augmentation data; core decides whether and how to apply it.
- Prefer a small closed operation vocabulary over prepared-request diffs.
- Store augmentation operations and diagnostics as canonical turn-scoped session data before pure request preparation consumes them.
