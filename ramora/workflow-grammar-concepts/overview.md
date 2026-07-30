# Overview

The workflow grammar is a compact, EBNF-like documentation grammar for authored
workflow data. It is not intended to be a complete executable parser
specification.

This document explains the supported authoring model built around explicit step
`:type` values, contributions, delegated boundaries, and yielded-value
semantics.

The grammar separates workflow authoring into a small number of orthogonal concerns:

- control flow
- execution form
- session construction
- delegation
- data flow
- result surfaces
- yielded value
- templating

A workflow is a graph of named steps. Each step uses one execution form and may participate in control flow.
