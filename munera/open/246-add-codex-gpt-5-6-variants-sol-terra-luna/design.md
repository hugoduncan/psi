# Add Codex-supported GPT-5.6 variants (sol/terra/luna) to OpenAI OAuth

## Goal

Make GPT-5.6 usable under OpenAI OAuth by adding the ChatGPT/Codex-supported
model variants `gpt-5.6-sol`, `gpt-5.6-terra`, and `gpt-5.6-luna` to the
built-in catalog and routing them through the existing OpenAI OAuth/Codex
transport.

## Context

Task 245 established that the literal `gpt-5.6` id is **not** supported on the
ChatGPT/Codex backend for a ChatGPT account, and correctly marked bare
`gpt-5.6` as unsupported under OpenAI OAuth
(`openai-oauth-unsupported-model-ids` in
`components/ai/src/psi/ai/model_registry.clj`).

Cross-checking the working reference implementation at `~/src/pi-mono`
confirms and completes that picture:

- pi-mono explicitly **removed the nonexistent bare `gpt-5.6` alias**; bare
  `gpt-5.6` is only reachable via the direct OpenAI API-key surface (272K
  short-context tier), not via ChatGPT OAuth.
- pi-mono **verified `openai-codex` support** for `gpt-5.6-sol`,
  `gpt-5.6-terra`, and `gpt-5.6-luna` (pi CHANGELOG entries for the GPT-5.6
  inherited-model release).

Mechanism (from pi-mono `packages/ai/src/api/openai-codex-responses.ts` and
`packages/ai/src/providers/openai-codex.ts`):

- Endpoint: `https://chatgpt.com/backend-api/codex/responses` (same backend our
  task 245 probe used).
- The request `model` field is sent **verbatim** as the catalog model id — no
  aliasing — so the catalog id itself must be a Codex-supported id.
- Request shape includes `store:false`, `stream:true`,
  `include:["reasoning.encrypted_content"]`, `instructions` = system prompt,
  `text.verbosity`, and `reasoning.effort` derived from a thinking-level map.
- Headers include `Authorization: Bearer <token>`, `chatgpt-account-id`
  (extracted from the token), `originator`, `OpenAI-Beta:
  responses=experimental`, and `session-id` / `x-client-request-id`.

Our psi OAuth/Codex transport already routes `gpt-5.5` through this backend via
`openai-oauth-codex-model-ids` (currently `#{"gpt-5.5"}`) and
`structured-output/with-openai-codex-transport`, so the transport itself is not
the gap. The gap is that the three Codex-supported GPT-5.6 variant ids are not
present in the catalog nor on the OAuth/Codex route.

## Problem

GPT-5.6 is effectively unavailable under OpenAI OAuth because the only exposed
GPT-5.6 catalog key is the bare `gpt-5.6` id, which the ChatGPT/Codex backend
rejects. The Codex-supported variant ids (`gpt-5.6-sol`, `gpt-5.6-terra`,
`gpt-5.6-luna`) are not modelled.

## Approach intent

Add the three variant models to the catalog and to
`openai-oauth-codex-model-ids` so OAuth-backed selection routes them through the
verified Codex transport, while leaving bare `gpt-5.6` unsupported under OAuth
(unchanged from task 245).

## Constraints

- Preserve working `gpt-5.5` OAuth/Codex behaviour.
- Preserve task 245 behaviour: bare `gpt-5.6` remains unsupported under OpenAI
  OAuth (it is only valid on the direct API-key surface, which is out of scope
  here).
- Follow the task-245 rule: **no model id goes on the Codex path without live
  backend evidence.** Each of `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`
  must be confirmed by a structured **streaming** probe against
  `https://chatgpt.com/backend-api/codex/responses` that reaches execution for
  that id before it is added to the OAuth/Codex route. Probes print structured
  status/body; they do not assert.
- Keep catalog entries and runtime transport overrides coherent across all
  model-selection surfaces (`/model`, RPC `set_model`, RPC picker, TUI picker,
  turn preflight), reusing the shared `model_registry.clj` join point and shared
  helpers rather than restating codex/capability literals per surface.
- Do not silently alias one variant to another; each catalog id is sent verbatim.

## Acceptance criteria

- `gpt-5.6-sol`, `gpt-5.6-terra`, and `gpt-5.6-luna` are present in the built-in
  model catalog and selectable across all model-selection surfaces.
- Under OpenAI OAuth, selecting any of the three variants routes through the
  ChatGPT/Codex transport with its id sent verbatim, exactly as `gpt-5.5` does.
- `gpt-5.5` OAuth/Codex behaviour is unchanged.
- Bare `gpt-5.6` remains unsupported under OpenAI OAuth with the existing
  uniform unsupported-model message (task 245 behaviour preserved).
- Tests cover runtime model resolution for OAuth-backed selection of each of the
  three variants (proving the verbatim Codex backend id) and confirm `gpt-5.5`
  and bare-`gpt-5.6` behaviour are unchanged.
- Each added variant is backed by structured streaming-probe evidence recorded
  in `implementation.md` (status/body per id).
- Changelog / user docs updated for the newly selectable models.

## Open questions / evidence to gather before planning

- Live streaming probe: confirm all three of `gpt-5.6-sol`, `gpt-5.6-terra`,
  `gpt-5.6-luna` reach execution on the ChatGPT/Codex backend for the current
  ChatGPT account. Intent is to support all three; if any id is rejected,
  record the negative evidence and exclude only that id.
- Catalog metadata per variant (context window, reasoning support,
  thinking-level map, pricing tier) — cross-reference the pi-mono inherited
  GPT-5.6 metadata (272K short-context tier default; Codex backend exposes a
  larger context window with long-context pricing).
