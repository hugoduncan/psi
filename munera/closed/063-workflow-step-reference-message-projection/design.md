# 063 — Workflow step reference message projection

## Goal

Allow workflow steps to preload constrained projected message/transcript context into child sessions.

## Context

Task 059 is the umbrella. This task extracts Phase 4 so session-first workflow authoring can shape reference conversation context, not just current input bindings.

## Scope

In scope:
- define authoring for reference/preloaded context under `:session`
- keep that authoring aligned with task-060/task-061 source+projection structure rather than inventing a separate reference DSL
- support at least one projected message/transcript form
- settle one canonical source of truth for step-session message/transcript projection
- feed projected context into child-session creation/preloading
- add focused execution tests

Stretch if it fits cleanly within the task:
- tail selection
- tool-output stripping

Out of scope:
- arbitrary transcript transformation logic
- broad session reuse redesign

## Authoring shape

Task `063` should introduce a dedicated preload/reference entry under `:session` rather than overloading prompt-binding channels.

Preferred shape:

```clojure
{:name "review"
 :workflow "reviewer"
 :session {:input {:from {:step "build" :kind :accepted-result}
                   :projection :text}
           :reference {:from :workflow-original}
           :preload [{:from {:step "plan" :kind :accepted-result}
                      :projection :full}
                     {:from {:step "build" :kind :session-transcript}
                      :projection {:type :tail :turns 4}}]}
 :prompt "Review the following implementation:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}
```

Task-063-specific semantics:
- `:session :preload` is the constrained authoring surface for preloaded message/transcript context
- preload entries reuse the same author-facing source reference rule as earlier tasks: `{:step "<step-name>" ...}` refers to step `:name`
- preload entries reuse the same source-then-projection layering model established by tasks `060` and `061`
- preloaded context shapes what messages/context are present in the child session before prompt submission
- preloaded context does not implicitly populate `$INPUT` or `$ORIGINAL`
- `$INPUT` and `$ORIGINAL` continue to be controlled only by `:session :input` and `:session :reference`

## Validation

Validation should reject at least:
- malformed preload entry forms
- unknown step names
- forward references to later-defined steps
- unsupported transcript/message projection operators for the implemented task surface
- unsupported preload-related `:session` keys for the implemented `060`–`063` surface

## Acceptance

- [ ] `:session :preload` can describe constrained reference/preloaded message context
- [ ] One canonical source of truth for projected step-session messages is documented and used
- [ ] At least one transcript/message projection form is supported
- [ ] Preloaded context remains distinct from prompt-binding channels
- [ ] Focused execution tests prove the projected context reaches the child session
