# 134 — psi-tool mutation surface and session-summary introspection

## Goal

Make `psi-tool` able to perform safe canonical runtime mutations and make compact session inventory queryable enough that session-admin tasks can be completed through the tool surface without dropping to raw runtime `eval`.

## Why

A recent live-context cleanup task exposed a gap in `psi-tool`'s current shape.

The runtime already has the needed underlying capabilities:

- canonical session lifecycle mutations such as `psi.extension/close-session`
- canonical session graph surfaces such as `:psi.agent-session/context-sessions`

But `psi-tool` currently makes this workflow harder than it should be because:

- there is no canonical mutation action, so imperative runtime work often falls back to raw `eval`
- existing session-list/query surfaces can be too large or too detailed for routine operational tasks
- when session-targeted introspection is awkward, operators are pushed toward unsafe shortcuts or code inspection instead of the authoritative runtime surface

The result is that a generic session lifecycle operation such as "close all non-current sessions" requires code-path discovery and ad hoc runtime evaluation even though the system already owns the necessary mutation and introspection primitives.

## Problem

`psi-tool` is currently asymmetric:

- reads are first-class through `action: "query"`
- some imperative domains are first-class through narrow actions like `reload-code`, `project-repl`, `workflow`, and `scheduler`
- generic canonical graph mutations are not first-class

That asymmetry causes two concrete problems:

1. Canonical imperative operations that already exist as mutations cannot be used directly from `psi-tool`.
2. Routine session inventory queries can over-return, making it harder to compose discovery → select targets → mutate safely.

## Intent

Add the smallest coherent `psi-tool` surface needed to support safe generic session-admin and similar runtime tasks without inventing bespoke commands for each case.

This task should:

- add a canonical `psi-tool` mutation action for invoking existing registered mutations
- expose a compact session-summary root surface suitable for operational selection and follow-up mutation
- preserve explicit session targeting and fail clearly on invalid mutation requests
- keep `psi-tool` explicit and structured rather than turning it into an unbounded command proxy

This task should not:

- add an explicit `delete-old-sessions` or similar task-specific command
- broaden into a generic raw dispatch/event injection surface
- replace existing domain-specific `psi-tool` actions such as `scheduler`, `workflow`, or `project-repl`
- redesign session lifecycle semantics themselves
- solve unrelated query-graph ergonomics beyond the minimal session/admin surfaces needed here

## Decision

Adopt a focused generic mutation surface plus compact session-summary introspection.

Specifically:

1. add `psi-tool(action: "mutate", ...)`
2. add a compact root attr for context session summaries

This is preferred over adding one-off lifecycle commands because it improves the general `psi-tool` contract while still keeping the surface explicit and bounded.

## In scope

- `psi-tool` support for a new `action: "mutate"`
- validation and execution of registered canonical mutations through that action
- stable structured mutation result reporting
- root graph support for compact context session summaries
- focused documentation and tests for the canonical query → choose targets → mutate workflow
- session-admin use cases such as closing explicitly selected non-active sessions through canonical mutation calls

## Out of scope

- authoritative active session id root attr — see task 139
- bespoke convenience commands for deleting/cleaning old sessions
- broad batching/looping DSLs inside `psi-tool`
- generic arbitrary dispatch event submission
- generic namespace eval replacement; `eval` remains a separate escape hatch
- redesign of session persistence or session ordering semantics
- UI-specific affordances for bulk session management
- dry-run support unless the implementation shows it falls out trivially from the chosen mutation result model

## Canonical `psi-tool` mutation surface

Add a new action:

- `action: "mutate"`

### API contract

#### Request fields

Required:

- `action`
  - must be `"mutate"`
- `mutation`
  - string form of a qualified mutation symbol
  - must parse as a qualified symbol
  - example: `"psi.extension/close-session"`

Optional:

- `params`
  - canonical mutation parameter payload
  - canonical external shape in v1: map / object
  - when provided, must be a map/object shape rather than a scalar or collection payload
  - implementations may normalize top-level string-keyed input into the keyword-keyed map shape expected by the canonical mutation path
  - v1 should preserve values and unknown keys rather than performing broader semantic rewriting

Explicitly unsupported in v1:

- `entity`
  - supplying `entity` to `action: "mutate"` must return a validation error
- tool-level `session-id` routing convenience
- `target-session-id`
- batching or `params-list`

Rationale:

- many canonical mutations already use `:session-id` as ordinary business data
- adding a tool-level field with the same meaning would create ambiguity
- the smallest clear contract is: `psi-tool` invokes exactly one registered mutation and passes exactly one params map
- `entity` remains query-specific in v1 so mutation targeting semantics stay unambiguous

#### Canonical invocation semantics

`psi-tool(action: "mutate", ...)` must:

1. validate that `mutation` names a registered mutation
2. validate/coerce `params` into the expected map form if needed
3. invoke the canonical registered mutation path
4. return the mutation payload in structured tool-result form
5. fail explicitly when the request is invalid or unsupported

For this task, "canonical registered mutation path" means the same production-owned runtime mutation execution helper/path already used to invoke registered graph mutations with their normal capability, permission, and validation enforcement. Implementation must identify that concrete owner/helper before coding and route `action: "mutate"` through it rather than introducing a parallel execution path.

For this task, "registered mutation" means:

- a mutation available in the live runtime mutation registry used by psi-tool's canonical mutation execution path
- including extension-provided mutations when they are already present in that same live runtime registry

The mutate action must reuse the same capability, permission, and validation enforcement already present in that canonical mutation execution path. It must not bypass those checks or introduce a broader write surface than the existing runtime already exposes.

It must not:

- fall back to raw runtime `eval`
- route through slash-command parsing
- silently rewrite mutation params
- silently retarget session-scoped work to some implicit active session other than what the mutation/runtime already defines canonically

### Supported invocation shape

At minimum, the tool must support one mutation invocation per call.

Canonical example:

```json
{
  "action": "mutate",
  "mutation": "psi.extension/close-session",
  "params": {"session-id": "81be60be-bd6a-44d6-9fea-ea8d1feffbdf"}
}
```

### Result contract

Return a stable machine-oriented result with these top-level fields:

- `:psi-tool/action`
  - `:mutate`
- `:psi-tool/mutation`
  - qualified symbol of the invoked mutation
- `:psi-tool/duration-ms`
  - non-negative execution duration
- `:psi-tool/overall-status`
  - `:ok` or `:error`
- `:psi-tool/result`
  - mutation payload on success
- `:psi-tool/error`
  - structured error on failure

Result invariants:

- on success, `:psi-tool/error` is absent
- on error, `:psi-tool/result` is absent
- `:psi-tool/result nil` is still a valid successful result when the invoked mutation canonically returns `nil`

#### Successful result example

```clojure
#:psi-tool{:action :mutate
           :mutation 'psi.extension/close-session
           :duration-ms 4
           :overall-status :ok
           :result {:psi.agent-session/close-session-closed? true
                    :psi.agent-session/close-session-id "81be60be-bd6a-44d6-9fea-ea8d1feffbdf"}}
```

#### Error contract

Errors must stay explicit and structured.

Canonical error fields:

- `:phase`
  - one of `:validate`, `:mutation`, or another intentionally chosen narrow phase label
  - `:validate` means the tool request was malformed, unsupported, or named an unknown mutation before canonical mutation invocation began
  - `:mutation` means canonical mutation invocation was attempted and failed
- `:message`
  - human-readable summary
- `:class`
  - exception class name when applicable
- optional `:data`
  - machine-usable error details, including preserved original `ex-data` where available

Unknown mutation example:

```clojure
#:psi-tool{:action :mutate
           :mutation 'psi.extension/not-a-real-mutation
           :duration-ms 0
           :overall-status :error
           :error {:phase :validate
                   :message "Unknown psi-tool mutation: psi.extension/not-a-real-mutation"
                   :class "clojure.lang.ExceptionInfo"
                   :data {:mutation "psi.extension/not-a-real-mutation"}}}
```

Malformed params example:

```clojure
#:psi-tool{:action :mutate
           :mutation 'psi.extension/close-session
           :duration-ms 0
           :overall-status :error
           :error {:phase :validate
                   :message "psi-tool mutate requires `params` to be a map"
                   :class "clojure.lang.ExceptionInfo"
                   :data {:mutation "psi.extension/close-session"
                          :params-type :string}}}
```

Missing required mutation input example:

```clojure
#:psi-tool{:action :mutate
           :mutation 'psi.extension/close-session
           :duration-ms 1
           :overall-status :error
           :error {:phase :mutation
                   :message "Mutation psi.extension/close-session requires :session-id"
                   :class "clojure.lang.ExceptionInfo"}}
```

### Validation rules

- `mutation` must be a string and must parse as a qualified symbol
- `mutation` must resolve to a registered mutation on the canonical runtime graph
- malformed or unknown mutation names must return explicit structured errors
- malformed `params` must return explicit structured errors
- when present, `params` must be a map/object payload
- top-level string map keys may be normalized to keywords; broader coercion or semantic rewriting is out of scope for v1
- mutation invocation must not silently fall back to raw eval or command parsing
- if the mutation requires explicit parameters, missing required inputs must fail clearly
- mutation execution must preserve explicit targeting semantics rather than silently hitting the wrong session

## Compact session summary introspection

Add a compact root attr intended for operational selection.

Canonical name:

- `:psi.agent-session/context-session-summaries`

Each entry should expose only the minimum stable fields needed to identify and select sessions safely.

Canonical entry shape:

- `:psi.session-info/id`
- `:psi.session-info/display-name`
- `:psi.session-info/created`
- `:psi.session-info/updated`
- `:psi.session-info/parent-session-id`
- `:psi.session-info/worktree-path`

In v1, this compact surface should expose exactly those fields and no additional fields.

Explicitly excluded from this summary surface:

- `:psi.session-info/first-message`
- `:psi.session-info/all-messages-text`
- full message history
- message-history joins
- large transcript/body fields
- heavy per-session telemetry payloads unrelated to identification/selection

### Session summary query example

```edn
[{:psi.agent-session/context-session-summaries
  [:psi.session-info/id
   :psi.session-info/display-name
   :psi.session-info/created
   :psi.session-info/updated
   :psi.session-info/parent-session-id
   :psi.session-info/worktree-path]}]
```

### Session summary result example

```clojure
{:psi.agent-session/context-session-summaries
 [{:psi.session-info/id "8fe9b0a6-ad8a-4373-9478-557e537499f2"
   :psi.session-info/display-name "Fix Flaky RPC Prompt Test"
   :psi.session-info/created "2026-05-08T17:35:29.217528Z"
   :psi.session-info/updated "2026-05-08T17:40:03.000000Z"
   :psi.session-info/parent-session-id nil
   :psi.session-info/worktree-path "/abs/path/to/worktree"}
  {:psi.session-info/id "731274a7-55d0-4854-aa23-35df82c6abdd"
   :psi.session-info/display-name "Clean up all old non-current sessions in the li…"
   :psi.session-info/created "2026-05-09T15:03:48.579050Z"
   :psi.session-info/updated "2026-05-09T15:05:10.000000Z"
   :psi.session-info/parent-session-id nil
   :psi.session-info/worktree-path "/abs/path/to/worktree"}]}
```

This surface should be deliberately smaller than the broader session-list outputs and should avoid heavy transcript/message payloads.

Ordering rule:

- `:psi.agent-session/context-session-summaries` must preserve the same canonical session ordering as the existing context session inventory surface rather than introducing a new independent ordering.

Source/ownership rule:

- the summary attr should prefer reusing the existing canonical session-info source/projection and trimming it to the allowed fields rather than creating a divergent parallel session inventory model.

## Relationship to prior session-targeting work

This task is related to, but distinct from, the earlier `psi-tool` session-targeting introspection work.

That earlier work established the need for trustworthy explicit targeting and for clear failure over silent wrong-session answers.

This task extends that direction by:

- giving `psi-tool` a canonical mutation surface
- providing a compact discovery surface that composes naturally with explicit mutation calls

If implementation touches existing session-targeting semantics, it must preserve the rule that unsupported targeting forms fail explicitly rather than degrading to plausible wrong-session results.

## Intended workflow after the change

The canonical operator/agent workflow (assuming task 139 delivers `:psi.agent-session/active-session-id`) should be:

1. query `:psi.agent-session/active-session-id` (task 139)
2. query `:psi.agent-session/context-session-summaries`
3. select explicit non-active session ids in caller logic
4. call `psi-tool(action: "mutate", mutation: "psi.extension/close-session", params: {:session-id ...})` for each chosen session

This keeps policy in caller logic and capability in the tool/runtime surface.

### End-to-end example

Step 1 — query the active session id (requires task 139):

```json
{
  "action": "query",
  "query": "[:psi.agent-session/active-session-id]"
}
```

Step 2 — query compact session summaries:

```json
{
  "action": "query",
  "query": "[{:psi.agent-session/context-session-summaries [:psi.session-info/id :psi.session-info/display-name :psi.session-info/created :psi.session-info/updated :psi.session-info/parent-session-id :psi.session-info/worktree-path]}]"
}
```

Step 3 — caller chooses a non-active session id and closes it:

```json
{
  "action": "mutate",
  "mutation": "psi.extension/close-session",
  "params": {"session-id": "8fe9b0a6-ad8a-4373-9478-557e537499f2"}
}
```

Step 4 — verify the inventory again:

```json
{
  "action": "query",
  "query": "[{:psi.agent-session/context-session-summaries [:psi.session-info/id :psi.session-info/display-name]}]"
}
```

## Constraints

- preserve `psi-tool` as an explicit validated API surface, not a free-form command proxy
- reuse canonical registered mutations rather than parallel imperative code paths
- preserve current domain-specific `psi-tool` actions and their contracts
- invalid mutation requests must fail explicitly
- compact session summaries must avoid large payload expansion
- mutate must reuse the existing capability, permission, and validation enforcement of the canonical mutation path rather than bypassing it
- the implementation should prefer the smallest coherent addition over a broad mutation-framework redesign

## Test contract

Implementation should add focused proof at two levels.

### 1. psi-tool mutation action tests

Cover at least:

- successful invocation of a registered mutation through `action: "mutate"`
- result payload preserves the mutation's returned data under `:psi-tool/result`
- unknown mutation name returns structured `:validate` error
- malformed `params` returns structured `:validate` error
- supplying unsupported `entity` returns structured `:validate` error
- mutation-level missing-required-input failure returns structured error rather than tool crash or silent success
- on success, `:psi-tool/error` is absent
- on error, `:psi-tool/result` is absent
- mutation action does not route through raw eval or command parsing helpers
- mutation action reuses canonical capability/permission/validation enforcement rather than bypassing it

### 2. introspection resolver tests

Cover at least:

- `:psi.agent-session/context-session-summaries` returns compact entries with the intended keys
- the summary surface excludes `:psi.session-info/first-message`, `:psi.session-info/all-messages-text`, message-history joins, and other heavy transcript/message payloads
- the summary surface preserves the canonical ordering of the existing context session inventory
- the attr appears on the intended graph surface and is queryable from root

### 3. composed workflow/integration test

Add at least one proof of the intended operator workflow (note: `:psi.agent-session/active-session-id` requires task 139; the integration test may use a known session id directly in place of that query step, or treat 139 as a prerequisite):

- create or load more than one context session
- query compact session summaries
- choose an explicit non-active session id
- invoke `psi-tool(action: "mutate", mutation: "psi.extension/close-session", ...)`
- verify the chosen session is gone while the other session remains

This integration proof exists to show that the new surface removes the need for raw runtime eval or bespoke cleanup commands.

## Acceptance

- `psi-tool` supports a canonical `action: "mutate"` for invoking registered runtime mutations
- the API contract for request, success result, and error result is documented with examples
- valid mutation requests return structured machine-oriented results without dropping to raw eval or command parsing
- unknown mutation names and malformed mutation requests fail explicitly with structured errors
- the graph exposes a compact root attr for context session summaries suitable for operational selection
- the compact session summary query shape and an end-to-end query → select → mutate example are documented
- focused tests cover successful mutation execution, validation/error behavior, the compact session-summary attr, and the composed session-admin workflow
- session lifecycle mutations such as `psi.extension/close-session` can be invoked through `psi-tool(action: "mutate", ...)`
- the resulting workflow makes "close a chosen session" implementable in caller logic without any bespoke delete-sessions command
- implementation preserves explicit targeting semantics and does not silently route session-scoped work to the wrong session

## Related work

- `012-psi-tool-session-targeting-introspection` established the need for trustworthy explicit session targeting
- `023-extend-psi-tool-for-project-repl` is a precedent for adding a focused imperative `psi-tool` capability without broadening into a generic command proxy
- `033-psi-tool-scheduler-surface` is a precedent for explicit structured imperative tool actions
- `139-active-session-id-root-attr` — authoritative active session id; composes with this task's compact summaries and mutate action for full session-admin workflows
