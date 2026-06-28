# Model Selection Concepts

## Overview

Model selection is the mechanism for resolving a model either directly or through a query.

The grammar has two forms:

- concrete model id
- model query

A model query is broader than workflows. Workflows consume model selection through the `:model` field on session steps and judge session configuration, but the model query language is a project-wide artifact.

## Concrete model id

A concrete model id selects one explicit model.

Example:

```clojure
:model "gpt-5.4"
```

## Model query

A model query selects a model indirectly.

Example shape:

```clojure
:model {:type :model-query
        :prefer [...]
        :require [...]
        :fallback "gpt-5.4"}
```

The purpose of the query form is to allow reusable policy-oriented selection rather than hard-coding a single model id everywhere.

## Query clauses

### Prefer

`:`prefer` expresses soft selection preferences.

These preferences influence ranking but do not by themselves make a candidate valid.

### Require

`:`require` expresses hard constraints.

A candidate model must satisfy these constraints to remain eligible.

### Fallback

`:`fallback` provides a fallback model selection specification used if the preferred/required query path does not yield a usable result.

## Relationship to workflows

In the workflow grammar, `:model` accepts a `model-spec`.

That means a session step or judge session configuration can use either:

- a concrete model id
- a model query

The workflow grammar should reference model selection, not redefine it.
