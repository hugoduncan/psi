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

## Proposed authoring shape

The exact syntax may be adjusted to match current workflow IR conventions, but the design should converge on this conceptual shape:

```edn
{:id :classify-reproduction
 :type :session
 :prompt "Classify the reproduction result."
 :output
 {:mode :structured
  :schema-id :psi.workflow/bug-reproduction-classification
  :schema-version 1
  :schema
  [:map
   [:status [:enum :reproducible :not-reproducible :unclear]]
   [:summary :string]
   [:evidence [:vector :string]]
   [:commands-run [:vector :string]]
   [:next-action [:enum :request-more-info :handoff-to-fix :stop]]]}}
```

A judge step should use the same output contract surface rather than a separate ad hoc mechanism:

```edn
{:id :review-design
 :type :judge
 :prompt "Review the task design for ambiguities."
 :output
 {:mode :structured
  :schema-id :psi.workflow/design-review
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
   [:confidence [:double {:min 0.0 :max 1.0}]]]}}
```

A human-facing text-only step remains valid:

```edn
{:id :summarize
 :type :session
 :prompt "Write a concise human summary."
 :output {:mode :text}}
```

If `:output` is omitted, existing text behavior should remain the default for compatibility unless current workflow IR already requires explicit outputs.

## Runtime result shape

Each schema-constrained step should produce a canonical result shape equivalent to:

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

The design should provide one explicit way to reference validated structured output fields. Conceptually:

```edn
{:from {:step :classify-reproduction
        :structured [:next-action]}}
```

or, if current IR has an established output-ref shape, the equivalent should be:

```edn
{:from {:step :classify-reproduction
        :output :structured
        :path [:next-action]}}
```

The final implementation should choose the form that best aligns with current workflow IR, but it must satisfy these rules:

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

## Validation failure policy

The first implementation must not silently continue with invalid structured data.

The minimum acceptable policy is fail-fast:

- parse/validation failure marks the step failed;
- downstream steps depending on the structured value do not execute;
- the workflow run records raw output and validation errors.

A bounded retry/repair policy is acceptable only if explicit in the step contract, for example:

```edn
:output {:mode :structured
         :schema ...
         :on-invalid {:action :retry
                      :max-attempts 2}}
```

If retry/repair is included, tests must prove that attempts are bounded and that final failure is explicit.

## Standard first schemas

This task should introduce at least one realistic standard schema used by a test or migrated example. Good first candidates are:

1. **Workflow judge review result**
   - decision: `:clear`, `:needs-work`, or `:unclear`
   - findings/issues with severity, kind, evidence, and suggested change
   - confidence

2. **Bug reproduction classification**
   - status: `:reproducible`, `:not-reproducible`, or `:unclear`
   - evidence
   - commands run
   - next action

The judge review result is the preferred first standard schema because many existing workflows use judge-like control loops where prose-shaped actionable/no-action output is especially brittle.

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

- when to use `:output {:mode :structured ...}`;
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
