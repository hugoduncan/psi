# Design: Automatic Session Purpose Renaming — Proposal

**Status:** Proposal
**Scope:** extension design, extension/runtime capability fit, non-code gap analysis

## Problem

Session names currently reflect either an explicit manual label or a fallback
based on recent user text. As work progresses, the purpose of a session can
shift substantially while the visible session name lags behind. This makes
session trees, resume flows, and long-running work harder to scan.

We want an extension that keeps a session name aligned with the session's
current purpose without adding user-facing noise or mutating the source
conversation.

## Goals

- Keep the visible session name aligned with the current purpose of the
  conversation.
- Rename automatically in the background as the session evolves.
- Derive the new name from the conversation itself, not from tool traces or
  hidden reasoning.
- Avoid polluting the source session transcript.
- Avoid visible helper sessions in normal user workflows.
- Avoid races, recursion, and excessive rename churn.

## Non-goals

- Perfect semantic summarization.
- Preserving a rename history in v1.
- Reviewing each proposed rename with the user.
- Retroactive repair of old session names.
- Persisting scheduled rename work across process restart.

## Proposed user-visible behavior

For each eligible session, after every `N` completed prompt-lifecycle turns,
when the session has returned to `:idle`, the extension evaluates whether the
session name should change.

Default: `N = 2`

The evaluation runs in the background. If it succeeds and the result is still
current, the original session name is updated to a terse phrase describing the
current purpose of the session.

If it fails, the original session remains unchanged.

## Definitions

### Eligible session

A session is eligible when all of the following hold:
- it is a normal user session, not an internal helper session
- automatic renaming is enabled for that session/project/runtime
- it is not currently protected by a manual-name policy

### Completed turn

A completed turn is one source-session assistant turn that has fully completed
on the shared prompt lifecycle and returned the session to `:idle`.

Implications:
- streamed partial output does not count
- individual tool calls do not count
- tool-use continuation remains part of the same turn until terminal completion

### Current purpose

The current purpose is the best terse description of what the session is now
trying to achieve, based on user-visible conversation content.

## Proposed rename workflow

When the source session reaches the rename threshold:

1. Capture a source-session revision marker.
2. Build a sanitized conversation view from the source session.
3. Start one internal helper run for rename inference.
4. Ask for the current purpose as a terse phrase.
5. Validate the proposed title.
6. Re-check that the source session has not moved on.
7. Apply the new name to the original session.

See [workflow.md](workflow.md) for revision markers, sanitized views, helper execution, validation, apply rules, concurrency, manual rename policy, failure policy, cost/cadence, configuration, and acceptance criteria.

## Open design questions

1. Should helper runs use the session's current model or a dedicated cheap model?
2. Should compaction summaries be included when they are the best current
   visible representation of the conversation?
3. Should manual names suppress auto-renaming forever, or only until an explicit
   reset?
4. Should helper contexts be persisted at all, or be purely ephemeral runtime
   state?
5. Should this extension expose user-visible status when rename inference is in
   progress?
