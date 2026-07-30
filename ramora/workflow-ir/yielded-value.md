# Workflow IR — Yielded Value

The IR distinguishes:

- step-local outputs addressable through `:output`
- the step's resulting value as a whole, modeled through `:yields`

## Yield forms

The first-cut yielded-value union is:

```clojure
{:type :data
 :data output-key}
```

```clojure
{:type :text
 :text output-key}
```

```clojure
{:type :error
 :reason keyword
 :message string
 :details? map}
```

For delegate steps, the normal behavior is compositional delegation:

```clojure
{:type :delegated}
```

Meaning:

- yield the called workflow's yielded value unchanged

This avoids redundantly restating the delegated workflow's yield form at every delegating callsite.

## Default yield rules

When omitted in authored input, compiler-side normalization must supply defaults before the runtime-owned IR validation boundary:

- invoke step -> `{:type :data :data :data}`
- session step -> `{:type :text :text :final-llm-reply}`
- delegate step -> `{:type :delegated}`

The IR validator treats missing `:yields` as invalid normalized IR rather than filling defaults locally.

This aligns with the target grammar's preferred defaults:

- deterministic/invoke steps yield their canonical machine-readable `:data`
- inline session steps yield their canonical terminal text output key `:final-llm-reply`
- delegated steps yield the callee's yielded value unchanged
