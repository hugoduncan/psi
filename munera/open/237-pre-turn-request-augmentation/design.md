# Pre-turn request augmentation

## Goal

Add a core-owned pre-turn request augmentation phase so extensions such as `extensions/context-manager` can enrich a submitted user prompt before the parent turn is prepared and executed.

The phase must run after the user submits a prompt and before `prompt-prepare-request` builds the prepared request. It is intentionally part of the critical path: the parent turn waits for augmentation to complete, fail, or be canceled before execution continues.

## Problem

The current `context-manager` extension only observes `session_turn_finished` via fire-and-forget extension notification. That event is suitable for post-turn observation, but not for modifying the upcoming request:

- notification handlers return no data to the turn pipeline;
- returned values are discarded by `:notify/extension-dispatch`;
- post-turn events occur after the request has already executed;
- unrecorded extension output would break replay if it influenced the request.

We need a different mechanism: a synchronous-in-lifecycle, two-phase augmentation protocol whose outputs are recorded before request preparation.

## Intent

Introduce a new request-augmentation contract, not a new event-bus notification.

The contract should let privileged extensions register as turn augmenters. A turn augmenter receives enough turn/session context to decide what contextual blocks to add. It may perform internal work, including launching independent/child agent sessions, but it must return data only. Core validates, records, and applies the returned augmentation operations.

Replay must use the recorded augmentation operations and must not rerun augmenters or child sessions.

## Scope

This task covers the architecture, runtime protocol, and initial implementation for pre-turn augmentation.

In scope:

- Add a core-owned pre-turn augmentation lifecycle before request preparation.
- Add an extension registration mechanism for turn augmenters, distinct from `(:on api)` notification subscriptions.
- Invoke registered turn augmenters during the pre-turn phase.
- Allow augmentation providers to return typed augmentation operations as data.
- Record the exact augmentation operations and diagnostics in the turn/session event flow before request preparation.
- Apply recorded augmentation operations when building the prepared request.
- Make augmentation turn-scoped and tied to a specific `turn-id`.
- Ensure replay uses recorded augmentation operations instead of rerunning augmentation providers.
- Support augmentation providers that launch independent/child sessions to compute context.
- Mark augmentation-launched sessions with parent session/turn provenance.
- Suppress pre-turn augmentation inside augmentation child sessions by default to avoid recursion.
- Ensure cancellation/stale-result handling prevents late augmentation results from applying to the wrong turn.
- Update the `extensions/context-manager` scaffold to use the new mechanism for a minimal augmentation behavior.
- Add tests proving lifecycle ordering, recording/replay behavior, recursion suppression, cancellation/stale-result guarding, and request inclusion.

## Out of scope

- Explicit augmentation deadlines or timeouts.
- Explicit augmentation token budgets.
- Global trimming or prioritization of augmentation blocks.
- Arbitrary prepared-request mutation by extensions.
- Allowing extensions to change model, API key, tools, command list, system prompt, or user message.
- Interactive user prompts during pre-turn augmentation.
- Making child augmentation sessions ordinary unrestricted parent-turn participants.
- UI polish beyond inspectable diagnostics needed to verify behavior.
- General post-turn context compaction policy beyond what is needed for this pre-turn hook.

## Required semantics

### Two-phase prepare

The parent turn should follow this shape:

1. user submits prompt;
2. core creates/uses a `turn-id`;
3. core enters a pre-turn augmentation phase;
4. runtime invokes registered turn augmenters;
5. augmenters return validated augmentation data;
6. core records augmentation operations and diagnostics for the `turn-id`;
7. request preparation builds the prepared request from the original prompt plus recorded augmentation operations;
8. augmentation state for the turn is consumed or otherwise prevented from leaking into later turns;
9. the normal prompt execution and post-turn notification lifecycle continues.

### Critical path without deadline

Pre-turn augmentation is on the critical path. The parent turn waits for augmentation to return, fail, or be canceled.

No explicit deadline is required in this task. Because there is no deadline, cancellation and stale-result guarding are required safety properties.

### Data-only extension output

Turn augmenters must not mutate parent session state directly. They must not dispatch arbitrary parent mutations. They must return data to core.

Core owns:

- validation;
- recording;
- application to request building;
- replay semantics;
- cancellation/stale-result checks;
- final authority over supported operation types.

### Initial operation vocabulary

The initial supported operation should be minimal. The expected v1 operation is an append-only context block, conceptually:

```clojure
{:op :append-context-block
 :id "project-context"
 :title "Project context"
 :content "..."
 :source {:type :extension
          :extension-id "context-manager"
          :child-session-ids [...]}
 :trust :project-derived}
```

The exact schema may differ, but it must preserve these properties:

- append-only;
- turn-scoped;
- source/provenance is captured;
- project-derived context is distinguishable from privileged instructions;
- the operation does not alter system/developer policy, model selection, tools, API options, or the submitted user message.

### Child/independent sessions

The context-manager extension may launch independent/child sessions to gather information for augmentation.

Such sessions must be distinguishable from ordinary user sessions. They should carry parent provenance such as:

- parent session id;
- parent turn id;
- purpose similar to `:turn-augmentation`;
- marker disabling pre-turn augmentation by default.

Child augmentation sessions must not recursively trigger pre-turn augmentation unless a future explicit opt-in mechanism is designed.

Canceling the parent turn should prevent child results from being applied to that parent turn. Late results for an old `turn-id` must be ignored or recorded only as stale diagnostics.

### Replay

Replay must not rerun turn augmenters, child sessions, file reads, model calls, or other live augmentation work.

The replay source is the recorded augmentation operations on the parent turn. Child session ids and diagnostics are provenance, not replay dependencies.

## Acceptance criteria

- A new turn-augmentation registration path exists for extensions and is distinct from event notification subscription.
- The prompt lifecycle includes a pre-turn augmentation phase before prepared-request construction.
- A registered augmenter can return an append-context-block operation that appears in the prepared request for that turn.
- The exact operation applied to the request is recorded before request execution.
- Replaying the relevant lifecycle uses the recorded operation and does not invoke the augmenter again.
- Augmentation output is scoped to one `turn-id` and does not leak into a later turn.
- Augmentation child sessions are marked with parent session/turn provenance.
- Pre-turn augmentation is suppressed by default inside augmentation child sessions.
- Parent cancellation or turn mismatch prevents late augmentation results from being applied.
- Extension failures are captured as diagnostics and do not mutate the prepared request with partial/invalid data.
- Unsupported operations from an extension are rejected or ignored with diagnostics.
- The context-manager extension demonstrates the new mechanism with a minimal safe context block.
- Tests cover normal augmentation, no-op augmentation, extension failure, unsupported operation, replay, recursion suppression, and stale/canceled turn handling.

## Design constraints

- Preserve dispatch ownership of state changes and side effects.
- Preserve effects-as-data at impure boundaries.
- Preserve deterministic replay by recording augmentation data before it influences a request.
- Keep request construction core-owned.
- Keep extension authority narrow: extensions propose augmentation data; core decides whether and how to apply it.
- Prefer a small closed operation vocabulary over prepared-request diffs.

## Open questions for planning

- What exact event names and effect types should represent augmentation requested/applied/failed?
- Where should recorded augmentation operations live in session state while waiting for request preparation?
- Should augmentation diagnostics be exposed via existing `last-prepared-request-summary`, a new resolver, or both?
- What minimum API should an augmenter receive: original user message, session metadata, effective cwd, existing session context, or more?
- Should multiple augmenters run sequentially or independently with deterministic result ordering?
- How should extension permissions/manifests declare access to the turn-augmentation capability?
