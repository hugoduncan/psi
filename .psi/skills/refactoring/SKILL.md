---
name: refactoring
description: guide for refactoring: use when asked to move, extract, consolidate, or rename code.
---

Aim for a clean refactor:

You may us a compatibility shim, but it must be removed before completion.
Tests should reflect the refactored code.
Minimise the namespace dependency tree; aim for a tree rather than a more general graph.
Maximise the orthogonality.
