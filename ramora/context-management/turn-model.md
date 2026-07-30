# Context Management — Turn Model

A **turn** is one complete LLM interaction cycle: user input → LLM response →
tool calls → tool results → LLM response → ... → final response.

In the current code, this is the `prompt-submit` → `prompt` →
`prompt-prepare-request` → `prompt-execute` → (`prompt-continue` → repeat) →
`prompt-finish` lifecycle. One turn may produce multiple journal entries
(assistant messages with tool calls, tool result messages, final assistant
message).

The IR model treats this as **one turn IR** that captures the *semantic content*
of the turn — what was discussed, decided, or discovered — rather than the LLM
mechanics of how it was produced. The journal remains the authoritative record
of raw messages; the turn IR is a structured extraction of meaning.

```
Turn IR:
  user-intent:   what the user wanted (structured)
  entities:      named things mentioned (files, tasks, concepts)
  relationships: how entities relate to each other
  claims:        factual assertions made during the turn
  questions:     questions asked or raised
  decisions:     decisions reached or proposed
  actions:       things done or committed to
  metadata:      turn-id, timestamp, source references
```

This is different from the journal model where each message is a separate entry
with no semantic structure. The IR groups by turn boundary and extracts meaning.

## Turn IDs

Core creates turn ids per session at prompt submission time. Turn ids are
sequential from zero within each `session-id` (for example, turn `0`, `1`, `2`,
...). Every journal entry produced by the prompt/continue/finish lifecycle is
stamped with the current turn id. This makes tool-call continuations and final
assistant messages recoverable as one semantic turn.

On resume, the extension rebuilds Turn IRs by grouping journal entries by the
persisted turn id. For legacy journal entries without turn ids, rebuild uses a
best-effort boundary scan (`user` entry followed by assistant/tool/result entries
until the next user entry) and may assign synthetic sequential ids for rebuilt
IRs.
