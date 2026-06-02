# Scheduler

Psi exposes delayed scheduler control through `psi-tool`.

The scheduler is intentionally **one-shot** and **volatile**:
- schedules do not survive process restart
- recurring schedules are not supported
- create/list/cancel are the canonical ops

## psi-tool surface

Scheduler requests use:

```clojure
{:action "scheduler"
 :op "create" | "list" | "cancel"}
```

Create supports two explicit kinds:
- `"message"` — deliver a delayed prompt into the originating session
- `"session"` — create a delayed fresh top-level session and submit the prompt there

## `kind: "message"`

Delayed prompt injection into the invoking/origin session.

Example:

```clojure
{:action "scheduler"
 :op "create"
 :kind "message"
 :label "check-build"
 :delay-ms 600000
 :message "Check whether the build finished."}
```

Semantics:
- the schedule belongs to the invoking session
- if that session is busy when the timer fires, delivery queues until idle
- injected prompt appears as a user-role message with scheduled provenance

## `kind: "session"`

Delayed creation of a **fresh top-level session** in the same worktree/context as the origin session, followed by prompt submission into that new session.

Example:

```clojure
{:action "scheduler"
 :op "create"
 :kind "session"
 :label "morning-review"
 :at "2026-04-22T13:00:00Z"
 :message "Review the overnight changes and summarize risks."
 :session-config {:session-name "Morning review"
                  :thinking-level :high
                  :cache-breakpoints #{:system}
                  :preloaded-messages [{:role "user"
                                        :content [{:type :text :text "Context: review the last merged PR first."}]
                                        :timestamp #inst "2026-04-22T12:59:00Z"}]}}
```

Semantics:
- created session is **top-level**, not a child session
- created session does **not** become active/focused automatically
- origin session busy state does **not** block `kind: "session"` delivery
- created session records scheduler provenance:
  - `:scheduled-origin-session-id`
  - `:scheduled-from-schedule-id`
  - `:scheduled-from-label`

## Supported `session-config` subset

Current supported scheduler `session-config` keys:
- `:session-name`
- `:system-prompt`
- `:model`
- `:thinking-level`
- `:skills`
- `:tool-ids`
- `:developer-prompt`
- `:developer-prompt-source`
- `:preloaded-messages`
- `:cache-breakpoints`
- `:prompt-component-selection`

Unsupported keys are rejected explicitly.

## Create validation rules

Exactly one of these delay forms is required:
- `:delay-ms`
- `:at`

Bounds:
- minimum relative delay: `1000ms`
- maximum relative delay: `24h`
- absolute `:at` values are first resolved to a millisecond delay
- `:at` values resolving to delay `0` fire immediately (past/now, plus any
  sub-millisecond future instant that truncates to `0ms`)
- future `:at` values resolving to a positive delay below `1000ms` (`1–999ms`)
  are rejected by the minimum bound
- future `:at` values resolving beyond `24h` are rejected by the maximum bound

Kind-specific rules:
- `kind: "message"` requires `:message` and forbids `:session-config`
- `kind: "session"` requires both `:message` and `:session-config`

## List

List pending scheduler entries for the invoking session:

```clojure
{:action "scheduler"
 :op "list"}
```

Default scheduler summaries are compact.

Common summary fields include:
- `:schedule-id`
- `:kind`
- `:label`
- `:status`
- `:origin-session-id`
- `:fire-at`

Session-kind summaries also include compact `:session-config-summary` fields such as:
- `:session-name`
- `:model`
- `:thinking-level`
- `:skill-count`
- `:tool-count`
- `:preloaded-message-count`

## Cancel

Cancel a pending or queued schedule:

```clojure
{:action "scheduler"
 :op "cancel"
 :schedule-id "sch-..."}
```

Cancel works on:
- `:pending`
- `:queued`

Delivered schedules are not cancellable.

## Status model

Scheduler statuses are:
- `:pending`
- `:queued`
- `:delivered`
- `:cancelled`
- `:failed`

For `kind: "session"`, failure can preserve delivery-phase detail such as:
- `:create-session`
- `:prompt-submit`

If session creation succeeds but prompt submission fails, the schedule becomes `:failed` and still records the created session id.

## Introspection

Scheduler projections now expose explicit public attrs such as:
- `:psi.scheduler/kind`
- `:psi.scheduler/origin-session-id`
- `:psi.scheduler/created-session-id`
- `:psi.scheduler/delivery-phase`
- `:psi.scheduler/error-summary`
- `:psi.scheduler/session-config-summary`

These are available through the live EQL graph and are also reflected in `psi-tool` scheduler summaries.

## Notes

The scheduler is scoped to the invoking session/context.
It is not a general cross-session orchestration API.

`kind: "session"` is intentionally the delayed creation of a **fresh top-level** session, not child-session creation.