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
- Update docs/CHANGELOG only if user-visible operation names or workflow guarantees are documented.
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
- A valid marker returns:

```clojure
{:status :ok
 :data <route-token>
 :summary <route-token>}
```

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
- Generic routing parser tests cover valid, missing, duplicate, malformed, unsupported, prose-mention, leading-whitespace, whitespace-before-colon, trailing-whitespace, same-line-extra-text, and surrounding-prose cases using arbitrary marker labels/routes.
- Workflow-loader/content-lock tests assert that each simplification workflow supplies the correct marker label and allowed route labels in `:args` to `workflow/exact-marker-routing`.
- Operation registration/invocation smoke tests prove `workflow/exact-marker-routing` is registered and works for at least one arbitrary marker/route example.
- Existing focused workflow-loader tests for task 209, 218, and 220 remain green after updating operation ids and args.
- Targeted `clj-kondo`, `clj-paren-repair`, workflow EDN read checks, and relevant focused Scry tests are green.

## Non-goals / constraints

- Do not add compatibility aliases for the old specialized operation ids unless a concrete backward-compatibility requirement is identified. These are internal workflow definitions in the same repository and should be updated together.
- Do not move simplification workflow policy into Clojure constants under a different name.
- Do not broaden runtime routing to arbitrary code execution or expression evaluation; it remains a pure marker parser.
- Do not make the parser infer route labels from workflow `:on` maps. The workflow author explicitly supplies allowed routes to the operation.

## Open questions

1. Should `workflow/exact-marker-routing` validate `:allowed-routes` is a non-empty vector of all-caps strings and return `:error` otherwise, or treat invalid args as no supported routes? Preferred: explicit `:error` for invalid operation args.
2. Should the generic operation accept `:marker-label` values with hyphens/lowercase, or restrict labels to `[A-Z_]+` for clarity? Preferred: restrict to `[A-Z_]+` because marker labels are protocol tokens.
3. Should old specialized operation ids be removed immediately or left as deprecated wrappers for one release? Preferred: remove immediately unless tests/docs reveal external use.
