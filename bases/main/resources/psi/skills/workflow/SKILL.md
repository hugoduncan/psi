---
name: workflow
description: Repository-specific guidance for understanding, creating, and updating psi workflows.
lambda: "λworkflow_work. {understand ∨ create ∨ update}(psi_workflow) → read(authority) ∧ inspect(example) ∧ align(grammar ∧ runtime ∧ tests)"
---

# Workflow

Use this skill when understanding an existing workflow, creating a new workflow, or updating an existing workflow in psi.

## Start with the authoritative docs

Read these first and prefer them over incidental examples:

- `doc/workflows.md`
- `doc/workflow-grammar.md`
- `doc/workflow-grammar-concepts.md`
- `AGENTS.md` — workflow/runtime/dispatch architecture guidance where relevant

## Where workflows live

Workflow definitions are discovered from `.psi/workflows/` in the project, plus the normal user/global workflow roots described in `doc/workflows.md`.

Authoring forms:

- `.md` — single-step prompt workflows
- `.edn` — multi-step orchestration workflows

Prefer the converged target-authored workflow grammar for new work:

- `:type :invoke`
- `:type :session`
- `:type :delegate`

Do not invent parallel workflow shapes when the existing grammar already covers the need.

## Canonical examples in this repository

Use current repository examples before inventing new patterns:

- `.psi/workflows/create-task-plan.edn` — representative multi-step planning/orchestration workflow
- `.psi/workflows/review-task-design.edn` — representative review/orchestration workflow
- `.psi/workflows/planner.md` — representative single-step prompt workflow
- `.psi/workflows/builder.md` — representative single-step prompt workflow

Also use `doc/workflows.md` for example-led guidance and current conventions.

## Authoring guidance

When changing a workflow:

- identify whether it is a single-step prompt workflow or a multi-step orchestration workflow
- preserve one clear purpose per file
- prefer explicit step names and explicit data flow
- use `:delegate` when calling another named workflow across a reusable boundary
- use `:session` when constructing an inline child session
- use `:invoke` for deterministic operation calls
- prefer explicit `:contributions`, `:outputs`, `:judge`, and `:on` surfaces over ad hoc prompting conventions
- keep authored references aligned with the documented source-spec model (`:workflow-input`, `:workflow-original`, prior step outputs/yields)

When creating a workflow:

1. read the grammar and concepts docs first
2. find the nearest existing workflow example with the same shape
3. author the smallest workflow that satisfies the task
4. prefer clear step boundaries over hidden coupling
5. verify with the relevant parser/compiler/runtime tests before treating the workflow as done

When updating a workflow:

1. read the current workflow file fully before editing
2. trace the workflow's prompt/data-flow/routing contract
3. inspect neighboring examples and tests that prove the same behavior class
4. update docs or companion prompt files when the workflow contract changes
5. reload and re-verify rather than assuming authored EDN or markdown is valid

## Key implementation seams

Workflow loading / authoring / compilation:

- `components/workflow-loader/src/psi/workflow_loader/parser.clj`
- `components/workflow-loader/src/psi/workflow_loader/compiler.clj`
- `components/workflow-loader/src/psi/workflow_loader/authoring_session.clj`
- `components/workflow-loader/src/psi/workflow_loader/authoring_routing.clj`

Representative workflow-loader tests:

- `components/workflow-loader/test/psi/workflow_loader/parser_test.clj`
- `components/workflow-loader/test/psi/workflow_loader/compiler_target_authoring_test.clj`
- `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`

Workflow runtime seams:

- `components/agent-session/src/psi/agent_session/workflow/bootstrap.clj`
- `components/agent-session/src/psi/agent_session/workflow/core.clj`
- `components/agent-session/src/psi/agent_session/workflow_execution.clj`
- `components/agent-session/src/psi/agent_session/workflow_judge.clj`

## Editing loop

Use the normal workflow authoring loop from `doc/workflows.md`:

- edit the workflow under `.psi/workflows/`
- reload with `/delegate-reload`
- verify through the narrowest relevant tests for parser, compiler, authoring, or runtime behavior
- inspect command/runtime behavior through the ordinary workflow surfaces rather than special-case loaders

## Built-in skill and discovery seams

This workflow-authoring skill itself is a built-in packaged skill. Relevant skill/discovery seams when debugging skill availability are:

- `components/prompt-assets/src/psi/prompt_assets/skills.clj`
- `components/prompt-assets/test/psi/prompt_assets/skills_test.clj`
- `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`
- `components/agent-session/src/psi/agent_session/commands.clj`

## Verification checklist

For workflow work, prove the relevant structural surfaces:

- the authored workflow shape matches the documented grammar
- the right workflow file kind is used (`.md` vs `.edn`)
- parser/compiler/definition tests cover the changed behavior
- runtime or command-level verification covers user-visible workflow effects when applicable
- docs, examples, tests, and workflow files agree on the contract

## Testing stance

Prefer narrow tests that exercise real parser/compiler/runtime seams and observable workflow outputs. Avoid mock-heavy tests when real local workflow components provide stronger proof.
