# Metrics Extension

## Intent

An extension that observes system activity and maintains usage counters for registered capabilities — tools, skills, commands, workflows, and deterministic operations. Counters persist across sessions so that usage patterns are visible over time.

## Problem Statement

Psi has no visibility into which capabilities are actually used, how often they succeed or fail, or how much they cost in tokens. This makes it hard to identify unused registrations, error-prone tools, or token-expensive workflows. The information exists transiently in the dispatch event log and extension event bus, but nothing aggregates or persists it.

## Constraints

- **Extension boundary**: must be a standard extension (init via `api` map), not a core component modification.
- **Event-driven**: must observe existing events (`tool_call`, `tool_result`, `session_turn_finished`, dispatch events) — no new hooks required in core.
- **Persistence**: counters must survive process restarts. Storage mechanism should be simple (EDN file in workspace `.psi/` directory or similar).
- **Scoping**: metrics should be scoped to a workspace (project). User-global aggregation is out of scope for the initial version.
- **Read surface**: metrics must be queryable — at minimum via a deterministic operation (`metrics/summary` or similar) and a slash command (`/metrics`).
- **Minimal overhead**: counting and persistence must not observably slow tool execution or turn completion.
- **Schema**: the metrics data shape must be explicit (malli schema) so consumers can rely on it.

## Success Criteria

1. Extension subscribes to relevant events and increments counters for: tool invocations (by tool name), tool errors (by tool name and error reason), workflow executions (by workflow id), command invocations (by command name), skill activations (by skill name), and token usage (by session, aggregated input/output/cache tokens).
2. Counters persist to disk and are restored on extension init.
3. A deterministic operation (`metrics/summary`) returns the current counter state.
4. A slash command (`/metrics`) renders a human-readable summary.
5. Metrics data conforms to an explicit malli schema.
6. No modifications to existing core components are required.
