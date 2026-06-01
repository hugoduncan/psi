# 188 — Native structured-output support for the ChatGPT/Codex endpoint

## Intent

Implement real schema-constrained structured-output support for the ChatGPT/Codex endpoint used by Codex-backed OpenAI execution, while preserving and strengthening schema usage in workflow control.

This task is intentionally narrow: it is about supporting schemas on the Codex endpoint, not about broader model-identity or auth-routing redesign.

## Problem

Review workflows such as `review-step` and `review-task-implementation` already declare structured outputs and expect tight loop control from small schemas such as:

- Malli: `[:enum "REPEAT" "DONE"]`
- JSON Schema: `{"type":"string","enum":["REPEAT","DONE"]}`

The current codebase already supports native structured output on OpenAI Chat Completions and models that path explicitly. But Codex-backed OpenAI execution is still modeled as fallback-only prompted JSON.

The relevant current reality is:

- with an API key, Codex CLI uses `https://api.openai.com/v1/responses`
- with ChatGPT login/OAuth, Codex CLI uses `https://chatgpt.com/backend-api/codex/responses`

For Psi, the narrow gap to close is the second path: the ChatGPT/Codex backend currently treated as fallback-only should support schemas if the backend actually supports them.

## Current code constraints

Authoritative current seams:

- `components/ai/src/psi/ai/models.clj`
  - several built-in models, including `:gpt-5.4` and multiple `*-codex` variants, are built in directly as `:openai-codex-responses`
  - built-in `:gpt-5.5` remains catalogued as `:openai-completions`
  - built-in capability assignment marks `:openai-codex-responses` models as fallback-only `openai-codex-fallback-capability`
  - built-in capability assignment separately marks selected `:openai-completions` models, including built-in `:gpt-5.5`, as native-capable Chat Completions models
- `components/ai/src/psi/ai/model_registry.clj`
  - runtime model resolution is more authoritative than the built-in catalog alone for this task
  - under OpenAI OAuth context, `resolve-runtime-model` reroutes `gpt-5.5` to `:openai-codex-responses`, sets the ChatGPT backend base URL, and replaces its structured-output capability with Codex fallback-only capability
  - therefore current structured-output behavior for `gpt-5.5` depends on transport/auth-resolved runtime model selection, not only on the static `models.clj` entry
- `components/ai/src/psi/ai/structured_output.clj`
  - Codex capability is explicit fallback-only `{:supported? true :strategies [:prompted-json] :native-mechanism nil}`
  - Chat Completions capability is modeled separately as native-capable
- `components/ai/src/psi/ai/providers/openai/codex_responses.clj`
  - Codex request shaping currently relies on prompted-JSON fallback instructions
  - Codex request construction requires a bearer token containing `chatgpt_account_id`, resolves the ChatGPT/Codex URL, and currently treats that resolved transport as prompted-JSON/fallback-only
  - Codex streaming emits structured-output strategy metadata but derives results from fallback parsing rather than a native provider contract
- `components/ai/src/psi/ai/providers/openai.clj`
  - Codex non-streaming `:execute` is not implemented
- `components/ai/test/psi/ai/model_registry_test.clj`
  - focused runtime-model tests already prove that `gpt-5.5` stays `:openai-completions` without OAuth context but resolves to `:openai-codex-responses` with OAuth context, carrying fallback-only Codex structured-output capability there
- `components/ai/test/psi/ai/providers/openai_structured_output_test.clj`
  - Codex tests currently assert fallback-only behavior and omission of native schema request fields

So the existing boundary is clear: Psi already has one OpenAI native structured-output path, but ChatGPT/Codex-transport execution is not yet one of them, including runtime-resolved Codex cases such as OAuth-routed `gpt-5.5`.

## Goal

Add true native schema support for the ChatGPT/Codex endpoint **if and only if the endpoint actually supports it**, using explicit capability declaration, exact request shaping, and exact result extraction.

## Non-goals

- redesigning model identity or auth routing;
- changing the existing OpenAI Chat Completions native structured-output path except where shared helpers need extension;
- weakening schemas or removing structured-output requirements from workflow control;
- pretending prompted-JSON fallback is native schema support.

## Design approach

### Capability-selection seam

The authoritative discriminator for this task is the resolved transport surface, not the broad provider family alone.

Specifically:

- `:api :openai-codex-responses` is necessary but not sufficient as the task boundary; it identifies the Codex provider code path, but by itself would be too coarse if a public OpenAI Responses transport were later added under the same family.
- the resolved request URL/transport remains the decisive native-capability boundary for this task:
  - ChatGPT/Codex OAuth transport resolves to `https://chatgpt.com/backend-api/codex/responses` and is the only transport in scope for possible native Codex structured-output support here;
  - public OpenAI API Responses transport (for example `https://api.openai.com/v1/responses`) is out of scope for this task and must not inherit ChatGPT/Codex native capability by accident.
- OAuth/account context is part of transport resolution evidence, not a separate public capability category: current Codex request construction already requires a bearer token containing `chatgpt_account_id`, and that requirement is what makes the ChatGPT/Codex transport reachable.

Therefore the runtime decision seam to name and preserve is:

1. resolve the concrete model/provider request path first (`:api`, base URL, and any auth-derived ChatGPT account requirement);
2. only after that resolution, assign or select the effective structured-output capability for execution/tests;
3. treat the ChatGPT/Codex transport capability as distinct from both OpenAI Chat Completions native capability and any future public OpenAI Responses capability.

In current code terms, implementation/tests for this task should anchor capability selection at the resolved Codex request seam owned by `components/ai/src/psi/ai/providers/openai/codex_responses.clj` and its URL/auth resolution behavior, with `components/ai/src/psi/ai/model_registry.clj` runtime model resolution treated as part of the same authoritative surface. Static `components/ai/src/psi/ai/models.clj` capability annotation must remain coherent with that transport-specific contract, but it is not sufficient on its own because OAuth routing can transform built-in `:openai-completions` catalog entries such as `gpt-5.5` into effective `:openai-codex-responses` runtime models. If the existing coarse built-in `:api :openai-codex-responses` annotation remains, it must still be justified by the fact that all current models on that API resolve only to the ChatGPT/Codex OAuth transport; the task must not generalize beyond that fact.

### Approach A — preferred: verified native Codex capability

Live discovery on 2026-05-29 verified that `https://chatgpt.com/backend-api/codex/responses` accepts native schema-constrained structured output for the streaming transport Psi uses.

Observed positive contract:

- requests must currently set `"stream": true`; attempts with `"stream": false` returned `400` with `{"detail":"Stream must be set to true"}`
- the accepted native schema surface is Responses-style `text.format`, not Chat Completions-style `response_format`
- a successful probe returned `200` and echoed the schema contract under:

```json
"text": {
  "format": {
    "type": "json_schema",
    "name": "probe_result",
    "schema": {...},
    "strict": true
  },
  "verbosity": "low"
}
```

Observed negative contract:

- Chat Completions-style `response_format` is rejected on this endpoint with `400` and `{"detail":"Unsupported parameter: response_format"}`

Therefore this task should implement a first-class Codex native mechanism using the verified Responses-style request surface rather than prompted fallback.

That implementation should:

1. introduce a Codex-native structured-output capability/mechanism name unless the protocol is proven identical to an existing OpenAI native mechanism;
2. extend `codex_responses.clj` request construction to send the verified native schema fields under Responses-style `text.format`;
3. extend Codex streaming extraction to emit first-class `:structured-output-strategy` and `:structured-output-result` events sourced from the true native response shape;
4. treat Codex non-streaming `:execute` as a separate verified capability question, because the live probe currently showed `stream` is required and did not establish a supported non-streaming contract;
5. update Codex-backed built-in model capabilities such as `gpt-5.4` to advertise real native support rather than fallback-only;
6. add focused tests and retain guarded live verification for the request contract and result extraction contract.

### Approach B — if Codex native support is not available

If the endpoint does not support native schemas, keep the explicit fallback-only capability and do not claim provider-native support.

In that case, this task should still leave the boundary stronger than before:

1. add Codex-specific evidence so fallback-only status is explicit and justified rather than inherited assumption;
2. document clearly that the ChatGPT/Codex endpoint remains prompted-JSON-only;
3. add focused workflow/turn-runtime regression proof showing schema-preserving fallback behavior is explicit and inspectable.

This task must not blur the distinction between native schema support and prompted fallback.

## Scope

In scope:

- inspect and codify the real native structured-output capability of `https://chatgpt.com/backend-api/codex/responses`;
- implement native support only if verified;
- otherwise preserve fallback-only semantics explicitly and evidence them;
- add focused tests and, if feasible, guarded live smoke verification for Codex structured-output behavior;
- keep the Codex capability separate from the existing Chat Completions native capability;
- cover transport-resolved Codex runtime models generally, including OAuth-routed `gpt-5.5`, rather than only models statically catalogued as Codex in `models.clj`.

Out of scope:

- changing whether `gpt-5.5` is routed or not routed under OAuth;
- broad auth/model-selection redesign;
- claiming compatibility with undocumented request fields without evidence.

## Acceptance

1. There is one authoritative answer for the ChatGPT/Codex endpoint structured-output capability, and this task now records verified native streaming schema support using Responses-style `text.format` on `https://chatgpt.com/backend-api/codex/responses`.
2. Codex request construction, strategy selection, result extraction, and capability declarations are updated coherently for the verified native streaming contract.
3. Chat Completions-style `response_format` is not used on the ChatGPT/Codex endpoint, and tests/documentation make that distinction explicit.
4. If non-streaming Codex support is implemented, it is backed by separate verification; until then, the task remains explicit that live evidence currently supports only streaming native schema use and that `stream: false` returned `400` during discovery.
5. Focused tests cover Codex model capability assignment and Codex structured-output request/result behavior for the finalized capability outcome, including transport-resolved runtime model cases such as OAuth-routed `gpt-5.5` rather than only static built-in Codex catalog entries.
6. The existing OpenAI Chat Completions native structured-output path remains intact and clearly distinct.
7. Workflow structured-output schemas remain intact and no control-loop schema is weakened or removed.

## Risks

- The ChatGPT/Codex backend may expose a schema feature that differs subtly from public OpenAI API structured-output fields.
- The backend may support only streaming structured results.
- The backend may not support native schemas at all, in which case the real outcome is explicit fallback-only proof rather than implementation.

## Suggested implementation sequence

1. Reconfirm current Codex fallback-only capability and provider seams.
2. Inspect the ChatGPT/Codex request/response protocol and run guarded live probes for native schema support.
3. Decide native-capable vs fallback-only based on evidence.
4. Implement the smallest coherent capability/model/provider/test slice for that outcome.
5. Add a workflow-oriented regression proving the control-loop schema path behaves according to the finalized Codex capability.