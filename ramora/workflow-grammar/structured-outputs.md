# Workflow Grammar — Structured Outputs

Session steps may declare machine-facing structured outputs under the existing
step-local `:outputs` map. LLM judges may declare judge-local structured
outputs under their own `:outputs` map. Delegate `:outputs` remain supported
for handoff data; `outputs` is no longer delegate-only.

Structured outputs use `:source :session/structured-output` for ordinary
session step output and `:source :judge/structured-output` for LLM judge
output. Both forms require `:mode :structured` plus a Malli-compatible schema
contract (`:schema-id`, `:schema-version`, and `:schema`). Provider-native and
prompted-JSON request shaping also require an explicit `:json-schema`; the
runtime does not derive JSON Schema from Malli. Authors may set
`:strategy-preference :provider-native`, `:fallback :prompted-json` or `:none`,
and `:require-provider-native? true`. Omitted strategy defaults to native-first
with prompted-JSON fallback. A session step may have at most one session
structured-output entry, and an LLM judge may have at most one judge
structured-output entry. Authors who need multiple machine-facing values should
group them as fields inside one structured map schema and address fields with
`:path`.

## Downstream References

Downstream references to session structured outputs use the normal source-spec shape:

```clojure
{:from {:step "classify-reproduction" :output :classification}
 :path [:next-action]}
```

The path is resolved against the validated structured `:value`, never by
parsing prose. If the source output is missing, non-structured, invalid, or the
path is absent, resolution fails clearly.

Judge structured outputs are judge-local in this slice. They are available to
the judge result and transition evaluation, but are not implicitly promoted into
the parent step's `{:step ... :output ...}` namespace. A later step that needs
the same data must consume an explicitly declared session structured output or a
future explicit promotion/export contract, not a hidden judge-output ref.

## Prompted Fallback

Prompted fallback means the AI adapter injects schema-guided JSON-only
instructions into the provider request for one JSON value matching the declared
JSON Schema for the one declared structured-output key. Workflow runtime then
parses the returned text and schema-guided coercion maps JSON object keys and
enum strings into the declared Malli-domain values when the value is an object,
and also validates scalar, array, boolean, number, string, and `null` values when
the schema allows them. Raw text is retained even when coercion and validation
succeed. Provider-native structured output likewise requests one
schema-constrained JSON value and records it behind the single declared
structured-output key. If native support is required or fallback is `:none`, an
unsupported resolved model/transport fails with `:unsupported-structured-output`
instead of retrying as prose. Authors who need multiple named fields or
`:path`-addressable subvalues should use a map/object schema for that one JSON
value.
