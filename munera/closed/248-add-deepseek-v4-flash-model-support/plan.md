# Plan

## Current implementation strategy

The task ships DeepSeek `deepseek-v4-flash` through psi's existing custom
provider system and hardens the provider boundary behavior exercised while
integrating it. Work is organized by observable behavior rather than the
chronology of review passes.

### Slice 1 — custom-model configuration

- Extend the closed custom-model schema with the capabilities required by the
  documented DeepSeek model, including adaptive thinking and per-turn system
  message support.
- Preserve raw `env:` key specifications in the registry and tag custom-model
  origin explicitly so request-time resolution cannot confuse same-named
  custom and built-in providers.
- Parse-lock the documented DeepSeek `models.edn` example against the schema.

### Slice 2 — provider request safety

- Resolve credentials at request time and scope built-in environment/OAuth
  fallback to built-in model origin across Anthropic messages, OpenAI chat
  completions, and OpenAI Codex responses.
- Support explicitly keyless and custom-auth-header providers without allowing
  incidental headers to bypass missing-key failures.
- Centralize shared request support: origin classification, key resolution,
  keyless detection, once-only start emission, capture redaction, and the
  configurable nullable HTTP boundary used by tests.
- Keep request captures secret-safe with case-insensitive redaction.

### Slice 3 — request and response compatibility

- Shape DeepSeek adaptive-thinking requests through the Anthropic-compatible
  endpoint and document the supported effort, temperature, fast-mode,
  thinking-off, cache-cost, and HTTP-400 fallback constraints.
- Preserve provider errors, status, headers, retry metadata, usage, thinking,
  tool calls, and structured content consistently in streaming and
  non-streaming paths.
- Normalize all provider streams around the same invariants: `:start` precedes
  output/terminal events, exactly one terminal event occurs, no event or
  capture occurs after termination, and every open content/tool block is
  balanced before the terminal event.
- Use the real provider HTTP adapter through an explicit boundary; use the
  nullable scripted implementation for deterministic transport tests.

### Slice 4 — user-facing surfaces and formal specification

- Document the DeepSeek configuration and operational caveats in
  `doc/custom-providers.md`, and reflect the capability in README,
  `ramora/IMPLEMENTED.md`, and CHANGELOG.
- Keep `custom-providers.allium`, `anthropic-provider.allium`, and
  `openai-provider.allium` aligned with the configuration, credential,
  request-shaping, capture, retry, and terminal-event contracts.

## Verification gates

- Focused Scry suites cover custom-model parsing and all affected Anthropic and
  OpenAI provider request/stream paths.
- Full `bb test` is green; known unrelated timing-sensitive failures must be
  reproduced and recorded rather than attributed to this task.
- `clj-kondo` is clean on changed Clojure source and tests.
- Documentation's DeepSeek EDN block parses through the production schema.
- Live DeepSeek checks confirm both non-streaming request acceptance and the
  normal streaming sequence when `DEEPSEEK_API_KEY` is available.
- Spec guidance contains current contracts and rationale, not review history.

## Risks and controls

- Provider credential crossover is security-sensitive: origin and provider
  scoping are explicit and covered on all three transports.
- Anthropic-compatible implementations vary: compatibility retries are
  bounded to one transformed retry, and defensive EOF/error handling preserves
  terminal invariants for truncated or non-conforming streams.
- Stream lifecycle regressions can hang turns or duplicate effects: exact event
  vectors, terminal cardinality, capture guards, and open-block balancing are
  tested at the transport boundary.
