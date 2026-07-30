# Context Management — Session Lifecycle with IRs

## On Prompt Submission

```
user text
  → :session/prompt-submit handler
    → core builds user-message map
    → core dispatches :session/ir-build-user-input (NEW dispatch event)
      → extension handler builds User Input IR
      → handler returns {:root-state-update f :return ir-map}
      → core stores IR in :extension-ir-data
    → core appends journal entry (as before)
    → returns {:turn-id 0 :user-msg msg}
  → :session/prompt handler
    → starts agent loop with user message
  → :session/prompt-prepare-request handler
    → calls :build-prepared-request-fn (ctx fn, not dispatch)
      → inside build-prepared-request, core calls
        :ir-project-conversion-fn (NEW ctx fn, extension-owned)
        → extension projects IRs into provider-neutral conversation entries:
          - queries :extension-ir-data for turn IRs and state IRs
          - selects which turns to include
          - projects Session State IR and Project State IR as system messages
          - removes older turns (replacing with summary or dropping)
          - returns projected provider-neutral conversation
        → core passes the projected provider-neutral conversation into the
          existing provider-specific projection/adaptation step
    → returns prepared-request after existing provider-specific projection
  → :session/prompt-execute handler
    → starts agent loop with prepared messages
```

Key point: projection happens inside `build-prepared-request` via a ctx fn,
not via dispatch. This is because `build-prepared-request` is already a ctx fn
(`:build-prepared-request-fn`), not a dispatch handler. Adding a dispatch event
here would break the synchronous flow.

## On Reply Completion (Turn Continue)

```
assistant message with tool calls
  → :session/prompt-record-response handler
    → core records response (journals assistant message)
    → core schedules :session/prompt-continue
  → :session/prompt-continue handler
    → runs tool calls (via :runtime/prompt-continue-chain effect)
    → after tool execution, tool results are journalled
  → loops back to :session/prompt-prepare-request
```

No Turn IR extraction during the continue loop. The raw journal entries
accumulate; the extension extracts semantic content from the complete turn after
it finishes. Core still stamps each journal entry in the loop with the current
turn id so the extension can recover the turn boundary.

## On Reply Completion (Turn Finish)

```
final assistant message (no tool calls)
  → :session/prompt-record-response handler
    → core records response (journals assistant message)
    → core schedules :session/prompt-finish
  → :session/prompt-finish handler
    → emits :on-agent-done event
    → dispatches :session/ir-process-reply (NEW, via effect, async)
      → extension processes the complete turn out-of-band:
        - reads all journal entries for this turn
        - extracts Turn IR: entities, relationships, claims, questions,
          decisions, actions
        - updates Session State IR (turn count, context usage)
        - generates Project Event IRs from extracted content
        - re-materialises Project State IR if events warrant
        - optionally extracts memories/knowledge
      → user sees reply immediately; processing happens in background
```

Reply processing is ordered per session. The context manager processes completed
turns sequentially by turn id for a given session-id; it does not process turn
`N+1` before turn `N`. If the user submits the next prompt before processing has
finished, projection uses the latest completed IRs and raw journal fallback for
any still-unprocessed turns.

## On Auto-Compaction

```
context threshold exceeded
  → :runtime/auto-compact-workflow effect
    → core dispatches :session/ir-compact (NEW dispatch event)
      → extension handler decides compaction strategy:
        - queries :extension-ir-data for all turn IRs
        - scores turns by relevance
        - summarises old turns into compact summary
        - returns {:root-state-update f :return {:summary "..." :turns-to-remove [...]}}
        → f updates :extension-ir-data with compaction summary
          and removes old turn IRs
      → core records the compaction metadata for future projection; the journal
        remains the source of truth and is not rebuilt from IRs
    → extension dispatches ir_compacted event
```

## On Session Resume

```
session file loaded
  → :session/resume-loaded handler
    → core rebuilds messages from journal entries (as today)
    → core dispatches :session/ir-rebuild (NEW dispatch event)
      → extension rebuilds IRs from journal entries:
        - scans journal for turn boundaries
        - extracts Turn IRs for each turn
        - builds Session State IR from current session data
        - loads or re-materialises Project State IR from cache
        - enforces size budgets and creates overflow files as needed
      → IRs are ready for the next projection
```

IR rebuild is a one-time cost at session start. The extension can optimise by
only rebuilding IRs since the last compaction boundary.

## Projection Fallback

When the context manager extension is not installed, or when projection fails,
the ctx fn `:ir-project-conversion-fn` returns `nil` to signal core to use the
default journal-to-neutral-conversation projection, followed by the existing
provider-specific projection/adaptation step. When only some Turn IRs are absent (e.g.,
the next turn starts before out-of-band processing finishes), the projector may
mix IR-projected turns with raw journal fallback for the missing turns.

This means the system always works — the IR pipeline is an enhancement, not a
requirement.
