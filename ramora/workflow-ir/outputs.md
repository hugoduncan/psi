# Workflow IR — Outputs

The IR separates execution from output surfaces.

Each step may expose step-local outputs by logical output key.

Illustrative common output keys:

- invoke step: `:data`, `:summary`, `:result`
- session step: commonly `:final-llm-reply`, `:transcript`, `:result`, plus any declared structured output keys
- delegate step: no first-cut step-local outputs except explicitly declared boundary outputs such as `:handoff`
- LLM judge: judge-local structured output keys under the judge's own `:outputs` map for transition evaluation only

The `:outputs` map should describe what logical output keys exist for a step or
judge and, at minimum, give runtime a canonical local meaning for each key.

The exact internal value of each `:outputs` entry may evolve, but the key-space should be stable for runtime reference and validation.

If a local `:yields` form names an output key (for example `{:type :text :text :final-llm-reply}` or `{:type :data :data :data}`), that output key must be declared in the same step's `:outputs` map at the normalized IR boundary.

## Structured output specs

A structured output spec is an output entry with a structured source and schema
contract. The normalized IR accepts these sources for this slice:

- `:session/structured-output` on session-step `:outputs`
- `:judge/structured-output` on LLM-judge `:outputs`

Normalized structured output spec shape:

```clojure
{:source structured-output-source
 :mode :structured
 :schema-id schema-id
 :schema-version schema-version
 :schema malli-schema
 :json-schema json-schema-map
 :strategy-preference :provider-native
 :fallback :prompted-json
 :require-provider-native? false
 :on-invalid? invalid-policy}
```

Invalid structured-output handling depends on the structured output source. For
session-step structured outputs, the first implementation's minimum invalid
policy is fail-fast: if `:on-invalid` is omitted, runtime treats it as
`{:action :fail-fast}`. For LLM judge structured outputs, invalid generated
structured output is retried by default up to the built-in judge retry limit using
judge retry feedback; each retry preserves the original structured-output
options/schema. Unsupported structured output still fails immediately with
`:unsupported-structured-output` rather than retrying. Explicit `:on-invalid`
retry/repair policy beyond this built-in judge retry behavior may be added only
when proven by tests.

Cardinality is constrained in the normalized IR: a session step must not contain
more than one `:source :session/structured-output` entry, and an LLM judge must
not contain more than one `:source :judge/structured-output` entry. Compiler or
IR validation should reject multiple structured entries clearly. One raw
model/judge response maps to one structured envelope for the one declared key.

Structured workflow outputs use two schema contracts. `:schema` is the Malli
contract used by workflow runtime for local coercion and validation. `:json-schema`
is the explicit provider/request contract passed under provider-neutral
`:structured-output` options to turn execution; workflow runtime does not infer
JSON Schema from Malli. Omitted `:strategy-preference` defaults to
`:provider-native`, omitted `:fallback` defaults to `:prompted-json`, and
`:require-provider-native? true` forbids fallback. `:fallback :none` also forbids
fallback. If a structured spec opts into provider-native request shaping but has
no `:json-schema`, execution fails with `:missing-json-schema` before generation.
If fallback is forbidden and the resolved model/transport cannot provide native
structured output, execution fails with `:unsupported-structured-output` rather
than silently degrading to prose.

For `:prompted-json`, the AI adapter injects schema-guided JSON-only
instructions into the outbound provider request, and the workflow runtime parses
the returned text as a single JSON value matching the declared JSON Schema. For
`:provider-native`, the provider is asked for a single schema-constrained JSON
value. Scalar, array, object, boolean, number, string, and `null` values are all
valid when the schema allows them. Sibling JSON fields are not promoted into
additional output keys; use a map/object schema plus downstream `:path`
references instead.

## Structured output runtime envelope

Execution records the resolved value behind a structured output key as a
canonical envelope. Downstream references must use only the validated
`:structured-output :value` when status is `:valid`.

Valid example:

```clojure
{:raw-output "..."
 :structured-output
 {:mode :structured
  :schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :strategy :provider-native
  :native-mechanism :openai/chat-completions-json-schema-response-format
  :source :openai/message-json
  :payload {"decision" "needs-work"
            "issues" []
            "confidence" 0.84}
  :raw-payload "... raw provider diagnostic payload when available ..."
  :status :valid
  :value {:decision :needs-work
          :issues []
          :confidence 0.84}}}
```

Invalid example:

```clojure
{:raw-output "..."
 :structured-output
 {:mode :structured
  :schema-id :psi.workflow/judge-review-result
  :schema-version 1
  :strategy :prompted-json
  :status :invalid
  :errors [{:message "missing required key"
            :path [:decision]}]
  :parsed-value {"issues" []}}}
```

The strategy field is observable runtime metadata. The first slice should record
at least one of:

- `:provider-native` — the provider/API accepted a structured-output schema or mode directly
- `:prompted-json` — the AI adapter injected a JSON-only/schema instruction, while workflow runtime parsed the result, coerced it to Malli-domain data, and validated it
- `:repair-parse` — the runtime performed an explicit repair parse attempt
- `:unsupported` — the runtime could not reasonably request the structured mode

For `:prompted-json`, the adapter-owned request shaping is limited to prompting
for a single JSON value that matches the declared schema; it does not make the
provider response trusted workflow data. Raw model text remains in
`:raw-output`; workflow runtime parses JSON and schema-guides it into
Malli-domain values before validation. Object keys may become keywords when the
schema expects map keys, and enum strings may become keyword enum values when
the declared enum contains the corresponding keyword. Non-object JSON values are
preserved and validated directly when the schema allows them. Coercion, parse,
or validation failure records `:status :invalid`, `:errors`, and
`:parsed-value` when a parsed value exists; it must not expose `:value`.

Reusable schemas are owned by workflow-runtime code. The first standard reusable
schema should live in `psi.workflow-runtime.structured-output-schemas` with id
`:psi.workflow/judge-review-result` and version `1`. Runtime/docs/tests should
refer to the id/version pair, and known reusable schema declarations should match
the exported Malli schema for that id/version.
