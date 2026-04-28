Goal: make workflow-driven agent execution resilient to transient LLM transport failures and prevent errored child-session turns from being recorded as successful empty workflow outputs.

Issue provenance:
- observed during live execution of workflow `gh-bug-triage`
- workflow run id: `c196f112-ee62-4c05-b587-43f87da54a9c`
- child execution session id: `e18dc4d8-f6ed-401d-9498-fe2016a47b9a`
- observed error: `Premature end of chunk coded message body: closing chunk expected`

Intent:
- retry transient provider transport failures that should be treated like retryable execution errors
- preserve the existing bounded auto-retry semantics for normal sessions and workflow child sessions
- ensure workflow execution records child-session turn errors as workflow execution failures rather than successful empty text envelopes
- keep retry classification and workflow result propagation explicit, inspectable, and testable

Context:
- session-level auto-retry already exists and is enabled by default
- current retry classification is narrow and message-based:
  - retryable HTTP statuses are limited to `429 500 502 503 529`
  - retryable message patterns focus on rate-limit and overload text
- current statechart retry entry depends on `session/retry-error?`
- observed transport failure text did not match existing retry classification, so no retry was attempted
- workflow execution currently reads the child session's last assistant message and always builds `{:outcome :ok :outputs {:text ...}}`
- the workflow text extractor reads only `:text` content blocks, so an assistant message containing only `{:type :error ...}` becomes empty string output
- this allows a child-session error turn to be converted into a successful empty workflow result envelope, which is incorrect

Problem:
- transient stream/transport failures can terminate a session without using the existing auto-retry path even when retry would be appropriate
- canonical deterministic workflows can incorrectly mark an errored child-session attempt as successful with empty text output
- together, these gaps make workflow execution less reliable and less truthful than the underlying child-session turn result

Minimum concepts:
- retryable execution failure: an execution failure that should use the existing bounded retry path rather than terminate immediately
- transient transport failure: a provider/HTTP streaming boundary failure that indicates interrupted delivery or incomplete streamed response data rather than a semantic/model/request error
- child turn outcome: the canonical outcome of the workflow child session's final turn, including success vs error semantics
- workflow execution failure: the canonical workflow-progression failure path for a step attempt that did not produce a successful result envelope
- successful text envelope: the existing canonical workflow result envelope shape `{:outcome :ok :outputs {:text ...}}` used only for successful assistant turns

Desired outcome:
- retryable LLM transport/stream failures are classified as retryable by the session retry guard when they represent transient provider execution failure
- workflow child sessions benefit from the same retry classification as ordinary sessions when their turn terminates with such errors
- workflow execution inspects the child session turn outcome before shaping the workflow result envelope
- a child-session turn that ends in error records a workflow execution failure rather than `{:outcome :ok}`
- successful child-session turns still produce the canonical text envelope shape already used by workflow progression
- retry and workflow-failure behavior are covered by focused tests at the session and workflow levels

Scope:
In scope:
- broaden retry classification for transient LLM transport/stream failures
- define which stream/transport failure messages are treated as retryable in the current architecture
- update or factor retry classification logic so it can recognize the observed chunked-stream failure and closely related transient transport failures
- ensure workflow execution distinguishes successful assistant output from assistant error turns
- ensure workflow execution submits execution failure into workflow progression when the child turn outcome is error
- add or update tests proving:
  - retry classification for transient transport failure text
  - statechart retry path activates for such errors when other retry conditions are met
  - workflow execution does not convert assistant error content into successful empty output
  - workflow run remains retryable/fails according to its retry policy when child execution returns an error turn

Out of scope:
- implementing general provider failover across different providers or models
- redesigning model-selection hierarchy or scoped-model resolution
- changing workflow retry-policy semantics beyond making current execution failures surface correctly
- introducing speculative retries for non-transient semantic/model errors
- changing unrelated provider transports or API integrations beyond what is needed for correct retry classification

Canonical behavior:
1. a provider stream fails with a transient transport/stream error during a turn
2. the terminal assistant error message or equivalent canonical execution result is classified as retryable when it matches the approved transient transport failure set
3. if session auto-retry is enabled and retry budget remains, the session enters the existing retry path with backoff and resumes execution
4. if a workflow child session ultimately produces a successful assistant turn, workflow execution records `{:outcome :ok :outputs {:text ...}}`
5. if a workflow child session ultimately produces an error turn, workflow execution records execution failure through workflow progression rather than a successful empty output envelope
6. workflow progression then applies the step retry policy to that execution failure as it already does for other execution failures

Retry classification requirements:
- classification remains explicit and code-defined, not heuristic by scattered call sites
- session retryability for provider/transport failures must remain centralized in one canonical retry-classification helper used by the session statechart
- for this task, `session/retry-error?` may remain that canonical helper, or a new helper may be introduced, but there must be one authoritative retry-classification entry point and existing call sites must converge on it rather than forking logic
- workflow execution must not introduce its own retryability classifier
- the observed error text `Premature end of chunk coded message body: closing chunk expected` must be treated as retryable
- retryable transport failures must be defined as an explicit allowlist/pattern set for transport-boundary interruption cases, not as a broad catch-all for arbitrary parsing or provider errors
- minimum required case for task completion: incomplete or prematurely terminated chunked/streamed HTTP response body delivery, including the observed chunk-coded-body termination failure
- any additional included messages are optional in this task and, if added, must clearly indicate interrupted or partial provider streaming at the transport boundary rather than invalid request, auth failure, content-policy refusal, or semantic/model error
- the allowlist/pattern set must be represented in code and covered by focused tests
- context-window/token overflow errors must remain excluded from auto-retry
- non-transient semantic errors must remain excluded from auto-retry

Workflow execution requirements:
- workflow execution must inspect canonical child turn outcome, stop reason, and/or assistant error content before deciding result-envelope shape
- canonical child turn outcome and stop reason are authoritative over content-block text extraction when deciding success vs failure
- if the child turn is an error turn, workflow execution must record execution failure even if partial text content is present
- assistant messages containing only error content must not be treated as successful text output
- if workflow execution records an execution failure, the failure payload must preserve a minimum canonical diagnostic shape containing required field `:message`
- optional diagnostic fields such as `:stop-reason`, `:turn-outcome`, and `:session-id` may be included when they are readily available from the existing execution surface; their absence does not fail task acceptance
- successful text extraction behavior for normal assistant text output must remain unchanged

Possible implementation shapes:
- shape A: broaden the existing `session/retry-error?` helper with explicit transient transport-failure patterns and keep the statechart guard using that helper
- shape B: extract current retry classification into a slightly richer dedicated helper while preserving one canonical retry-classification entry point used by the statechart
- preferred direction: choose the smallest change that preserves one canonical retry-classification source of truth and does not duplicate classification logic in workflow execution

Acceptance:
- session retry classification recognizes the observed chunked-stream transport failure as retryable
- the minimum acceptable retryable transport-failure set for task completion includes the observed chunked-stream transport failure case; additional cases are optional
- focused tests prove the retry classifier behavior for the new transport failure case
- focused session/statechart tests prove the retry path activates observably for those failures under existing guard conditions by showing the retry transition/effect path rather than only indirect inference
- workflow execution no longer records `{:outcome :ok :outputs {:text ""}}` for assistant error turns
- workflow execution records child-session error turns as execution failure with required diagnostic field `:message`
- focused workflow execution tests prove the failure is propagated into workflow progression instead of being flattened into success
- existing successful workflow execution behavior remains green

Why this design is complete and unambiguous:
- it isolates two concrete defects observed in one live workflow run
- it states the exact failure text that must be handled
- it preserves existing retry and workflow progression architecture rather than introducing new orchestration concepts
- it separates transient retryable transport failures from out-of-scope provider failover and non-transient model errors
