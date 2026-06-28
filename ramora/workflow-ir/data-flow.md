# Workflow IR — Data Flow

The IR uses one shared source-spec language for:

- invoke args
- source contributions
- template vars
- delegated context
- delegated prompt-string template vars
- judge args where applicable

Current runtime ownership note:

- canonical runtime materialization of these refs/specs now lives in `components/agent-session/src/psi/agent_session/workflow_source_resolution.clj`
- compiler/authoring seams may translate authored syntax into IR-compatible refs/specs, but they should not re-encode divergent runtime resolution semantics

## Source spec

Illustrative shape:

```clojure
{:from source-ref
 :path [:issues]}
```

or:

```clojure
{:from source-ref
 :projection {:type :tail :turns 4 :tool-output false}}
```

A source-spec must not contain both `:path` and `:projection` in the first cut.

## Source refs

Illustrative source refs:

```clojure
:workflow-input
:workflow-original
{:step "discover" :output :data}
{:step "discover" :yield :data}
```

These align with the workflow grammar's shared data-reference model and form
the runtime boundary.

## Meaning of refs

- `:workflow-input` -> current workflow invocation input value
- `:workflow-original` -> current workflow invocation original request surface
- `{:step s :output k}` -> step-local output surface `k` from prior step `s`
- `{:step s :yield f}` -> yielded-value field `f` from prior step `s`

For session structured outputs, `{:step s :output k}` addresses the logical
parent step output key that declared `:source :session/structured-output`. A
source-spec `:path` is resolved against the validated structured `:value`, not
against `:raw-output`, `:parsed-value`, or prose. Resolution must fail clearly
when the source output is missing, non-structured, invalid, or lacks the requested
path.

Judge-local structured outputs declared with `:source :judge/structured-output`
are available to the judge result and transition evaluation only in this slice.
They are not valid downstream `{:step s :output k}` refs unless a future explicit
promotion/export contract adds a named parent step output. The current source-ref
grammar has no judge identifier, so implicit judge-output promotion would make
the parent step output namespace ambiguous.

Current implementation note:

- canonical normalized IR currently admits `:workflow-input`, `:workflow-original`, prior-step `:output`, and prior-step `:yield`
- `:workflow-runtime` is not a canonical IR `:from` source-ref and target-authored definitions using it are rejected at validation time

## Contributions

Session construction and delegated context reuse normalized contribution items.

### Source contribution

```clojure
{:type :source
 :from source-ref
 source-projection?}
```

### Template contribution

```clojure
{:type :template
 :text string
 :vars {string source-spec}*}
```

Rules:

- author order is preserved
- template vars use the same source-spec language as invoke args
- unresolved vars are first-cut errors
- template rendering must produce deterministic text from resolved values

## Delegated prompt-string

Delegate `:prompt-string` may be represented in IR either as:

- a literal final string, or
- a template contribution-like renderer that will deterministically render to a final string before invocation

Recommended first-cut shape keeps authored intent visible until boundary rendering:

```clojure
:string
```

or

```clojure
{:type :template
 :text string
 :vars {string source-spec}*}
```

Before actual delegated execution, runtime should materialize this to a final string.
