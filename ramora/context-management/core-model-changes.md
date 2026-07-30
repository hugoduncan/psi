# Context Management — Core Model Changes

## Journal Metadata Contract

Core adds enough metadata to journal entries for deterministic IR rebuild and
neutral-conversation projection:

```clojure
{:journal/entry-id "stable-entry-id"
 :journal/turn-id 0
 :journal/turn-seq 0
 :journal/timestamp instant
 :journal/kind :user|:assistant|:tool-call|:tool-result|:system|:summary
 :journal/provider {:id "openai" :model "..."}
 :journal/tool-call-id "provider-tool-call-id"
 :journal/tool-result-for "provider-tool-call-id"}
```

Required fields are `:journal/entry-id`, `:journal/turn-id`,
`:journal/turn-seq`, `:journal/timestamp`, and `:journal/kind`. Tool linkage
fields are required when the entry represents a tool call or tool result. The
projector uses this metadata to either preserve a raw neutral-conversation
segment or replace the whole segment with summary/context.

Legacy journal entries without this metadata are supported by best-effort rebuild
only. They may be projected as raw journal history or summarised conservatively;
they are not expected to produce high-fidelity Turn IRs.

## Session Data

Add to `agent-session-schema` in `psi.session-state.model`:

```clojure
[:extension-ir-data {:optional true}
 [:map-of :keyword [:map-of :keyword :any]]]
```

Outer key is extension path, inner keys are extension-defined.
Initial value in `initial-session`: `{}`.

This is an opaque store. Core never reads or interprets the values — only the
extension does via EQL query.

## Context Function Additions

Add one context-manager projection function to the ctx map (built in
`context.clj`):

```clojure
:ir-project-conversion-fn  (fn [ctx session-id turn-id user-msg base-conversation]
                             ;; returns {:conversation [...] :metadata {...}}
                             ;; or nil to use base conversation unchanged)
```

Projection is synchronous and happens inside request preparation. All IR state
creation and mutation (`build-user-input`, reply processing, compaction metadata,
and rebuild) goes through dispatch events/mutations, not ctx functions. When no
context manager extension is installed, the projector is nil and core uses
legacy behaviour.

## New Dispatch Events

### `:session/ir-build-user-input`

Dispatched during `:session/prompt-submit`, after the user message is built
but before journaling.

**Input:**
```clojure
{:session-id sid
 :user-msg user-message-map
 :expansion expansion-map-or-nil
 :commands command-names-vector}
```

**Handler:** stores the User Input IR on the session under
`:extension-ir-data :context-manager :user-input-irs turn-id`.

**Return:** `{:root-state-update f :return ir-map}`

**Fallback:** when no handler registered, returns `{:return nil}`.

### `:session/ir-process-reply`

Dispatched as an effect during `:session/prompt-finish` (async, non-blocking).

**Input:**
```clojure
{:session-id sid
 :turn-id 0
 :journal-entries [journal-entry ...]}
```

**Handler:** reads the journal entries for the completed turn, extracts the
Turn IR (entities, relationships, claims, questions, decisions, actions),
generates project events, updates session state, and optionally extracts
memories. No return value needed.

**Fallback:** when no handler registered, returns `{}`.

### `:session/ir-compact`

Dispatched during auto-compaction when the context manager extension is
installed.

**Input:**
```clojure
{:session-id sid
 :ir-data :extension-ir-data-from-session
 :context-tokens N
 :context-window N
 :keep-recent-tokens N}
```

**Return:** `{:root-state-update f :return {:summary "..." :turns-to-remove [...]}}`

**Fallback:** when no handler registered, falls back to legacy compaction.

### `:session/ir-rebuild`

Dispatched during `:session/resume-loaded` after journal entries are loaded.

**Input:**
```clojure
{:session-id sid
 :journal-entries [journal-entry ...]}
```

**Handler:** rebuilds all IRs from journal entries. Runs synchronously during
resume so IRs are available for the first projection.

**Fallback:** when no handler registered, returns `{}`.

## Extension Event Hooks

New extension dispatch events for extensions that want to observe IR lifecycle:

- `ir_user_input_built` — fired after User Input IR is stored
- `ir_turn_extracted` — fired after Turn IR is extracted from journal entries
- `ir_conversation_projected` — fired after projection, with before/after message counts
- `ir_reply_processed` — fired after out-of-band reply processing
- `ir_compacted` — fired after IR-based compaction
- `ir_rebuilt` — fired after IR rebuild on session resume
