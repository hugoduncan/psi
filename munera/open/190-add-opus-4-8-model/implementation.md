# Implementation Notes — 190-add-opus-4-8-model

---

## Design review pass — 2026-05-30

**Ambiguities found:**

1. **`/speed` scope argument contradiction** — Part 2 step 8 specifies the command takes exactly one arg (`normal`|`fast`), but Part 2 step 11 references `/speed fast project` as a user-facing invocation implying a scope token. The command spec and the persistence prose are inconsistent. Does `/speed` accept an optional scope token (like `/model`) or not?

2. **`:runtime/agent-set-speed-mode` effect — no execute-effect! handler** — Part 2 step 3 adds the effect type to `dispatch_schema.clj`, but no corresponding `execute-effect!` multimethod in `dispatch_effects.clj` is specified, and no `agent/set-speed-mode-in!` in `agent-core` is mentioned. The parallel for `:runtime/agent-set-thinking-level` includes all three. Either this effect is unneeded (speed-mode is read from session-data, not agent-core) and should be removed from the design, or the missing handler/agent-core function must be added.

3. **`speed: "fast"` Anthropic beta header** — The design describes this as a "research preview" but does not specify whether a beta header is required (as with `interleaved-thinking-beta`, `prompt-caching-beta`, etc.). The `beta-header` function must be updated if a beta string is required; omitting this would cause 400 errors in production.

4. **`:fast` semantic mismatch across providers** — Anthropic `speed: "fast"` = higher throughput, premium pricing. OpenAI `service_tier: "flex"` = lower priority, cheaper. The design acknowledges the difference in prose but maps them to the same `:fast` value. The user-facing meaning of `/speed fast` is ambiguous: is it "faster" (Anthropic) or "cheaper/lower-priority" (OpenAI)?

5. **`:xhigh` → `"highest"` fallback mechanism unspecified** — The effort table says `:xhigh` maps to `"highest"` (when supported; else `"high"` with warning), but the architecture section says "no special retry logic is needed." There is no design for how the warning is emitted: the 400 error surface returns an error to the user, not a transparent fallback with a warning. The table and the architecture section contradict each other.

6. **Compaction preservation mechanism for `:mid-system` entries** — Part 4 step 11 states mid-system messages "must be preserved across compaction boundaries" but does not specify the mechanism. `rebuild-messages-from-entries` calls `entry->message`, which returns `nil` for `:mid-system` (not handled). Post-compaction, the agent message list is rebuilt from `entry->message` results, so `:mid-system` entries would be silently dropped. Either `entry->message` must handle `:mid-system`, or the design must explain an alternative preservation path.

7. **`journal->provider-messages` vs `agent-messages->ai-conversation` dual-path for `:mid-system`** — Part 4 step 4 modifies `append-msg` in `conversation.clj` (handles `"system"` role in agent message maps), and step 10 modifies `journal->provider-messages` (projects `:mid-system` journal entries into `{:role "system" ...}` maps). These two steps are complementary and both needed, but the design does not make their interaction explicit. The implementation must ensure the projected message format from step 10 is compatible with the `append-msg` handler added in step 4.

---

## Ambiguity follow-up — 2026-05-30

Completed all newly added ambiguity follow-up items in `design-steps.md` by refining `design.md` only.

Decisions recorded:
- `/speed` accepts optional scope syntax: `<mode> [session|project|user]`, matching the existing `/model` persistence pattern.
- No `:runtime/agent-set-speed-mode` effect belongs in scope; speed mode is request-built from canonical session data, unlike thinking/model agent-core mirrors.
- Anthropic fast mode requires beta header token `fast-mode-2026-02-01`; step 6 now explicitly includes wiring that token through `beta-header` / `request-headers` alongside `speed: "fast"`.
- `/speed fast` is defined as selecting the provider's alternate non-default throughput tier, with provider-specific help/docs required because Anthropic and OpenAI semantics differ.
- `:xhigh` adaptive effort always sends `"highest"`; unsupported-provider 400s surface directly, with no retry/fallback in this slice.
- Mid-system compaction preservation is concretized via `compaction.clj/entry->message` returning a provider-style `{"role": "system", ...}` shape for `:mid-system` entries.
- The exact `journal->provider-messages` → `append-msg` contract is now documented as `{:role "system" :content [{:type :text :text ...}]}`.
