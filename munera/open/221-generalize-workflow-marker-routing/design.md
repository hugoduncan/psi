# 221 — Generalize Workflow Marker Routing

## Intent

Remove workflow-specific route vocabularies from generic workflow runtime code by replacing the specialized proof-sync and validation-capture deterministic operations with one parameterized exact-marker routing primitive.

Workflow runtime/code should expose generic mechanisms only. Authored workflow definitions should own workflow-specific marker labels, allowed route labels, topology, proof artifact names, and business rules.

## Problem

Task 220 hardened the simplification workflows, but introduced workflow-specific implementation details into generic workflow routing code:

- `components/agent-session/src/psi/agent_session/workflow/routing.clj` defines:
  - `proof-sync-routes`
  - `validation-capture-routes`
  - `parse-proof-sync-disposition-routing`
  - `parse-validation-capture-disposition-routing`
- `components/agent-session/src/psi/agent_session/workflow/core.clj` registers specialized operations:
  - `workflow/proof-sync-disposition-routing`
  - `workflow/validation-capture-disposition-routing`
- Both simplification workflow EDNs call those specialized operations.

That works functionally, but violates the architecture boundary now recorded in `AGENTS.md`:

> workflow runtime/code owns generic execution, parsing, and routing mechanisms only; workflow-specific topology, route labels, marker names, proof artifacts, and business rules belong in authored workflow definitions/prompts and their tests, not in generic workflow implementation code.

The runtime should not know that `PROOF_SYNC_ROUTE` supports `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, and `BOOKKEEPING_FIXED_POINT`, nor that `VALIDATION_CAPTURE_ROUTE` supports `IMPLEMENTATION_REPAIR` and `TERMINAL_STOP`. Those are authored workflow policies.

## Scope

### In scope

- Introduce one generic deterministic operation for exact marker routing.
- Move workflow-specific marker labels and allowed route labels into `.psi/workflows/*.edn` operation args.
- Update both simplification workflows:
  - `.psi/workflows/reduce-incidental-complexity.edn`
  - `.psi/workflows/reduce-architectural-complexity.edn`
- Update workflow-loader/content-lock tests for tasks 209, 218, and 220 as needed.
- Update deterministic routing parser tests so they cover generic exact-marker parsing instead of workflow-specific wrapper functions.
- Update built-in operation registration smoke tests.
- Add a CHANGELOG entry because registered operation ids are user-visible through `/operations`, `/operation`, and `psi-tool operation`; update docs only if existing docs mention the old or new built-in workflow operation ids.
- Preserve all existing workflow behaviour and route topology.

### Out of scope

- Changing proof-sync, validation-capture, coverage, diff-gate, or terminal-stop semantics.
- Changing route labels used by the simplification workflows.
- Changing `PASS_STATUS` routing.
- Changing `workflow/munera-open-task-path-routing` unless implementation discovers it also violates the generic boundary. It is acceptable as a reusable Munera task-path primitive.
- Adding new workflow-specific deterministic operations.

## Required design

### Generic operation

Register a single deterministic operation:

```text
workflow/exact-marker-routing
```

It accepts args shaped like:

```clojure
{:text <string>
 :marker-label <string>
 :allowed-routes [<string> ...]}
```

Behaviour:

- Parse `:text` line-by-line.
- Find exactly one route marker line matching:

```text
<MARKER_LABEL>: <ROUTE_TOKEN>
```

- Marker line must:
  - start at column 0,
  - have exactly one colon immediately after the marker label,
  - have exactly one space after the colon,
  - contain exactly one all-caps route token with underscores allowed,
  - have no trailing text.
- Surrounding prose and `PASS_STATUS` lines are allowed and ignored.
- Ordinary prose merely mentioning the marker label is not a marker candidate.
- Marker-like malformed lines are errors, not ignored as prose.
- Missing, duplicate, malformed, or unsupported markers return tagged `:error` results with diagnostic details.
- Operation argument validation happens before parsing marker candidates.
- A valid marker returns:

```clojure
{:status :ok
 :data <route-token>
 :summary <route-token>}
```


### Exact-marker argument contract

`workflow/exact-marker-routing` must reject invalid operation args explicitly instead of treating them as missing markers or empty allowed routes.

Valid args are:

- `:text` — required string. An empty string is valid input and produces `:missing-route-marker` because no marker candidate exists.
- `:marker-label` — required string matching `^[A-Z_]+$`; lowercase, digits, hyphens, spaces, blanks, and non-strings are invalid.
- `:allowed-routes` — required non-empty vector of distinct strings, each matching `^[A-Z_]+$`; lowercase, digits, hyphens, spaces, blanks, non-strings, duplicate values, empty vectors, lists/sets, and nil are invalid.

Invalid args return exactly one operation result shaped like:

```clojure
{:status :error
 :reason :invalid-route-marker-args
 :message "workflow/exact-marker-routing args are invalid"
 :details {:errors [<arg-error> ...]}}
```

Argument errors should be accumulated in `:details :errors` so callers can fix all malformed inputs from one diagnostic. Required error entries:

- Missing `:text`: `{:field :text :reason :missing-text}`.
- Non-string `:text`: `{:field :text :reason :non-string-text :value <value>}`.
- Missing `:marker-label`: `{:field :marker-label :reason :missing-marker-label}`.
- Non-string `:marker-label`: `{:field :marker-label :reason :non-string-marker-label :value <value>}`.
- String `:marker-label` not matching `^[A-Z_]+$`: `{:field :marker-label :reason :invalid-marker-label :value <value>}`.
- Missing `:allowed-routes`: `{:field :allowed-routes :reason :missing-allowed-routes}`.
- Non-vector `:allowed-routes`: `{:field :allowed-routes :reason :non-vector-allowed-routes :value <value>}`.
- Empty vector `:allowed-routes`: `{:field :allowed-routes :reason :empty-allowed-routes}`.
- Route entry not matching the route-token contract: `{:field :allowed-routes :reason :invalid-allowed-route :index <index> :value <value>}`.
- Duplicate route token: `{:field :allowed-routes :reason :duplicate-allowed-route :value <route> :indices [<first-index> <duplicate-index> ...]}`.

Parser and operation tests must prove invalid args return this tagged result without throwing, including missing/non-string text, invalid marker labels, empty/non-vector allowed routes, duplicate routes, and invalid route tokens.

### Marker candidate precedence and diagnostics

After argument validation succeeds, the parser classifies every marker candidate line before deciding the result. A marker candidate is any line that starts at column 0 with the marker label followed by `:` or whitespace-before-`:`, or any leading-whitespace line whose trimmed-left form starts with that same marker attempt. Ordinary prose merely mentioning the marker label without a marker colon is not a candidate.

Result precedence is:

1. No marker candidates → `:missing-route-marker`.
2. More than one marker candidate → `:ambiguous-route-marker`, regardless of whether the candidates are valid, malformed, unsupported, or mixed.
3. Exactly one valid supported candidate → `:ok` with the route token.
4. Exactly one unsupported candidate → `:unsupported-route-marker`.
5. Exactly one malformed candidate → `:malformed-route-marker`.

`:ambiguous-route-marker` diagnostics must include all candidate lines and per-candidate classification so mixed cases are actionable:

```clojure
{:details {:text <text>
           :marker-label <marker-label>
           :route-marker-lines [<line> ...]
           :route-marker-candidates [{:line <line>
                                      :kind :exact
                                      :route <route>}
                                     {:line <line>
                                      :kind :malformed
                                      :reason <malformed-reason>
                                      :value <optional-value>}
                                     {:line <line>
                                      :kind :unsupported
                                      :value <route>} ...]}}
```

Tests must cover valid+malformed, valid+unsupported, malformed+unsupported, multiple malformed/unsupported, and duplicate valid marker candidates; all multi-candidate cases must assert `:ambiguous-route-marker` and the complete candidate-line diagnostics rather than allowing malformed or unsupported candidates to win.

### Workflow EDN ownership

The simplification workflow EDNs must supply concrete marker policy through args.

For proof sync:

```clojure
{:type :invoke
 :operation "workflow/exact-marker-routing"
 :args {:text {:from {:step "proof-sync" :output :final-llm-reply}}
        :marker-label "PROOF_SYNC_ROUTE"
        :allowed-routes ["COVERAGE_REVIEW"
                         "VALIDATION_RECAPTURE"
                         "BOOKKEEPING_FIXED_POINT"]}}
```

For validation capture:

```clojure
{:type :invoke
 :operation "workflow/exact-marker-routing"
 :args {:text {:from {:step "validation-capture" :output :final-llm-reply}}
        :marker-label "VALIDATION_CAPTURE_ROUTE"
        :allowed-routes ["IMPLEMENTATION_REPAIR"
                         "TERMINAL_STOP"]}}
```

For incidental workflow, use the actual validation step name (`incidental-validation-capture`) but the same generic operation and workflow-owned label/route args.

### Runtime cleanup

Remove runtime-level workflow-specific definitions:

- `proof-sync-routes`
- `validation-capture-routes`
- `parse-proof-sync-disposition-routing`
- `parse-validation-capture-disposition-routing`
- operation registration for `workflow/proof-sync-disposition-routing`
- operation registration for `workflow/validation-capture-disposition-routing`

The runtime may keep generic helper functions such as:

- `parse-exact-marker-routing`
- `parse-pass-status-routing`
- `parse-munera-open-task-path-routing`

`parse-exact-marker-routing` should be public/testable and parameterized by marker label and allowed routes.

## Acceptance criteria

- `psi.agent-session.workflow.routing` contains no hard-coded proof-sync or validation-capture route vocabularies.
- Built-in workflow operation registration exposes `workflow/exact-marker-routing` and no longer exposes `workflow/proof-sync-disposition-routing` or `workflow/validation-capture-disposition-routing`.
- Both simplification workflows invoke `workflow/exact-marker-routing` for proof-sync and validation-capture disposition routing, passing marker label and allowed routes as authored EDN args.
- Workflow-specific route labels remain present only in workflow definitions/prompts and workflow-specific tests/docs, not in generic runtime code.
- Generic routing parser tests cover valid, missing, duplicate, malformed, unsupported, prose-mention, leading-whitespace, whitespace-before-colon, trailing-whitespace, same-line-extra-text, surrounding-prose, invalid args, and mixed-candidate precedence cases using arbitrary marker labels/routes.
- Workflow-loader/content-lock tests assert that each simplification workflow supplies the correct marker label and allowed route labels in `:args` to `workflow/exact-marker-routing`.
- Operation registration/invocation smoke tests prove `workflow/exact-marker-routing` is registered and works for at least one arbitrary marker/route example, and invalid-arg invocation returns `:invalid-route-marker-args` without throwing.
- CHANGELOG records the registered-operation surface change: new `workflow/exact-marker-routing` and removal of `workflow/proof-sync-disposition-routing` / `workflow/validation-capture-disposition-routing`; docs are updated only if they explicitly mention these built-in operation ids.
- Existing focused workflow-loader tests for task 209, 218, and 220 remain green after updating operation ids and args.
- Targeted `clj-kondo`, `clj-paren-repair`, workflow EDN read checks, and relevant focused Scry tests are green.

## Non-goals / constraints

- Do not add compatibility aliases for the old specialized operation ids unless a concrete backward-compatibility requirement is identified. These are internal workflow definitions in the same repository and should be updated together.
- Do not move simplification workflow policy into Clojure constants under a different name.
- Do not broaden runtime routing to arbitrary code execution or expression evaluation; it remains a pure marker parser.
- Do not make the parser infer route labels from workflow `:on` maps. The workflow author explicitly supplies allowed routes to the operation.

## Resolved design decisions

- `workflow/exact-marker-routing` validates operation args explicitly and returns `:invalid-route-marker-args` for malformed args; invalid args are never interpreted as ordinary missing-marker input.
- Marker labels and route tokens are protocol tokens restricted to `^[A-Z_]+$`.
- Multi-candidate marker replies always return `:ambiguous-route-marker` with all candidate lines and per-candidate diagnostics, even when the candidates mix valid, malformed, and unsupported lines.
- The old specialized operation ids are removed immediately, without compatibility aliases, because they encode workflow-specific policy in generic runtime code.
- Registered operation id changes are user-visible through `/operations`, `/operation`, and `psi-tool operation`; implementation must add a CHANGELOG entry. Existing docs must be inspected and updated only where they explicitly mention the old or new built-in workflow operation ids.
