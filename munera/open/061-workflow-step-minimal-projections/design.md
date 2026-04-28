# 061 — Workflow step minimal projections

## Goal

Add the first constrained projection vocabulary for session-first workflow authoring.

## Context

Task 059 is the umbrella. This task extracts Phase 2 so explicit source selection becomes practically useful for structured results and reference shaping.

## Scope

In scope:
- support `:text`, `:full`, and `:path [...]` projection forms
- keep projections under the `:session`-first authoring model
- layer projections on top of task-060 source-selection entries rather than replacing `{:from ...}`
- validate malformed paths and unsupported projection operators
- add tests for structured-field extraction and named prior-step non-adjacent source use

Out of scope:
- transcript-tail projections
- tool-output stripping
- arbitrary transformation logic

## Authoring shape

Task `061` should preserve task `060` source selection and add projection as a separate concern on the same entries.

Preferred shape:

```clojure
{:name "request-more-info"
 :workflow "gh-bug-request-more-info"
 :session {:input {:from {:step "reproduce" :kind :accepted-result}
                   :projection :text}
           :reference {:from :workflow-original
                       :projection :full}}
 :prompt "$INPUT"}
```

Projection semantics for this task:
- `:projection :text` selects the canonical text view for the selected source
- `:projection :full` selects the full structured value for the selected source
- `:projection {:path [...]}` selects a structured sub-path from the selected source
- `{:step "<step-name>" ...}` continues to refer to the author-facing step `:name`
- task `061` does not change task-060 source-selection semantics; it only adds projection semantics on top

## Acceptance

- [ ] `:projection :text`, `:projection :full`, and `:projection {:path [...]}` are supported forms
- [ ] Invalid projection forms fail validation clearly
- [ ] Structured field extraction works for named prior-step sources
- [ ] Backward compatibility remains intact
