# Design: Automatic Session Purpose Renaming — Workflow Details

## Revision marker

The rename workflow must capture enough source-session state to detect staleness
before applying the result.

The marker should identify the source snapshot used for inference, for example:
- completed-turn count
- latest journal entry identity
- latest session update timestamp

Exact representation is an implementation detail, but the workflow must be able
to answer: "Has the source session advanced since the rename evaluation began?"

If yes, discard the result.

## Sanitized conversation view

The rename inference must use a sanitized view of the source session.

### Include

- user textual messages
- assistant visible textual replies
- compacted/summary assistant text if it is part of the visible conversation

### Exclude

- tool calls
- tool results
- hidden/provider reasoning or thinking
- slash commands and navigation commands
- background-job terminal chatter
- extension/system noise that does not express user intent or assistant-visible
  task progress

## Helper execution model

The rename inference should run in an internal helper context rather than in the
source session itself.

Required properties:
- not user-facing in normal session navigation
- does not alter the source transcript
- does not recursively trigger the auto-rename extension
- tools disabled
- thinking disabled
- single-response execution

Preferred shape:
- create an internal child/helper session or equivalent isolated execution
  context
- seed it with the sanitized conversation view
- append a final user prompt asking for the current purpose

## Prompt contract

Recommended prompt:

> Based on this conversation, what is the current purpose of the session?
> Reply with only a terse phrase of 2–8 words.
> No quotes. No explanation.

The result should be treated as invalid if it does not satisfy the response
contract.

## Validation rules

A proposed title is valid when it satisfies all of the following:
- non-blank
- single-line
- within configured maximum length
- within configured word-count range
- not just punctuation or quoting
- not an explanation sentence when a phrase was requested

Suggested defaults: 2–8 words, max 60 characters.

## Apply rules

Apply the proposed rename only when:
- the helper run completed successfully
- the result validates
- the normalized new name differs from the current normalized name
- the source session revision marker still matches
- manual-name protection policy does not block the write

## Concurrency rules

For each source session:
- allow at most one rename inference job in flight
- if another trigger arrives while one is running, either coalesce into one
  pending rerun or skip until the next threshold
- never let an older result overwrite a newer session state

## Manual rename policy

This must be explicit. Recommended v1 policy:
- track whether the current name is user-authored or auto-derived
- if the current name is manual, auto-rename is suppressed until re-enabled

Alternative policies are possible, but the system should not silently oscillate
between user intent and automatic renaming.

## Failure policy

On helper-run failure, validation failure, or stale-source detection:
- leave the original session name unchanged
- do not inject failure text into the source transcript
- optional diagnostic UI/status may be added later, but v1 should default to
  silent failure

## Cost and cadence

This feature adds one extra model inference every `N` completed turns per
eligible session.

To bound cost and churn, v1 should support:
- enable/disable switch
- configurable rename cadence `N`
- optional helper-model override
- no-tools helper execution
- thinking disabled

## Recommended configuration surface

Suggested extension configuration:
- `:enabled?` — default `true`
- `:turn-interval` — default `2`
- `:max-title-chars` — default `60`
- `:min-words` — default `2`
- `:max-words` — default `8`
- `:helper-model` — optional override
- `:manual-name-policy` — default `:suppress-when-manual`
- `:debug-visibility?` — default `false`

## Architectural preference

Prefer an event-driven workflow over timer polling.

Preferred trigger model:
- observe prompt-lifecycle turn completion
- count completed turns
- schedule or enqueue a background rename job only when a threshold is crossed

A scheduler may still be useful for debounce/coalescing, but a blind periodic
polling loop is not the preferred architecture.

## Acceptance criteria

- A source session can be automatically renamed without mutating its transcript.
- Rename evaluation happens only after completed turns and idle transition.
- The inference input excludes tool calls, tool results, and hidden thinking.
- Helper runs do not recursively trigger rename inference.
- Stale helper results are discarded.
- Manual-name policy is enforced.
- The user-visible session tree shows updated names for successfully applied
  renames.
