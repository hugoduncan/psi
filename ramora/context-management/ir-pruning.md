# Context Management — IR Pruning

The extension is responsible for keeping the total IR store within bounds. Pruning manages the IR storage footprint on the session; it is separate from compaction, which manages what the LLM sees in the conversation projection.

## Pruning vs Compaction

| | Compaction | Pruning |
|---|---|---|
| **Purpose** | Reduce context sent to LLM | Reduce IR storage on session |
| **Trigger** | Context window threshold | IR store size threshold, turn completion, periodic |
| **Target** | Conversation projection | Raw IR instances and overflow files |
| **Reversibility** | Journal preserved; IRs can be rebuilt | IRs can be rebuilt from journal; overflow files may be deleted |

Compaction and pruning are often coordinated — when compaction removes old turns from the projection, pruning can remove the corresponding IRs from storage. But they can also operate independently.

## Pruning Policy

Pruning policy is declared in the extension configuration:

```clojure
{:ir-pruning
 {:max-turn-irs        50
  :max-project-events  100
  :max-total-ir-tokens 50000
  :prune-on            [:turn-complete :compaction :threshold]
  :retention
  {:turn-irs        :by-relevance
   :project-events  :by-age}}}
```

- `:max-turn-irs` — maximum number of turn IRs to retain
- `:max-project-events` — maximum number of project event IRs to retain
- `:max-total-ir-tokens` — maximum total token count across all IRs
- `:prune-on` — events that trigger pruning
- `:retention` — strategy for selecting which IRs to keep per type

## Pruning Triggers

Pruning runs on:

1. **Turn completion** — lightweight check after each turn; prunes if counts are exceeded
2. **Compaction** — coordinated with IR compaction; removes IRs for turns that were compacted away
3. **Threshold** — when total IR token count exceeds `:max-total-ir-tokens`
4. **Periodic** — configurable interval (e.g., every N turns)

## Pruning Strategy

The extension scores IRs by relevance and removes the lowest-scoring first:

1. **Recency** — newer IRs score higher
2. **User interaction** — turns with direct user input score higher
3. **Decisions** — turns containing decisions score higher
4. **Entity references** — IRs whose entities are referenced in recent turns score higher
5. **Actions** — turns with completed actions (commits, file changes) score higher

Before removing an IR:
- Update any cross-references (e.g., project events referencing the removed turn)
- Delete associated overflow files
- If the IR can be rebuilt from the journal, mark it as prunable rather than deleting (lazy rebuild on demand)

## Cross-Reference Maintenance

When a turn IR is pruned, the extension updates any project events that reference it:
- Replace `:ir/source-turn-id` with a summary of the turn (if available from the compaction summary)
- Or mark the event as `:ir/source-turn-pruned? true`

This ensures project events remain meaningful even after their source turns are pruned.

## Cleanup

The extension periodically scans `.psi/context/irs/` for orphaned overflow files (files whose parent IR no longer exists) and removes them. This prevents disk accumulation from crashed sessions or aggressive pruning.
