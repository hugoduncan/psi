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
- Implement Anthropic request construction for the documented JSON Schema output format/header on supported models/transports.
- Preserve the existing forced-tool implementation as a separate native mechanism or compatibility path rather than deleting it blindly.
- Keep prompted JSON fallback distinct from both native Anthropic mechanisms.
- Update built-in Anthropic model capability declarations to mark only verified/documented model ids as JSON Schema native-capable.
- Ensure unsupported or older Anthropic models do not silently claim JSON Schema native support.
- Expose precise strategy metadata naming the actual Anthropic mechanism used.
- Add focused tests for request shape, metadata, response extraction, fallback, and unsupported behavior.
- Perform a live smoke verification against Anthropic OAuth when credentials are available, without committing secrets.
- Update AI/model documentation and any task 170 dependency text that currently assumes Anthropic native equals forced-tool use.

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

## Model support policy

Use current Anthropic documentation and/or live verification to determine which built-in Claude model ids receive `:anthropic/json-schema-output` capability.

The initial expected supported set includes currently documented models such as:

- Claude Sonnet 4.5 / 4.6;
- Claude Opus 4.5 / 4.6 / 4.7;
- Claude Haiku 4.5;
- Claude Mythos Preview, if represented in Psi's model catalog.

Do not mark older Claude 3.x or unverified model ids as JSON Schema native-capable unless verified during implementation.

If Psi's catalog model ids differ from Anthropic's documented API ids, the implementation must map support using the actual ids in `components/ai/src/psi/ai/models.clj`.

## Request behavior

For a structured-output request against an Anthropic model whose resolved capability selects `:anthropic/json-schema-output`:

1. Require explicit request `[:structured-output :json-schema]` as task 169 does.
2. Add the documented Anthropic structured-output request fields for JSON Schema output.
3. Add the required Anthropic beta/header if the API requires one.
4. Do not add the synthetic forced structured-output tool unless the selected mechanism is `:anthropic/forced-tool-use`.
5. Do not inject prompted-JSON fallback instructions unless strategy selection chooses `:prompted-json`.
6. Record strategy metadata with `:strategy :provider-native` and `:native-mechanism :anthropic/json-schema-output`.

## Response behavior

For Anthropic JSON Schema output:

- extract the provider-returned structured value into the same top-level non-streaming result surface introduced by task 169:

```edn
[:structured-output :payload]
```

- include raw provider content sufficient for debugging failures;
- report `:source :anthropic/json-schema-output` or an equivalent precise source value;
- preserve local parse/coerce/validate as the downstream authority;
- for streaming, emit first-class `:structured-output-strategy` and `:structured-output-result` events matching task 169's stream contract.

## Acceptance

1. Capability schemas accept and normalize `:anthropic/json-schema-output` as a distinct native mechanism.
2. Built-in Anthropic model capabilities mark documented supported model ids as JSON Schema native-capable and leave unverified ids unsupported, fallback-only, or forced-tool-only as appropriate.
3. Anthropic non-streaming request construction for JSON Schema native support includes the documented output-format/schema fields and required beta/header.
4. Anthropic JSON Schema native request construction does not include synthetic forced-tool fields unless the forced-tool mechanism is selected.
5. Anthropic JSON Schema native responses expose structured payload metadata on the task-169 top-level `:structured-output` result surface.
6. Anthropic streaming JSON Schema native responses emit first-class `:structured-output-strategy` and `:structured-output-result` events.
7. Existing Anthropic forced-tool structured-output tests continue to pass or are deliberately migrated to the forced-tool-specific mechanism.
8. Prompted JSON fallback still works for fallback-only Anthropic capabilities and reports `:prompted-json`, not provider-native.
9. Missing JSON Schema still reports unsupported clearly without native fields or fallback prompt injection.
10. Focused tests cover JSON Schema native request shape, strategy metadata, response extraction, streaming event behavior, forced-tool separation, fallback, and unsupported paths.
11. A live Anthropic OAuth smoke test verifies the documented JSON Schema path when a token is available; results are recorded without secrets.
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

The test must assert the response is obtained through the native JSON Schema mechanism, not through prompted JSON or forced-tool fallback.

## Documentation requirements

Update user/developer documentation to state:

- Anthropic JSON Schema structured output is the preferred native mechanism for supported models.
- Forced/strict tool schema use is a separate native tool-use mechanism, not the only Anthropic structured-output path.
- Prompted JSON remains fallback only.
- Local runtime validation still gates downstream structured values.
- OAuth/API tokens must not be written into task files, commits, test fixtures, logs, or docs.
