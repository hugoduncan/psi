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

The contract lets privileged extensions register as turn augmenters. A turn augmenter receives a bounded core-owned turn projection, may perform internal work, including launching helper/child sessions through the existing extension session APIs, and returns data only. Core validates, records, orders, diagnoses, and applies the returned augmentation operations.

Replay uses the recorded augmentation operations and diagnostics and never reruns augmenters, helper sessions, file reads, model calls, or other live augmentation work.

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
- Support augmentation providers that launch independent/helper sessions with the existing extension session APIs to compute context.
- Do not add a dedicated v1 child-session creation or child-run API for turn augmentation.
- Require extensions that create helper sessions to avoid recursive augmentation for those helper sessions, for example by tracking their helper session ids and returning `:no-op` when invoked for them.
- Ensure cancellation/stale-result handling prevents late augmentation results from applying to the wrong turn.
- Update the `extensions/context-manager` scaffold to use the new mechanism for a minimal augmentation behavior.
- Add tests proving lifecycle ordering, registration gating, recording/replay behavior, deterministic ordering, helper-session no-op recursion avoidance, cancellation/stale-result guarding, diagnostics, and request inclusion.

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
4. runtime considers currently registered turn augmenters, diagnoses unauthorized ones for this parent session, and invokes only authorized turn augmenters;
5. augmenters return augmentation data or failures;
6. core validates, deterministically orders, and records accepted operations plus provider diagnostics for the `turn-id`;
7. `:session/prompt-prepare-request` receives the same `turn-id` and builds the prepared request from the original prompt plus recorded accepted augmentation operations;
8. augmentation state remains addressable by `turn-id` for replay/diagnostics but is never read for later turns;
9. normal prompt execution and post-turn notification lifecycle continues.

`prompt-prepare-request` must not run live augmentation itself. It only reads the canonical augmentation record for its `turn-id`.

### Statechart-visible lifecycle barrier

Pre-turn augmentation is an explicit prompt-lifecycle statechart phase, not only a request-preparation precondition. The statechart owns the critical-path barrier and rejects invalid prepare-before-augmentation transitions before request construction is attempted.

The v1 prompt turn topology is:

1. `:session/prompt-submit` creates or reuses the canonical `turn-id`, records the submitted prompt/repair entries, and leaves the turn in state `:turn/submitted`.
2. `:session/pre-turn-augment` is valid only from `:turn/submitted`; it opens the canonical augmentation phase, records state `:turn/augmentation-open`, selects providers, and emits provider-invocation effects plus a close-phase dispatch when needed. Even no-provider, replay, and already-canceled cases create the open phase first; they are closed by `:session/close-pre-turn-augmentation` rather than by an immediate terminal write in `:session/pre-turn-augment`.
3. Provider result events and cancellation events are valid while the turn is `:turn/augmentation-open`; they update the open canonical phase through dispatch-owned state changes.
4. `:session/close-pre-turn-augmentation` is the single terminal writer for v1. It is valid from `:turn/augmentation-open` when every selected provider has a terminal outcome, when no providers were selected, when parent cancellation closes the turn, or when replay uses/fails a recorded close payload. It writes the terminal canonical augmentation record and transitions the turn to `:turn/augmentation-closed` for non-canceled live/replay-used outcomes or to the existing canceled/failure lifecycle path for canceled/replay-failed outcomes.
5. The statechart action on the non-canceled `:turn/augmentation-closed` transition is the only automatic scheduling path for `:session/prompt-prepare-request`.
6. `:session/prompt-prepare-request` is valid only from `:turn/augmentation-closed` for the same `session-id`/`turn-id`; direct prepare attempts from `:turn/submitted`, `:turn/augmentation-open`, another turn, or no turn state are rejected as `ex-info` with reason `:invalid-prompt-lifecycle-transition` and must not build a provider-visible request.

The request-preparation record checks below remain as a fail-closed backstop for malformed or missing canonical augmentation records, but they are not the primary lifecycle gate.

### Dispatch/effect boundary

Live turn-augmenter invocation is impure runtime work and must not run inside a pure dispatch handler. Pure handlers may select providers, create/open a canonical augmentation phase, and emit effects-as-data, but arbitrary extension handlers, helper-session runs, file/model access, futures, and late-result callbacks run only at the dispatch effect/runtime boundary.

The live path is:

1. dispatch handles `:session/pre-turn-augment` for the parent `session-id`/`turn-id`, records an open turn-scoped augmentation phase, selects currently registered providers, diagnoses providers that are not authorized for the parent session, and returns one or more invocation effects for authorized providers;
2. the effect executor invokes selected extension handlers with the bounded turn projection and no raw dispatch context;
3. provider returns, exceptions, cancellations, unload/disable checks, and late results are fed back through dispatch-owned state updates, for example a provider-result or close-phase event/mutation;
4. dispatch validation records the terminal canonical augmentation record before request preparation is allowed to continue.

Replay suppresses the invocation effect and uses the replay lookup rules instead. No pure handler may call an augmenter handler, run a helper session, dereference provider futures, read files for augmentation, or mutate canonical augmentation records outside the dispatch update path. Late-result handling must preserve the same boundary: background callbacks may enqueue/dispatch data, but canonical state changes still go through dispatch.

### Capability and permission gating

Turn augmentation is a privileged extension capability.

- Core adds exactly one named capability for this mechanism, `:psi.capability/turn-augmentation`, to the capability catalog.
- An extension may register a turn augmenter only when its manifest/effective permissions declare `:psi.capability/turn-augmentation`.
- Registration-time authorization is not tied to an ambient active/default session and does not inspect session-available capabilities. Extension-load registration is therefore manifest/effective-permission gated only.
- Invocation is gated at pre-turn execution time for the parent session being augmented: the session's available extension-scoped capabilities must include `:psi.capability/turn-augmentation` for the registering extension id. Stale registrations whose extension is no longer authorized for that session are skipped and diagnosed as `:unauthorized`.
- Registration is exposed by a new API function such as `(:register-turn-augmenter api)`, not by `(:on api)` and not by generic event-bus subscription.
- Permission failure must not leave a callable augmenter registration.

Concrete permission schema:

- Manifest/effective extension permissions use a vector or set of capability keywords under `:permissions`, for example `{psi/context-manager {:permissions [:psi.capability/turn-augmentation]}}` in the extension install manifest/effective projection.
- The effective permission set is normalized to a set of keywords on the live extension record, for example `:effective-permissions #{:psi.capability/turn-augmentation}`.
- Unknown capability keywords fail closed during extension effective-permission construction. The activation/effective-state update fails with diagnostic reason `:unknown-capability`, the extension is not live/enabled for that activation, its initialization is not allowed to register a turn augmenter, and existing sessions receive no available capability entry for that failed activation. Unknown capability keywords are never silently dropped or granted.
- A session's available extension capabilities are extension-scoped, not one global session flag. The session state/projection uses a map equivalent to `{:extensions {<extension-id> #{:psi.capability/turn-augmentation}}}`.
- Session creation initializes that map from the currently live/enabled extensions' effective permissions under the v1 default policy: allow declared live extension capabilities. V1 has no user-facing or persisted session-policy narrowing/update path for turn augmentation. The state shape may reserve room for a future narrower policy, but this task only implements default allow-declared-live and must not invent UI/API controls for granting or narrowing capabilities.
- When an extension is loaded, enabled, or successfully reloaded after a session exists, core recomputes that extension's entry for every existing session from the new effective permissions under the default allow-declared-live policy. When an extension is unloaded, disabled, or fails activation/reload, core removes that extension id from every session's available extension capabilities. There is no v1 session-policy-change event to implement.
- Absence of the extension id or capability in the parent session's available capability map means unavailable for that session.
- Invocation is allowed only when both checks pass: the live extension effective permissions contain `:psi.capability/turn-augmentation` and the parent session's available extension capabilities contain the same capability for that exact extension id.

### Extension identity

Core derives one canonical `extension-id` for every live extension and uses it for turn-augmenter stable keys, deterministic sorting, provenance, diagnostics, authorization lookup, and unload/disable/reload cleanup. Extension code cannot supply or override it.

Canonical identity rules:

- Manifest-installed extensions use the manifest activation id `manifest:<lib>`, where `<lib>` is the dependency lib symbol string, for example `manifest:psi/context-manager`.
- Path-loaded extensions use `path:<canonical-absolute-path>` after filesystem canonicalization. They do not use basename, namespace, package name, or user-supplied display labels for authority.
- The normalized id must be a non-blank string and is stored on the live extension record separately from display/path metadata.
- Loading a second live extension with the same normalized id is a duplicate-extension-id activation error; the later activation is rolled back and leaves no callable registrations.
- Registration replacement, unload, disable, and reload match registrations by this exact canonical `extension-id`. Manifest and path activations of the same code are distinct extensions unless they normalize to the same id.

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
- Invalid registration arguments throw `ex-info` with `:reason :invalid-registration`. Replacement is validate-before-swap: if a callable registration for the same stable key already exists and the replacement arguments are invalid, the previous registration remains unchanged.
- Missing manifest/effective permission throws `ex-info` with `:reason :unauthorized`. Authorization failure removes any existing registration for that same stable key before throwing, so an unauthorized extension cannot retain a callable turn augmenter through a failed replacement attempt.
- Handler exceptions during pre-turn invocation do not escape the parent turn lifecycle; core records that provider with status `:failed` and accepts no operations from it.

Registrations are extension-scoped entries in the runtime registration table. They are not session-scoped, and they do not capture whichever session happened to be active when the extension loaded. Per-session authorization is checked at invocation time, so a registered augmenter can be skipped as `:unauthorized` for a session where its capability is unavailable.

### Registration lifecycle

Turn-augmenter registrations follow the extension live-registry lifecycle:

- registration with the same stable key replaces the previous registration for that key only after the new arguments validate and the registering extension is authorized; invalid replacement arguments preserve the previous registration, while authorization failure removes that key as described above;
- extension unload or disable removes every turn-augmenter registration owned by that extension;
- extension reload first removes the extension's existing registrations, then successful initialization may register replacement augmenters;
- failed initial load or failed reload rolls back the extension's live registry entry and leaves no callable turn-augmenter registrations from the failed activation;
- a registration is callable only while its owning extension remains live and enabled.

Removed registrations are absent from later pre-turn provider enumeration and therefore do not create provider diagnostics. If a provider was selected for an already-started pre-turn invocation and the extension is unloaded or disabled before its result is recorded, core treats the provider as no longer authorized for that parent turn, records provider status `:unauthorized`, and accepts no operations from it.

### Registration and deterministic ordering

Each registered augmenter has a stable key:

```clojure
{:extension-id "manifest:psi/context-manager"
 :augmenter-id  "project-context"}
```

Successful registration with the same stable key replaces the previous registration for that key according to the validation and authorization rules above. Invocation and result application order are deterministic:

1. sort registered authorized augmenters by `extension-id`, then `augmenter-id`;
2. keep operations in provider-returned order within one augmenter result;
3. apply accepted operations in the sorted provider order.

The implementation may execute augmenters sequentially in that order or execute them concurrently and sort the collected results before validation/recording. In both cases, the same state and same turn event must produce the same recorded operation order.

Each selected provider snapshot records the stable provider key plus a core-generated activation/registration token from the live registration entry, for example `:activation-id` and `:registration-token`. A provider result is accepted only if the owning extension remains live/enabled, still authorized for the parent session, and the current registration for the stable key has the same token selected for this phase. If the extension was unloaded, disabled, reloaded, or replaced under the same stable key before result recording, the result is diagnosed as `:unauthorized` when the owner is no longer authorized/live, otherwise `:stale` with reason `:late-stale-result`; no operations are accepted.

### Augmenter input contract

An augmenter receives exactly one bounded map, not raw runtime context. The v1 input shape is:

```clojure
{:turn-augmentation/turn-id "..."
 :turn-augmentation/session-id "..."
 :turn-augmentation/workflow-run-id "workflow-run-id-or-nil"
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
 :turn-augmentation/provider {:extension-id "manifest:psi/context-manager"
                              :augmenter-id "project-context"}}
```

Rules for the projection:

- `:turn-augmentation/workflow-run-id` is the workflow run id already threaded by the prompt lifecycle for this turn, or `nil`; it is provenance only and does not affect provider identity, authorization, ordering, replay lookup, or request rendering.
- `:turn-augmentation/user-message` is the original submitted user message for this turn before template/skill expansion.
- `:turn-augmentation/user-text` is the first text block from that original user message, or `nil`.
- `:turn-augmentation/effective-cwd` is the session worktree path after normal session cwd resolution, or `nil`.
- `:turn-augmentation/session` contains only stable scalar/session-summary fields needed for context choice; it must not contain atom handles, dispatch functions, credentials, tool implementations, or provider API keys.
- `:turn-augmentation/history` is a bounded core projection equivalent to existing context-message summaries: message count plus tail summaries with snippets capped by core. It is not raw journal access.
- Extensions needing more information must request a future resolver-backed field; they must not receive `ctx`, root state, or mutable session data.

History projection bounds are fixed for v1 so tests can assert them:

- `:message-count` is the count of canonical prior agent-core messages visible to request preparation before the current turn's prompt-submit additions are considered;
- the projection excludes both the current submitted user message and any prompt-submit repair entries generated for that current turn; the submitted user message is provided separately as `:turn-augmentation/user-message` and `:turn-augmentation/user-text`;
- `:tail` contains at most the last 8 of those prior messages, in chronological order;
- each `:tail` entry uses the zero-based `:index` from the full prior-message vector;
- `:content-types` is the vector of each content block's `:type`;
- `:snippet` is built by a core helper equivalent to `psi.agent-session.message-text/content-display-text`, normalized to single-space whitespace, trimmed, and truncated to at most 200 characters; messages with no displayable text use `""`;
- the projection helper is core-owned, for example `build-turn-augmentation-history-projection`, so augmenter tests do not depend on UI telemetry resolver defaults.

### Critical path without deadline

Pre-turn augmentation is on the critical path. The parent turn waits until the pre-turn augmentation barrier reaches a terminal state. In v1 the terminal states are:

- all selected provider invocations returned, failed, or were rejected and the canonical augmentation record has been written;
- the parent turn was canceled and the canonical augmentation record has been closed with status `:canceled`;
- replay failed closed with `:replay-missing` or `:replay-invalid`.

No explicit deadline or timeout is required in this task. Core must not proceed to request preparation while a live non-canceled pre-turn augmentation phase is still accepting provider results.

Cancellation is the only required unblocking mechanism for in-flight providers under the no-deadline v1 contract. On parent-turn cancellation, core closes the augmentation phase for that `turn-id`, records pending selected providers that have not returned as provider status `:canceled`, records overall status `:canceled`, accepts no further operations for the turn, and resumes the parent lifecycle through the normal canceled-turn path without preparing or executing the request. Implementations may interrupt/cancel provider futures or child-session work best-effort, but correctness must not depend on interruption; late provider results are handled by the stale/canceled-result rules below.

### Data-only extension output

Turn augmenters must return augmentation data to core and must not mutate the parent request or parent session state directly. They must not append parent messages, inject parent system/developer content, alter the parent model/tools/options, rewrite the submitted user message, write parent augmentation records, or dispatch arbitrary parent-session mutations that affect request preparation.

Allowed helper-session side effects are narrower and distinct from parent mutation: an augmenter may use existing extension session APIs to create and run helper sessions, then return the final helper-derived content as data in its result envelope. The parent `session-id` may be used as provenance or as the parent reference for helper allocation, but the helper session is the mutation target for helper prompts/results. Any effect on the parent turn comes only from the validated operations that core records and later renders.

Core owns:

- validation;
- deterministic ordering;
- recording;
- application to request building;
- replay semantics;
- cancellation/stale-result checks;
- final authority over supported operation types.

### Augmenter result envelope

An augmenter handler must return one data envelope. Core must not infer provider outcome from arbitrary return shapes. A handler may return the envelope map directly or return an `IDeref` value such as a future, promise, or delay that yields the envelope. The effect executor waits by dereferencing returned `IDeref` values with no explicit deadline in v1. Exceptions thrown by the handler or raised while dereferencing are provider failures with status `:failed` and reason `:handler-exception`; canceled futures/promises that throw on deref are handled the same way unless parent-turn cancellation has already assigned provider status `:canceled`.

Valid v1 envelopes have required status and operation keys plus optional provenance/diagnostic keys. These are canonical full examples:

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

The `:turn-augmentation/child-session-ids` key is optional in returned envelopes; the examples include it to show the normalized full shape.

Envelope rules:

- `:turn-augmentation/status` is required and must be `:success` or `:no-op` when returned by a handler.
- `:turn-augmentation/operations` is required and must be a vector.
- `:success` requires at least one operation; `:no-op` requires an empty operations vector.
- `:turn-augmentation/child-session-ids` is optional provenance supplied by the provider. When present it must be a vector of non-blank session-id strings. Missing is treated as `[]`. Malformed ids make the provider result invalid with provider status `:invalid-operation`, diagnostic reason `:invalid-child-session-ids`, and no accepted operations from that provider. In v1 core does not require these ids to have been created through a special augmentation API, because no such API exists; providers that use existing session APIs are responsible for reporting truthful provenance.
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
          :extension-id "manifest:psi/context-manager"
          :augmenter-id "project-context"
          :child-session-ids [...]}}
```

Required properties:

- append-only;
- turn-scoped;
- `:id`, `:title`, and `:content` are non-blank strings;
- source/provenance is captured;
- the operation appends extension-returned content for the current turn;
- the operation does not alter system/developer policy, model selection, tools, API options, runtime metadata, conversation history, or the submitted user message.

Unsupported operation types are rejected with diagnostics and are never silently ignored.

V1 has no trust model for augmentation operations. Handlers do not supply `:trust`, core does not inject `:trust`, and request rendering does not branch on trust metadata. Accepted extension-returned content is injected as turn augmentation context using the rendering below.

Core owns provenance normalization for accepted operations:

- accepted operations are recorded with `:source` injected from the registered provider key and the verified `:turn-augmentation/child-session-ids` for that provider;
- handlers may omit `:source`; omission is normal and does not affect acceptance;
- if a handler supplies `:source`, it must exactly match the core-normalized source for that provider and verified child-session id set;
- a mismatched or spoofed `:source` rejects the provider result atomically with provider status `:invalid-operation`, diagnostic reason `:provenance-mismatch`, and no accepted operations from that provider.

Duplicate `:append-context-block` `:id` values are allowed across providers and within one turn. The id is a provider-local label, not a global unique key. Core does not rewrite ids; deterministic provider/order position disambiguates them. Prepared-request summaries expose `:operation-ids` in accepted operation order and may therefore contain repeated strings.

### Prepare-request precondition

In addition to the statechart gate, for a live, non-replay turn, `:session/prompt-prepare-request` has a hard precondition: exactly one canonical augmentation record for the same `session-id` and `turn-id` must already exist and must not still be accepting provider results.

If no canonical augmentation record exists, the record is malformed, the record belongs to a different turn, or the record is still open, request preparation fails closed with `ex-info` reason `:missing-turn-augmentation-record`, `:invalid-turn-augmentation-record`, or `:turn-augmentation-still-open`. It must not synthesize a no-op record, rerun augmentation, or prepare a request that silently omits the pre-turn phase. The failed prepare attempt may be surfaced through lifecycle diagnostics, but it must not append a provider-visible request. Replay uses the replay rules below instead of the live precondition.

### Rendering append-context-block

Request preparation renders accepted `:append-context-block` operations as an injected user-role turn-context message for the current turn.

Rendering order:

1. system prompt layers remain unchanged: base system prompt, developer prompt, extension prompt contributions, and runtime metadata keep their existing order;
2. prior conversation history before the current prompt-submit additions is projected as today;
3. prompt-submit repair entries for the current turn, if any, are rendered after prior history and before augmentation context so structural tool-result repairs still precede the new user turn;
4. the turn augmentation context message is inserted after prior history/current-turn repair entries and before the submitted current user message;
5. the submitted current user message remains the final user message for the turn.

Formatting:

```text
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
 :position :after-history-and-repairs-before-current-user
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
                :workflow-run-id "workflow-run-id-or-nil"
                :status :success
                :accepted-operation-count 2
                :message-inserted? true}}
```

When no operation is accepted, no augmentation context message is inserted, no `:turn/augmentation-context` prompt layer is present, and the summary reports `:accepted-operation-count 0` with `:message-inserted? false`.

For helper sessions where the context-manager chooses to avoid recursion by returning `:no-op`, request preparation uses the ordinary canonical no-op augmentation record. No `:turn/augmentation-context` prompt layer is present for such a turn.

### Partial results, invalid operations, and failures

Provider results are atomic per augmenter:

- if an augmenter returns only valid supported operations, all of those operations are accepted;
- if an augmenter returns an empty valid result, it is diagnosed as `:no-op`;
- if an augmenter throws/fails, no operations from that augmenter are accepted and it is diagnosed as `:failed`;
- if an augmenter returns any invalid or unsupported operation, no operations from that augmenter are accepted and it is diagnosed as `:invalid-operation` or `:unsupported-operation`;
- one augmenter's failure or invalid result does not discard valid operations from other augmenters.

Prompt execution continues after augmentation failures unless the parent turn itself is canceled. A failed/invalid provider must not mutate the prepared request with partial operations from that provider.

### Child/helper sessions

The context-manager extension may launch helper sessions to gather information for augmentation. V1 does not introduce a dedicated augmentation child-session creation API and does not introduce a dedicated child-run API.

Instead, an augmenter that needs model help may use the same existing extension session facilities used by `extensions/auto-session-name`, such as:

```clojure
((:mutate-session api) parent-session-id
                       'psi.extension/create-child-session
                       {:session-name "context-manager augmentation"
                        :system-prompt "optional helper prompt"
                        :tool-ids []
                        :prompt-component-selection {:agents-md? false
                                                     :extension-prompt-contributions []
                                                     :tool-names []
                                                     :skill-names []
                                                     :components #{}}})

((:mutate-session api) helper-session-id
                       'psi.extension/run-agent-loop-in-session
                       {:prompt "Summarize the project context relevant to the parent request."})
```

The exact helper-session prompt, model choice, tools, cleanup, and caching strategy are extension-owned in v1, subject to whatever authority and validation the existing extension session mutations already enforce. The pre-turn augmentation mechanism only cares about the provider's final returned data envelope.

Because helper sessions created this way are ordinary sessions, recursion avoidance is the extension's responsibility in v1. A context-manager implementation should track helper session ids it creates, like `auto-session-name` tracks `helper-session-ids`, and return a valid `:no-op` augmentation result when its augmenter is invoked for one of those helper sessions. This prevents the extension from recursively launching helper sessions for its own helper turns without requiring a new core child-session API.

If the provider wants helper sessions represented in parent augmentation diagnostics, it includes their session ids in `:turn-augmentation/child-session-ids`. In v1 those ids are provenance only. Core validates their shape but does not treat them as replay dependencies and does not require them to come from a dedicated augmentation allocation path.

Parent-turn cancellation prevents any later provider result from applying to the parent turn through the normal cancellation/stale-result rules. V1 does not require core to cancel or interrupt helper sessions created through the existing generic session APIs; providers should perform best-effort cleanup if they create temporary helper sessions. Correctness comes from refusing to apply late/canceled provider output, not from guaranteed helper-session interruption.

### Minimal context-manager v1 behavior

The `extensions/context-manager` scaffold demonstrates the mechanism with one registered turn augmenter and no helper-session model run in v1. It registers `:augmenter-id "project-context"` through `:register-turn-augmenter` when its effective permissions include `:psi.capability/turn-augmentation`.

Invocation behavior:

- if the current `:turn-augmentation/session-id` is in the extension's internally tracked helper-session id set, return a valid `:no-op` envelope with no operations and no child-session ids;
- if `:turn-augmentation/effective-cwd` is blank or nil, return a valid `:no-op` envelope with diagnostic `"no effective cwd"`;
- otherwise return `:success` with exactly one operation:

```clojure
{:op :append-context-block
 :id "project-context"
 :title "Project context"
 :content "Working directory: <effective-cwd>"}
```

The scaffold omits `:source`; core injects normalized provenance. It returns `:turn-augmentation/child-session-ids []`. It intentionally does not create or run helper sessions in this task; helper-session recursion avoidance is still specified and testable by seeding the helper-session id set or by future context-manager helper work.

### Cancellation and stale results

Core applies an augmentation result only when all are true at record time:

- result `session-id` matches the parent session;
- result `turn-id` matches the current turn being augmented;
- the parent turn has not been canceled;
- the pre-turn augmentation phase for that `turn-id` is still accepting results;
- the owning extension registration is still live and authorized for the parent session.

When parent cancellation is observed before all selected providers have returned, core closes the pre-turn phase immediately: any collected/validated operations not yet committed to a terminal augmentation record for that turn are discarded, providers without accepted results are recorded with status `:canceled`, overall status becomes `:canceled`, and request preparation is not invoked for that parent turn. Sequential implementations must not invoke later providers after cancellation is observed. Concurrent implementations may let already-started provider work finish in the background, but those late results cannot reopen the closed phase.

If a result arrives for a known but canceled turn, core records or updates that provider diagnostic with status `:canceled` and accepts no operations from that result. If a result arrives after the phase closed for any non-canceled reason, core records or updates that provider diagnostic with status `:stale` and accepts no operations from that result. Diagnostic updates for late results must preserve the canonical closed overall status and must not mutate prepared-request input. No v1 transition sets the overall augmentation record status to `:stale`; stale is only a provider diagnostic for late results after a terminal record already exists. If the session/turn record no longer exists, the result is ignored and only bounded runtime logging is allowed; no new turn state is recreated for the late result.

### Canonical storage and diagnostics

Accepted operations and diagnostics live in canonical turn-scoped session state or journal-equivalent session state, not only in runtime locals, extension diagnostics, or the bounded dispatch log.

The v1 augmentation record shape is:

```clojure
{:turn-id "..."
 :session-id "..."
 :workflow-run-id "workflow-run-id-or-nil"
 :status :success
 :replay? false
 :accepted-operation-count 1
 :operations [<accepted-operation>]
 :providers [{:extension-id "manifest:psi/context-manager"
              :augmenter-id "project-context"
              :status :success
              :operation-count 1
              :accepted-operation-count 1
              :rejected-operation-count 0
              :child-session-ids ["..."]
              :reasons []
              :diagnostic "optional terse provider message"}]}
```

The optional `:workflow-run-id` is copied from the prompt lifecycle when present. It is provenance for diagnostics/review and for workflow cancellation guard integration; augmentation record lookup remains keyed by `session-id` and `turn-id`.

Provider diagnostics are machine-readable. Every provider entry contains `:status`; rejected, failed, unauthorized, canceled, or stale providers also contain `:reasons`, a vector of reason keywords in the fixed order below. The optional provider-supplied `:turn-augmentation/diagnostic` string is copied to `:diagnostic` only as human-readable context and is never parsed for behavior.

Reason keywords for v1 are:

- lifecycle/runtime: `:unauthorized`, `:handler-exception`, `:provider-canceled`, `:late-stale-result`;
- envelope validation: `:invalid-envelope`, `:invalid-status`, `:invalid-operations`, `:invalid-child-session-ids`, `:invalid-diagnostic`;
- operation validation: `:invalid-operation-shape`, `:invalid-append-context-block`, `:provenance-mismatch`, `:unsupported-operation`;
- replay validation: `:missing-record`, `:malformed-record`, `:wrong-turn-id`.

When a returned provider result has multiple validation failures, core records all applicable reasons in the fixed order listed above. Status precedence for returned results is deterministic: lifecycle states (`:unauthorized`, `:canceled`, `:stale`) are assigned before result validation; handler exceptions become `:failed`; otherwise any malformed envelope, malformed child-session ids, provenance mismatch, or invalid supported operation shape yields provider status `:invalid-operation`; if the envelope and supported-operation fields are otherwise valid but any operation uses an unsupported `:op`, provider status is `:unsupported-operation`. Invalid-operation therefore wins over unsupported-operation when both are present, while `:reasons` still exposes every detected cause.

Minimum status taxonomy:

- overall statuses: `:success`, `:no-op`, `:partial`, `:failed`, `:canceled`, `:replay-used`, `:replay-missing`, `:replay-invalid`;
- provider statuses: `:success`, `:no-op`, `:failed`, `:invalid-operation`, `:unsupported-operation`, `:unauthorized`, `:canceled`, `:stale`.

Overall status aggregation is deterministic and computed once when the canonical augmentation record first reaches a terminal closed state. Late provider diagnostic updates after closure do not recompute or rewrite the overall status. Aggregation rules after replay/cancellation checks:

- replay outcomes override live aggregation: valid recorded replay uses `:replay-used`; missing or malformed/wrong-turn replay uses `:replay-missing` or `:replay-invalid`;
- parent-turn cancellation records `:canceled`, accepts no operations from pending or late results, and skips request preparation/execution;
- no registered augmenters records `:no-op` with `:providers []` and zero accepted operations;
- registered providers that are unauthorized for the parent session appear in `:providers` with status `:unauthorized` and are not invoked;
- if at least one operation is accepted and every provider status is `:success` or `:no-op`, overall status is `:success`;
- if at least one operation is accepted and any provider status is a rejection/failure status (`:failed`, `:invalid-operation`, `:unsupported-operation`, `:unauthorized`, or `:canceled`), overall status is `:partial`;
- if no operation is accepted and every provider status is `:no-op`, overall status is `:no-op`;
- if no operation is accepted and any provider status is a rejection/failure status, including the all-unauthorized and all-failed/invalid cases, overall status is `:failed`;
- `:stale` is not a terminal overall status in v1. It can only be written as a provider diagnostic for a late result after the canonical record has already closed, and it must preserve the previously closed overall status and accepted operations.

The resolver/query surface exposes both the latest summary for a session and an addressed summary for a specific turn.

Session query attributes:

```clojure
[:psi.agent-session/latest-turn-augmentation-summary
 {(:psi.agent-session/turn-augmentation-summary {:turn-id "turn-id"})
  [:psi.turn-augmentation/session-id
   :psi.turn-augmentation/turn-id
   :psi.turn-augmentation/workflow-run-id
   :psi.turn-augmentation/status
   :psi.turn-augmentation/replay?
   :psi.turn-augmentation/replay-status
   :psi.turn-augmentation/accepted-operation-count
   :psi.turn-augmentation/message-inserted?
   :psi.turn-augmentation/operation-ids
   {:psi.turn-augmentation/providers
    [:psi.turn-augmentation.provider/extension-id
     :psi.turn-augmentation.provider/augmenter-id
     :psi.turn-augmentation.provider/status
     :psi.turn-augmentation.provider/reasons
     :psi.turn-augmentation.provider/operation-count
     :psi.turn-augmentation.provider/accepted-operation-count
     :psi.turn-augmentation.provider/rejected-operation-count
     :psi.turn-augmentation.provider/child-session-ids
     :psi.turn-augmentation.provider/diagnostic]}]}]
```

The latest summary returns the most recent turn-scoped augmentation record for that session or `nil`. The addressed summary returns the record for the supplied `turn-id` or `nil`. `:psi.turn-augmentation/replay-status` is `:replay-used`, `:replay-missing`, `:replay-invalid`, or `nil` for non-replay live records. The prepared-request summary includes the same turn's augmentation status, accepted operation count, and `:message-inserted?` flag.

### Replay

Replay mode is detected from the dispatch/replay context that suppresses effects, or an explicit replay flag on the lifecycle event if the replay path already carries one. Replay must not rely on timing, extension registry state, or live runtime availability.

The replay source for augmentation is the replayed terminal close record, not the live extension registry and not an out-of-band lookup before the event log has recreated state. During normal live execution `:session/close-pre-turn-augmentation` records the terminal augmentation record as part of its event/state payload. During event-log replay, the replayed `:session/pre-turn-augment` recreates the open phase without invocation effects; the replayed `:session/close-pre-turn-augmentation` then validates and writes that same terminal record into reconstructed turn-scoped state. `:session/prompt-prepare-request` reads the reconstructed state after the close event has replayed.

During replay for a turn:

- pre-turn augmentation opens the phase but suppresses live provider invocation effects;
- augmenters, helper sessions, file reads, model calls, and other live work are not invoked;
- close-pre-turn consumes the replayed terminal record payload for the same `session-id` and `turn-id`;
- if the payload is present, well-formed, terminal, and matches the requested `turn-id`, request preparation uses its accepted operations and records/returns replay status `:replay-used`;
- if the close payload is missing, malformed, non-terminal, or for a different `turn-id`, replay fails closed with `:replay-missing` or `:replay-invalid`; core must not rerun augmenters or prepare a silently different replay request.

The shared augmentation-record well-formedness predicate used by live prepare and replay requires: non-blank matching `:session-id` and `:turn-id`; terminal overall status in `#{:success :no-op :partial :failed :replay-used}` for request preparation (`:canceled`, `:replay-missing`, and `:replay-invalid` are valid terminal diagnostics but not request-preparable); boolean `:replay?`; vector `:operations`; `:accepted-operation-count` equal to `(count :operations)`; accepted operations in deterministic order; every operation a supported normalized `:append-context-block` with non-blank `:id`, `:title`, `:content`, and core-normalized extension source; vector `:providers`; provider entries with valid provider key, status, counts consistent with their accepted/rejected outcome, vector `:reasons`, and vector `:child-session-ids`; and no `:accepting?`/open marker. Failed, no-op, and partial records are well-formed for request preparation when terminal and count/order-consistent; they may insert zero operations.

Child session ids and diagnostics are provenance, not replay dependencies.

## Acceptance criteria

- A new turn-augmentation registration path exists for extensions and is distinct from event notification subscription.
- Registration is gated by `:psi.capability/turn-augmentation` manifest/effective permissions; unknown capability keywords fail extension effective-state construction; invocation is gated by the parent session's extension-scoped session-available capabilities.
- Unauthorized registration from missing manifest/effective permission does not leave a callable augmenter; unauthorized stale or session-unavailable registrations are skipped at invocation with diagnostics.
- Augmenters receive only the bounded v1 input contract fields, not raw `ctx`, direct atom access, credentials, or dispatch/runtime handles.
- The prompt lifecycle includes a statechart-visible, statechart-enforced pre-turn augmentation barrier after `:session/prompt-submit` assigns `turn-id` and before prepared-request construction; direct prepare-before-closed-augmentation transitions are rejected.
- A registered augmenter can return an `:append-context-block` operation that appears in the prepared request for that turn as injected user-role turn context before the current user message.
- Multiple augmenters and multiple operations are recorded/applied in deterministic order.
- The exact operations applied to the request and diagnostics for rejected/failed/no-op providers are recorded before request execution.
- Replaying the relevant lifecycle uses the recorded operation and does not invoke the augmenter again.
- Replay with missing, malformed, or wrong-turn augmentation records fails closed and does not rerun live augmentation work.
- Live request preparation fails closed when its canonical augmentation record is missing, malformed, wrong-turn, or still open; it does not synthesize a no-op record or silently omit augmentation.
- Augmentation output is scoped to one `turn-id` and does not leak into a later turn.
- V1 adds no dedicated augmentation child-session creation API and no dedicated augmentation child-run API.
- Augmenters may create and run helper sessions using existing extension session APIs, as `auto-session-name` does.
- The context-manager implementation avoids recursive augmentation for helper sessions it creates, for example by tracking helper session ids and returning `:no-op` for those sessions.
- Parent cancellation unblocks the pre-turn barrier, records overall `:canceled`, records pending selected providers as `:canceled`, skips request preparation/execution for that turn, and prevents late augmentation results from being applied.
- Turn mismatch or stale late results prevent operation application and record/omit diagnostics according to the cancellation/stale rules above.
- Extension failures are captured as diagnostics and do not mutate the prepared request with partial/invalid data from the failed provider.
- Unsupported operations from an extension are rejected with diagnostics.
- Append-context-block operations use no `:trust` field; accepted extension-returned content is injected as turn augmentation context, and request rendering does not branch on trust metadata.
- A resolver or summary surface exposes augmentation status, accepted operation count, provider statuses, provider-supplied child/helper session ids, and replay status.
- Extension unload/disable/reload removes or replaces turn-augmenter registrations according to the extension live-registry lifecycle; failed activation leaves no callable registration.
- Late stale provider results can update provider diagnostics only; they never set overall status `:stale`, rewrite accepted operations, or affect prepared-request input.
- The context-manager extension demonstrates the new mechanism with a minimal safe context block.
- Tests cover normal augmentation, no-op augmentation, extension failure, invalid operation, unsupported operation, multiple-augmenter ordering, replay success, replay missing/invalid fail-closed behavior, helper-session no-op recursion avoidance, diagnostics, and stale/canceled turn handling.

## Design constraints

- Preserve dispatch ownership of state changes and side effects.
- Preserve effects-as-data at impure boundaries.
- Preserve deterministic replay by recording augmentation data before it influences a request.
- Keep request construction core-owned.
- Keep extension authority narrow: extensions propose augmentation data; core decides whether and how to apply it.
- Prefer a small closed operation vocabulary over prepared-request diffs.
- Store augmentation operations and diagnostics as canonical turn-scoped session data before pure request preparation consumes them.
