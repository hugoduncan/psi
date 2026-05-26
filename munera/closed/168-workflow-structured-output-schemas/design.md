# 168 — Workflow structured output schemas

## Intent

Make workflow steps that feed machine control flow produce validated structured data instead of prose-shaped output conventions, so workflows can branch, retry, hand off, and judge through explicit contracts rather than brittle text parsing.

The core principle is:

> Structured output is required where another machine step depends on the result; human-facing summaries may remain prose.

## Problem

Many workflows depend on model or judge output to control downstream execution. Examples include:

- classifying whether a bug reproduction succeeded;
- deciding whether to request more information or hand off to fixing;
- judging whether a design, implementation, or test review has actionable findings;
- extracting task intent, acceptance criteria, or handoff metadata;
- passing issue numbers, branch names, worktree paths, reproduction commands, or next actions between phases.

When these outputs are represented only as markdown, headings, bullets, or magic phrases, workflow control flow becomes fragile. A small wording change can break a downstream step, and runtime validation can only prove that text exists, not that the expected decision/data contract exists.

Psi already has architectural pressure toward explicit schemas, validation, replayable workflow execution, and machine-readable workflow state. Workflow output contracts should follow the same direction.

## Scope

This task designs and implements the first workflow structured-output contract surface.

In scope:

- Allow workflow model/session steps to declare an optional output schema.
- Allow workflow judge steps to declare an optional output schema.
- Ask capable providers for schema-constrained structured output when a schema is declared.
- Validate the returned structured value against the declared schema before exposing it to downstream workflow control flow.
- Persist both the raw model text and the validated/invalid structured-output result in workflow run state.
- Make downstream workflow steps able to reference validated structured output fields explicitly.
- Define validation-failure behavior for schema-constrained steps.
- Add focused tests for valid output, invalid output, retry/failure behavior, and downstream structured references.
- Document the authoring contract for structured step and judge outputs.

## Explicitly out of scope

- Requiring structured output for every workflow step.
- Removing human-facing prose summaries from workflow results.
- Proving that model judgments are true. Schema validation proves shape, not correctness.
- Redesigning the whole workflow grammar beyond the minimum needed contract additions.
- Migrating every existing workflow in this task.
- Exposing arbitrary internal step diagnostics as structured contracts.
- Treating provider-native structured output as mandatory for all models.
- Building a general schema registry UI.

## Acceptance

1. A workflow step can declare an output schema for machine-facing output.
2. A workflow judge can declare an output schema for machine-facing output.
3. Declared schemas are validated at runtime before structured values are made available to downstream control flow.
4. Workflow run state records:
   - the raw model/judge response;
   - the parsed structured value when parsing succeeds;
   - validation status and errors when parsing or validation fails.
5. Downstream workflow references can read fields from validated structured output without parsing prose.
6. Invalid structured output does not silently drive control flow as if valid.
7. Validation-failure policy is explicit and tested. The first implementation must support at least fail-fast behavior, and may support a bounded repair/retry policy if that is already natural in the workflow runtime.
8. Provider capability differences are represented explicitly. Provider-native structured output may be used when available; fallback prompting/parsing must be observable as a different strategy.
9. Focused tests prove representative valid and invalid outputs for both ordinary steps and judges.
10. Documentation explains when to use structured output: machine-dependent decisions and handoffs, not ordinary human-facing prose.

## Design constraints

- Use Malli-compatible schema representation unless there is an existing workflow schema convention that is more authoritative.
- Keep structured output optional at the step level.
- Store raw output even when structured parsing succeeds, so humans can inspect what the model actually said.
- Never treat schema validity as semantic truth. Judge schemas should require evidence fields where possible.
- Prefer one canonical structured-output result shape over provider-specific shapes leaking into workflow state.
- Preserve deterministic workflow replay as much as the current workflow runtime allows. Replayed runs should use persisted structured-output results rather than re-asking the model.
- Avoid compatibility shims that silently reinterpret old prose contracts as structured contracts.
- Prefer explicit references to structured data over path strings embedded in prompts.

## Concrete authored and IR contract shape

Workflow IR already uses step `:name` and step-local `:outputs` maps as the canonical machine-facing output surface. This task must therefore extend `:outputs`; it must not introduce a competing singular top-level `:output` key.

Authored workflow definitions declare structured output by adding an output entry under `:outputs` whose source is the execution-specific structured result. The normalized IR preserves the same logical output key and normalizes the schema contract into that output entry.

For session steps, the canonical first implementation shape is:

```edn
{:name "classify-reproduction"
 :type :session
 :contributions [{:type :template
                  :text "Classify the reproduction result."}]
 :outputs {:classification
           {:source :session/structured-output
            :mode :structured
            :schema-id :psi.workflow/bug-reproduction-classification
            :schema-version 1
            :schema
            [:map
             [:status [:enum :reproducible :not-reproducible :unclear]]
             [:summary :string]
             [:evidence [:vector :string]]
             [:commands-run [:vector :string]]
             [:next-action [:enum :request-more-info :handoff-to-fix :stop]]]}}
 :yields {:type :data
          :data :classification}}
```

An LLM judge declares the same structured contract under its judge-local `:outputs` map. Judge output keys are local to the judge result and may be used by the step's transition evaluation; they are not a separate prose-parsing mechanism and are not exported as parent step-local `:outputs`:

```edn
{:name "review-design"
 :type :session
 :contributions [...]
 :judge {:type :llm
         :contributions [{:type :template
                          :text "Review the task design for ambiguities."}]
         :outputs {:review
                   {:source :judge/structured-output
                    :mode :structured
                    :schema-id :psi.workflow/judge-review-result
                    :schema-version 1
                    :schema
                    [:map
                     [:decision [:enum :clear :needs-work :unclear]]
                     [:issues
                      [:vector
                       [:map
                        [:severity [:enum :blocking :minor]]
                        [:kind [:enum :ambiguity :inconsistency :missing-acceptance :scope-drift]]
                        [:description :string]
                        [:evidence :string]
                        [:suggested-change :string]]]]
                     [:confidence [:double {:min 0.0 :max 1.0}]]]}}}}
```

A human-facing text-only step remains valid by omitting structured output entries and using the existing text output surface:

```edn
{:name "summarize"
 :type :session
 :contributions [{:type :template
                  :text "Write a concise human summary."}]
 :outputs {:final-llm-reply {:source :session/final-llm-reply}}
 :yields {:type :text
          :text :final-llm-reply}}
```

If structured `:outputs` are omitted, existing text behavior remains the default for compatibility. `:output {:mode :text}` is not part of the chosen contract.

## Runtime result shape

Each schema-constrained output key should produce a canonical result shape equivalent to the value behind that step-local output key. For example, `{:from {:step "classify-reproduction" :output :classification}}` returns:

```edn
{:raw-output "..."
 :structured-output
 {:mode :structured
  :schema-id :psi.workflow/bug-reproduction-classification
  :schema-version 1
  :strategy :provider-native ; or :prompted-json / :repair-parse
  :status :valid
  :value {:status :reproducible
          :summary "..."
          :evidence ["..."]
          :commands-run ["bb test"]
          :next-action :handoff-to-fix}}}
```

For invalid output:

```edn
{:raw-output "..."
 :structured-output
 {:mode :structured
  :schema-id :psi.workflow/bug-reproduction-classification
  :schema-version 1
  :strategy :prompted-json
  :status :invalid
  :errors [...]
  :parsed-value nil}}
```

Downstream control flow must only consume `:value` when `:status` is `:valid`.

## Downstream reference contract

The chosen downstream reference form is the existing workflow source-spec shape: `{:from {:step step-name :output output-key} :path path}`. Downstream source refs address only the prior step-local `:outputs` surface. For session structured outputs, structured output is addressed by the logical output key declared in the parent step's `:outputs`; fields are addressed by `:path` into the validated structured `:value`.

Judge-local structured outputs are intentionally narrower in this slice: they are available to the judge result and parent step transition evaluation only. They are not implicitly promoted to the parent step's `{:step ... :output ...}` namespace, because the current source-ref grammar has no judge identifier and the parent step's output key space must remain explicit. If a later workflow step needs model/judge data, that data must be produced as a declared session-step structured output or promoted by an explicit future contract; prose parsing and implicit judge-output export are not part of this slice.

Example session structured-output reference:

```edn
{:from {:step "classify-reproduction"
        :output :classification}
 :path [:next-action]}
```

The reference resolver must satisfy these rules:

- references are to validated structured values, not raw text;
- references fail clearly if the source step did not declare structured output;
- references fail clearly if the source step's structured output is invalid;
- references fail clearly if the requested path is absent;
- prose parsing is not part of the reference mechanism.

## Provider strategy

Structured output should be represented as a runtime strategy, not assumed to be uniformly supported by every provider.

At minimum the runtime should distinguish:

- `:provider-native` — provider/API accepts a schema or structured-output mode directly;
- `:prompted-json` — runtime instructs the model to produce JSON/EDN matching the schema, then parses and validates;
- `:repair-parse` — runtime uses a repair attempt after malformed output, if this is implemented;
- `:unsupported` — provider/model cannot reasonably satisfy the requested structured mode.

Provider-native support is preferred when available. Fallback strategies must be visible in recorded workflow state so debugging can distinguish provider guarantees from prompt-only adherence.


## Prompted fallback wire format and coercion

Prompted fallback uses **JSON as the model-facing wire format** for the first implementation. EDN may appear in authored workflow definitions because workflow files are EDN/Clojure data, and Malli schemas may use keywords, but the model prompt should request a single JSON object for the structured value.

The runtime boundary is:

1. capture raw model/judge text unchanged as `:raw-output`;
2. extract/parse one JSON object from the response for structured outputs using `:strategy :prompted-json`;
3. coerce the parsed JSON value into the Malli-domain value before validation;
4. validate the coerced value against the declared Malli schema;
5. expose only a valid coerced `:value` to downstream references.

Coercion rules for this slice:

- JSON object string keys map to Clojure keyword keys when the target schema expects map keys.
- JSON strings map to Malli keyword enum values when the declared enum contains the corresponding keyword. For example, `"needs-work"` may coerce to `:needs-work` for `[:enum :clear :needs-work :unclear]`.
- JSON numbers, booleans, arrays, objects, and null map to ordinary Clojure numbers, booleans, vectors, maps, and nil before schema validation.
- Coercion is schema-guided; strings are not globally keywordized when the schema expects `:string`.
- Unknown enum strings, uncoercible values, missing required fields, wrong container shapes, and malformed JSON all produce an invalid structured-output result.

When parsing or coercion fails, the recorded structured-output envelope must include `:status :invalid`, `:strategy :prompted-json`, and `:errors` describing the parse/coercion/validation failure. If a JSON value was parsed but could not be coerced or validated, record it as `:parsed-value` for debugging and omit `:value`. Downstream control flow must not consume `:parsed-value`.

Provider-native structured output may return already-typed data or provider JSON depending on the API. The runtime must still normalize through the same canonical envelope and Malli validation boundary before exposing `:value`.

## Validation failure policy

The first implementation must not silently continue with invalid structured data.

The minimum acceptable policy is fail-fast:

- parse/validation failure marks the step failed;
- downstream steps depending on the structured value do not execute;
- the workflow run records raw output and validation errors.

A bounded retry/repair policy is acceptable only if explicit in the step contract, for example:

```edn
:outputs {:classification
          {:source :session/structured-output
           :mode :structured
           :schema ...
           :on-invalid {:action :retry
                        :max-attempts 2}}}
```

If retry/repair is included, tests must prove that attempts are bounded and that final failure is explicit.

## Standard first schema

The first standard schema for this slice is **workflow judge review result**. It is introduced as a reusable schema plus focused runtime tests and documentation examples. Existing workflows are not migrated in this slice; migration is explicit future work after the runtime surface is proven.

Schema intent:

- `:decision` — `:clear`, `:needs-work`, or `:unclear`;
- `:issues` — vector of findings with `:severity`, `:kind`, `:description`, `:evidence`, and `:suggested-change`;
- `:confidence` — bounded double between `0.0` and `1.0`.

The judge review result is preferred over bug reproduction classification because review loops are already common and currently rely on prose-shaped actionable/no-action judgments. A bug reproduction classification schema remains a follow-on candidate, not part of this first slice.

## Structured output cardinality and extraction semantics

This slice supports **at most one structured output key per session step** and **at most one structured output key per LLM judge**. A step or judge may still expose ordinary text/debug outputs where existing runtime contracts support them, but only one `:source :session/structured-output` or `:source :judge/structured-output` entry may be present in the same local `:outputs` map.

Rationale: one model/judge response is one structured contract boundary. Allowing several structured keys from the same response would require either splitting one JSON object across schemas, asking the provider for multiple independent schema outputs, or inventing key-level extraction rules. Those choices are useful later, but ambiguous for the first implementation.

For prompted JSON fallback, the model-facing response must therefore be a single JSON object representing the value for the one declared structured output key. The runtime parses that object, coerces it through the declared schema, validates it, and records the canonical envelope behind that output key. It must not infer additional structured outputs from sibling JSON fields.

For provider-native structured output, the runtime requests one schema-constrained object for the one declared structured output key, then normalizes the provider result through the same canonical envelope and Malli validation boundary. Provider APIs that support only one response-format/schema per request map naturally to this rule.

If an authored session step or LLM judge declares more than one structured output entry in its local `:outputs` map, the IR/compiler validation must reject it with a clear error. Authors who need multiple machine-facing values should group them into fields of one Malli `[:map ...]` schema and use downstream `:path` references into that validated value.

## Reusable schema ownership and export surface

Reusable workflow structured-output schemas are owned by the workflow runtime, not individual workflow files or documentation examples. The first implementation should introduce a small code namespace dedicated to reusable structured-output schemas, for example:

```clojure
psi.workflow-runtime.structured-output-schemas
```

That namespace should export the first standard schema as ordinary data plus lookup metadata:

```clojure
(def judge-review-result-schema-id :psi.workflow/judge-review-result)
(def judge-review-result-schema-version 1)
(def judge-review-result-schema
  [:map
   [:decision [:enum :clear :needs-work :unclear]]
   [:issues
    [:vector
     [:map
      [:severity [:enum :blocking :minor]]
      [:kind [:enum :ambiguity :inconsistency :missing-acceptance :scope-drift]]
      [:description :string]
      [:evidence :string]
      [:suggested-change :string]]]]
   [:confidence [:double {:min 0.0 :max 1.0}]])
```

Runtime, docs, and tests should refer to this schema by `:schema-id :psi.workflow/judge-review-result` and `:schema-version 1`. The inline Malli schema in an authored workflow or normalized IR must match the exported schema when it claims that id/version. The first slice may enforce this with direct equality for known reusable schemas or with a registry lookup that returns the schema for id/version before validation.

Schema ownership rules:

- `psi.workflow-runtime.structured-output-schemas` owns reusable schema ids and versioned Malli schema values.
- Workflow IR owns the declaration shape (`:schema-id`, `:schema-version`, `:schema`).
- Structured-output parsing/validation owns coercion and canonical envelopes.
- Documentation examples may inline schemas for readability, but must identify the same id/version and stay synchronized with the exported code schema.
- Future schema versions add new id/version entries; they do not mutate the meaning of version `1` in persisted workflow runs.

## Relationship to delegated handoffs

This task complements, but does not replace, delegated workflow handoff contracts.

Structured step output is the local mechanism for a model/judge step to produce validated data. Delegated workflow exports may later use this mechanism internally or expose a structured `:handoff`, but this task should not broaden into a full delegated terminal contract redesign unless that is already required by the current workflow IR.

## Testing requirements

Focused tests should cover:

- a text-mode step remains accepted and behaves as before;
- a structured step with valid output stores raw and structured valid result;
- a structured judge with valid output stores raw and structured valid result;
- malformed model output records parse/validation errors;
- schema-valid but semantically negative judge output can still drive a negative branch through explicit data;
- invalid structured output does not drive downstream branch selection;
- downstream references read structured fields from validated output;
- downstream references fail on missing path, invalid source output, or non-structured source step;
- provider strategy is recorded for at least provider-native or prompted fallback paths, depending on what the implementation supports in this slice.

## Documentation requirements

Update workflow authoring documentation to explain:

- when to use structured entries in `:outputs`;
- when to leave a step as text;
- how raw output, parsed output, and validation errors are stored;
- how downstream steps reference structured fields;
- why schema validation does not prove model correctness;
- provider-native versus prompted fallback behavior.

## Non-goals and risks

### False determinism

A valid schema only proves that the shape is valid. It does not prove that a judge was correct. Schemas for reviews and classifications should include evidence fields to make claims inspectable.

### Over-schematization

Exploratory planning, synthesis, and human-facing explanation steps should not be forced into rigid schemas unless another machine step depends on their result.

### Compatibility drift

Existing prose-based workflows may continue to work, but new structured references must not silently parse old prose outputs. Migration should be explicit.

### Schema evolution

Schemas that become reusable should carry `:schema-id` and `:schema-version` so persisted workflow runs remain debuggable as schemas evolve.
