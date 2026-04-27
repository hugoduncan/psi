# 061 — Workflow step minimal projections

## Goal

Add the first constrained projection vocabulary for session-first workflow authoring.

## Context

Task 059 is the umbrella. This task extracts Phase 2 so explicit source selection becomes practically useful for structured results and reference shaping.

## Scope

In scope:
- support `:text`, `:full`, and `:path [...]` projection forms
- keep projections under the `:session`-first authoring model
- validate malformed paths and unsupported projection operators
- add tests for structured-field extraction and branch-safe non-adjacent source use

Out of scope:
- transcript-tail projections
- tool-output stripping
- arbitrary transformation logic

## Acceptance

- [ ] `:text`, `:full`, and `:path [...]` are supported projection forms
- [ ] Invalid projection forms fail validation clearly
- [ ] Structured field extraction works for named prior-step sources
- [ ] Backward compatibility remains intact
