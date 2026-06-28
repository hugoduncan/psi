# Context Management — Extension Design

The context manager extension owns:

1. **IR builders** — functions that construct each IR type from raw data
2. **IR projector** — function that projects IRs into provider-neutral conversation entries
3. **IR processor** — function that processes replies out-of-band
4. **IR compactor** — function that compacts IRs when context is full
5. **State IR materialisers** — functions that build Session State IR and
   Project State IR from live data
6. **IR rebuilder** — function that rebuilds all IRs from journal on resume

## Extension Init

```clojure
(defn init [api]
  ;; Register dispatch handlers via on!
  (api/on "session/ir-build-user-input" build-user-input-handler)
  (api/on "session/ir-process-reply" process-reply-handler)
  (api/on "session/ir-compact" compact-handler)
  (api/on "session/ir-rebuild" rebuild-handler)

  ;; Register the one synchronous projection hook
  (api/register-ir-projector! project-conversation))
```

## Projection Strategy

The projector decides the provider-neutral conversation that will later be
adapted for the selected provider. A typical strategy:

1. **System layer**: Session State IR + Project State IR as system messages
2. **Recent turns**: Last N turns projected from their semantic IRs — entities,
   decisions, claims, and actions rendered as structured context
3. **Summarised history**: Older turns replaced with a compact summary derived
   from their IRs (preserving key decisions and entities)
4. **Active context**: Any mid-system messages, steering messages, follow-ups

The projector can use different strategies based on context pressure:
- **Low pressure**: include more turns with full semantic detail
- **High pressure**: aggressive summarisation, keep only decisions and key entities
- **Critical**: keep only the last turn + essential state + active decisions

## Projection Contract

The projector receives the base provider-neutral conversation produced from the
journal and returns either `nil` (use the base conversation unchanged) or:

```clojure
{:conversation provider-neutral-conversation
 :metadata {:strategy :low-pressure|:high-pressure|:critical
            :raw-turns [turn-id ...]
            :ir-turns [turn-id ...]
            :summarised-turns [turn-id ...]
            :dropped-turns [turn-id ...]}}
```

Projection must obey these rules:

1. Preserve the current user message for the in-flight turn.
2. Emit a valid provider-neutral conversation according to psi's internal
   conversation schema.
3. Preserve tool-call/tool-result adjacency when including raw tool segments.
4. Never include only one side of a tool-call/tool-result pair. If a raw segment
   cannot be included validly in the neutral conversation, replace the whole
   segment with a summary or structured context entry.
5. Preserve active system/developer steering messages unless they are explicitly
   superseded by a newer message according to existing session rules.
6. Stay provider-neutral: do not encode provider-specific roles, message quirks,
   token-accounting rules, or request shapes in the context-manager projection.
   Existing provider-specific projection/adaptation runs after this step.
7. Return `nil` on timeout, invalid projection, unavailable context manager, or
   any uncertainty about neutral-conversation validity.

The projection metadata is for introspection and debugging only; core must not
depend on it for semantic decisions.

## Provider-Specific Adaptation Boundary

Context-manager projection runs **before** provider-specific request projection.
The order is:

```
journal
  → provider-neutral base conversation
  → context-manager projection (provider-neutral)
  → existing provider-specific projection/adaptation
  → provider request
```

The context manager must not know whether the final request is for OpenAI,
Anthropic, a local model, or another provider. It may shape semantic context,
retention, summaries, and neutral conversation entries; it must not emit
provider-specific message maps or compensate for provider-specific quirks.

## Reply Processing

Out-of-band processing after each reply:

1. **Read journal entries** for the completed turn
2. **Extract Turn IR conservatively**: parse entities, relationships, claims,
   questions, decisions, and actions from the raw conversation content; when in
   doubt, preserve a raw summary/reference rather than inventing structure
3. **Extract project events**: file changes, task progress, decisions made
4. **Update session state IR**: turn count, context usage, phase
5. **Identify memory candidates**: detect possible mementum memories/knowledge,
   but only propose or stage them unless the run is operating under an explicitly
   approved autonomous extraction protocol
6. **Re-materialise project state IR** if events warrant

This runs in a background thread and does not block the user. Initial
implementations should prefer context preservation over aggressive reduction:
keep recent raw turns, summarise older turns, and expose projection metadata so
bad extraction can be inspected and corrected.

## Compaction Strategy

IR-based compaction is more surgical than the current approach:

1. **Score each turn** by relevance (recency, decisions made, entities introduced,
   user interaction)
2. **Group consecutive turns** into thematic clusters
3. **Summarise low-scoring clusters** into compact IR summaries preserving key
   decisions and entities
4. **Drop turns** below a threshold (with summary preservation)
5. **Consolidate related entities and decisions** across turns

The extension can use the structured semantic IR data to produce better
summaries than raw message text — preserving what matters (decisions, entities,
relationships) and discarding conversational noise.

## IR Rebuild on Resume

When a session is resumed, the extension rebuilds all IRs from journal entries:

1. **Scan journal** for turn boundaries (user message → assistant reply chain)
2. **Extract Turn IRs** for each turn since the last compaction boundary
3. **Build Session State IR** from current session data
4. **Load Project State IR** from cache (or re-materialise if stale)
5. **Enforce size budgets** and create overflow files as needed

This is a one-time synchronous cost at session start. The extension can
optimise by only rebuilding IRs since the last compaction entry in the journal.
