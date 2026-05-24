# 171 — Anthropic native JSON Schema structured output

## Intent

Update Psi's Anthropic structured-output support to use Anthropic's native JSON Schema structured-output API where supported, instead of treating synthetic forced tool use as the only provider-native Anthropic mechanism.

This is a follow-up to task 169. Task 169 implemented structured-output capability selection and Anthropic provider-native support through forced tool use with `input_schema`. Newer Anthropic API capability evidence indicates supported Claude models can constrain ordinary assistant output directly with JSON Schema structured outputs.

## Problem

Task 169's Anthropic capability model currently says Anthropic structured output is provider-native via forced synthetic tool use:

- add a synthetic tool with `input_schema`;
- force `tool_choice` to that synthetic tool;
- extract `tool_use.input` as the structured payload;
- hide the synthetic tool from ordinary tool-call surfaces.

That remains a useful schema-enforced mechanism, but it is not the full Anthropic structured-output surface. Anthropic now documents a native JSON Schema output mechanism for supported models, using a structured output format/header, where the model response itself is constrained to the JSON Schema.

If Psi continues to model Anthropic native support only as forced tool use, workflows and callers cannot select or test the stronger/direct JSON output path, and capability metadata will mislead future task 170 workflow integration.

## Scope

In scope:

- Add an Anthropic native JSON Schema structured-output mechanism to the model capability vocabulary.
- Implement Anthropic request construction for the documented JSON Schema output format/header on supported models/transports, including a provider-owned non-streaming execution path for Anthropic Messages.
- Preserve the existing forced-tool implementation as a separate native mechanism or compatibility path rather than deleting it blindly.
- Keep prompted JSON fallback distinct from both native Anthropic mechanisms.
- Update built-in Anthropic model capability declarations to mark only verified/documented model ids as JSON Schema native-capable.
- Ensure unsupported or older Anthropic models do not silently claim JSON Schema native support.
- Expose precise strategy metadata naming the actual Anthropic mechanism used.
- Add focused tests for request shape, metadata, response extraction, fallback, and unsupported behavior.
- Perform a guarded live smoke verification against Anthropic's provider seam when credentials are available, without committing secrets. The concrete task-171 seam is the Anthropic provider option/env API-key path (`:api-key` or `ANTHROPIC_API_KEY`); an Anthropic OAuth token may be exercised only when it is supplied to that same provider seam as the effective `:api-key`/bearer token. Task 171 does not implement or require a separate OAuth resolver integration for the live smoke.
- Update the exact documentation targets named in this task: `components/ai/README.md` (`## Structured output capabilities` and `### Anthropic` provider-support bullets), `doc/custom-providers.md` (`## Structured output capability` native mechanism bullets), and task 170's dependency/out-of-scope wording in `munera/open/170-workflow-provider-native-structured-output/design.md`.

Out of scope:

- Migrating workflow runtime to consume structured-output capabilities. That remains task 170.
- Removing local runtime validation after provider-native output.
- Implementing schema generation from Malli/domain schemas. Callers must still provide explicit JSON Schema unless a later task changes that boundary.
- Broadly redesigning all tool-use handling.
- Committing, logging, or persisting OAuth tokens or API secrets.

## Required capability model

Anthropic structured-output capabilities should distinguish at least:

```edn
{:strategy :provider-native
 :native-mechanism :anthropic/json-schema-output}
```

for the direct JSON Schema output-format API, and:

```edn
{:strategy :provider-native
 :native-mechanism :anthropic/forced-tool-use}
```

for the existing synthetic forced-tool schema path.

Prompted fallback remains non-native:

```edn
{:strategy :prompted-json
 :fallback-used? true}
```

The adapter must not report `:provider-native` unless the outbound Anthropic request actually includes the selected provider-native schema constraint.

If a model or user-model capability can express only one `:native-mechanism`, that mechanism is the selected provider-native path. Built-in models that support JSON Schema output should choose `:anthropic/json-schema-output` as their default because it constrains ordinary assistant output directly. Forced-tool compatibility is preserved by models or user-model overrides whose capability declares `:native-mechanism :anthropic/forced-tool-use`. Task 171 does not add a per-request override; a later task may add explicit mechanism preference if workflows need both paths from the same model entry.

## Model support policy

Use current Anthropic documentation and/or live verification to determine which built-in Claude model ids receive `:anthropic/json-schema-output` capability.

The current Psi catalog in `components/ai/src/psi/ai/models.clj` should be assigned as follows unless implementation-time documentation or live smoke proves otherwise:

| Catalog key | API id | Structured-output capability |
| --- | --- | --- |
| `:claude-3-5-sonnet` | `claude-3-5-sonnet-20241022` | `:anthropic/forced-tool-use` native only; no JSON Schema output claim |
| `:claude-3-5-haiku` | `claude-3-5-haiku-20241022` | `:anthropic/forced-tool-use` native only; no JSON Schema output claim |
| `:sonnet-4` | `claude-sonnet-4-20250514` | `:anthropic/forced-tool-use` native only unless verified for JSON Schema output during implementation |
| `:opus-4` | `claude-opus-4-20250514` | `:anthropic/forced-tool-use` native only unless verified for JSON Schema output during implementation |
| `:sonnet-4.5` | `claude-sonnet-4-5` | `:anthropic/json-schema-output` native |
| `:opus-4.5` | `claude-opus-4-5` | `:anthropic/json-schema-output` native |
| `:sonnet-4.6` | `claude-sonnet-4-6` | `:anthropic/json-schema-output` native |
| `:opus-4.6` | `claude-opus-4-6` | `:anthropic/json-schema-output` native |
| `:haiku-4.5` | `claude-haiku-4-5` | `:anthropic/json-schema-output` native |
| `:opus-4.7` | `claude-opus-4-7` | `:anthropic/json-schema-output` native |

Do not mark older Claude 3.x or unverified model ids as JSON Schema native-capable unless verified during implementation.

If Psi's catalog model ids differ from Anthropic's documented API ids, the implementation must map support using the actual ids in `components/ai/src/psi/ai/models.clj`.

## Execution boundary

Task 171 must add an Anthropic provider non-streaming execution path rather than limiting non-streaming work to helper-only extraction tests. The provider map should expose `:execute` for Anthropic Messages, and `psi.ai.core/execute-response` for an Anthropic model should return the same top-level shape used by OpenAI: `{:assistant-message ... :structured-output ...}` on success or `{:type :error ...}` on provider/runtime failure.

The non-streaming path should share the Anthropic request construction and structured-output strategy selection rules with streaming, but it must send a non-streaming Messages request (`:stream` absent or false according to the Anthropic API requirement) and parse the ordinary Messages response body directly. It must not emulate non-streaming by consuming the streaming API in task 171.

This task does not require adding non-streaming execution for providers or APIs other than Anthropic Messages, and it does not change workflow runtime consumption; task 170 still owns workflow integration.

## Request behavior

For a structured-output request against an Anthropic model whose resolved capability selects `:anthropic/json-schema-output`:

1. Require explicit request `[:structured-output :json-schema]` as task 169 does.
2. Add this Anthropic Messages request field at the top level:

   ```edn
   :output_format
   {:type "json_schema"
    :name (structured-output-name request)
    :schema (:json-schema request)
    :strict true}
   ```

   `:strict` follows the normalized request `:strict?` value and defaults true.
3. Add `structured-outputs-2025-11-13` to the comma-separated `anthropic-beta` header. Preserve existing beta values for OAuth, prompt caching, and thinking; do not duplicate beta tokens.
4. Do not add the synthetic forced structured-output tool unless the selected mechanism is `:anthropic/forced-tool-use`.
5. Do not inject prompted-JSON fallback instructions unless strategy selection chooses `:prompted-json`.
6. Record strategy metadata with `:strategy :provider-native` and `:native-mechanism :anthropic/json-schema-output`.

For a structured-output request whose selected mechanism is `:anthropic/forced-tool-use`, keep the task-169 synthetic tool shape exactly: append a tool named `psi_structured_output__{structured-output-name}` with `:input_schema (:json-schema request)` and force `:tool_choice {:type "tool" :name tool-name}`. That request must not include `:output_format` or the structured-output beta solely for JSON Schema output.

## Response behavior

For Anthropic JSON Schema output, the provider response is expected as ordinary assistant content constrained by `output_format` rather than as a synthetic `tool_use` block.

Non-streaming extraction:

- concatenate text content blocks from the assistant message;
- parse the concatenated text as a JSON object;
- expose the same top-level result surface introduced by task 169:

```edn
{:assistant-message ...
 :structured-output
 {:strategy :provider-native
  :native-mechanism :anthropic/json-schema-output
  :source :anthropic/json-schema-output
  :raw-payload "{...}"
  :payload {...}}}
```

- preserve the raw provider content/message in the normal response/capture path for debugging;
- if parsing fails or the value is not an object, still attach `:structured-output` with `:raw-payload`, `:source :anthropic/json-schema-output`, and a terse parse-failure marker such as `:parse-error? true`, but omit `:payload`; downstream local validation remains authoritative and must not treat the value as trusted.

Streaming extraction:

- emit `{:type :structured-output-strategy :structured-output strategy}` before provider content, as task 169 does;
- pass through ordinary text stream events unchanged;
- accumulate text deltas for JSON Schema native output;
- on `message_delta` stop or `message_stop`, emit:

```edn
{:type :structured-output-result
 :structured-output
 {:strategy :provider-native
  :native-mechanism :anthropic/json-schema-output
  :source :anthropic/json-schema-output
  :raw-payload "{...}"
  :payload {...}}}
```

- if streaming parse fails, emit the same event with `:raw-payload` and `:parse-error? true` but without `:payload`.

## Acceptance

1. Capability schemas accept and normalize `:anthropic/json-schema-output` as a distinct native mechanism.
2. Built-in Anthropic model capabilities mark documented supported model ids as JSON Schema native-capable and leave unverified ids unsupported, fallback-only, or forced-tool-only as appropriate.
3. Anthropic non-streaming request construction for JSON Schema native support includes the documented output-format/schema fields and required beta/header.
4. Anthropic JSON Schema native request construction does not include synthetic forced-tool fields unless the forced-tool mechanism is selected.
5. Anthropic JSON Schema native non-streaming execution is supported through the Anthropic provider `:execute` path and exposes structured payload metadata on the task-169 top-level `:structured-output` result surface.
6. Anthropic streaming JSON Schema native responses emit first-class `:structured-output-strategy` and `:structured-output-result` events.
7. Existing Anthropic forced-tool structured-output tests continue to pass or are deliberately migrated to the forced-tool-specific mechanism.
8. Prompted JSON fallback still works for fallback-only Anthropic capabilities and reports `:prompted-json`, not provider-native.
9. Missing JSON Schema still reports unsupported clearly without native fields or fallback prompt injection.
10. Focused tests cover JSON Schema native request shape, strategy metadata, response extraction, streaming event behavior, forced-tool separation, fallback, and unsupported paths.
11. A guarded live Anthropic smoke test verifies the documented JSON Schema native path when an effective credential is available through the Anthropic provider `:api-key`/`ANTHROPIC_API_KEY` seam; OAuth is acceptable only when supplied through that same seam, and results are recorded without secrets.
12. Documentation explains Anthropic's three distinct paths: JSON Schema native output, forced/strict tool schema use, and prompted JSON fallback.

## Testing notes

Prefer deterministic provider-unit tests for request/response shape. Add one live smoke path guarded by environment/session credentials so CI and ordinary test runs do not require Anthropic access.

The live smoke should use a small schema, for example:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["ok", "label"],
  "properties": {
    "ok": {"type": "boolean"},
    "label": {"type": "string"}
  }
}
```

Live smoke invocation and skip policy:

- test namespace/name should make the opt-in nature obvious, e.g. `psi.ai.providers.anthropic-live-structured-output-test`;
- run only when `PSI_LIVE_ANTHROPIC_STRUCTURED_OUTPUT=1` and an effective Anthropic credential is available through the provider's concrete credential seam: explicit test option `:api-key` or `ANTHROPIC_API_KEY` as consumed by `psi.ai.providers.anthropic/resolve-api-key`;
- if the available credential is an Anthropic OAuth bearer token, pass it through that same `:api-key`/`ANTHROPIC_API_KEY` seam and let the provider's OAuth-token detection choose `Authorization` plus OAuth beta headers;
- do not add or depend on a new live-test OAuth resolver path in task 171; if no concrete token reaches the provider seam, record OAuth live smoke as skipped/unavailable rather than blocked;
- default model id is the catalog `:sonnet-4.5` (`claude-sonnet-4-5`) unless implementation-time docs identify a better lowest-cost supported JSON Schema model;
- skipped runs should report a terse skip reason such as `missing PSI_LIVE_ANTHROPIC_STRUCTURED_OUTPUT=1` or `missing Anthropic credential at :api-key/ANTHROPIC_API_KEY provider seam`;
- successful notes in `implementation.md` must include date, model key/id, native mechanism, schema name, credential seam used (`:api-key` option, `ANTHROPIC_API_KEY`, or OAuth token via provider api-key seam), and pass/fail/skip, but never token values, authorization headers, or raw secret-bearing request maps.

The test must assert the response is obtained through the native JSON Schema mechanism, not through prompted JSON or forced-tool fallback.

## Documentation requirements

Update these exact user/developer documentation targets:

1. `components/ai/README.md`
   - In `## Structured output capabilities`, add `:anthropic/json-schema-output` as the preferred Anthropic native mechanism for supported models.
   - In the same section, keep `:anthropic/forced-tool-use` as a separate native tool-use mechanism and keep prompted JSON as fallback only.
   - In `## Provider Support` / `### Anthropic`, mention JSON Schema structured output for supported Claude 4.5+ catalog entries and forced tool-use structured output for older/compatibility entries.
2. `doc/custom-providers.md`
   - In `## Structured output capability`, add a native mechanism bullet for Anthropic-compatible providers that implement Anthropic Messages `output_format` JSON Schema plus the structured-output beta/header.
   - Keep the forced-tool bullet separate so custom providers do not infer forced tool use is the only Anthropic native path.
3. `munera/open/170-workflow-provider-native-structured-output/design.md`
   - In `## Dependencies`, change the Anthropic dependency wording from generic task-169 Anthropic support to task 169 plus task 171 support: OpenAI native support from task 169, Anthropic forced-tool native support from task 169, and Anthropic JSON Schema native output from task 171 or equivalent.
   - In `## Explicitly out of scope`, replace "task 169" as the sole owner of Anthropic adapter support with "task 169/task 171 provider-adapter capability work".

All documentation updates must state:

- Anthropic JSON Schema structured output is the preferred native mechanism for supported models.
- Forced/strict tool schema use is a separate native tool-use mechanism, not the only Anthropic structured-output path.
- Prompted JSON remains fallback only.
- Local runtime validation still gates downstream structured values.
- OAuth/API tokens must not be written into task files, commits, test fixtures, logs, or docs.
