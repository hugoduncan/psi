Goal: retire the current authored workflow grammar once the IR-first runtime, target-grammar compiler, migrated examples, and dependent workflows make compatibility support unnecessary.

## Intent

Task `077` deliberately chose a migration architecture that preserves current workflows first and retires the current authored grammar later. This task defines the controlled endgame for that migration so the project does not carry two permanent authored workflow languages longer than necessary.

## Problem statement

A compatibility compiler from current grammar to IR is useful during migration, but keeping it indefinitely has costs:

- duplicated conceptual surfaces
- ongoing maintenance burden
- author confusion about which grammar is preferred
- increased risk that runtime and docs remain split between old and new models

However, removing compatibility too early would destabilize existing workflows and dependent tooling.

This task exists to make retirement an explicit, gated cleanup rather than an unplanned future rewrite.

## Scope

In scope:

- identify the prerequisites for retiring current-authored grammar support
- enumerate dependent workflows/tests/docs that must be migrated first
- remove or disable current-authored grammar loading/compilation once the prerequisites are satisfied
- clean up compatibility-only code, tests, and docs that are no longer needed
- update project guidance so the target grammar is the only supported authored workflow language

Out of scope:

- the earlier migration work itself
- broad unrelated cleanup beyond workflow grammar compatibility retirement

## Desired outcome

The project has one authored workflow grammar and one runtime model:

- target authored grammar
- normalized workflow IR runtime model

The current authored grammar and its compatibility compiler are gone.

## Acceptance

- explicit retirement prerequisites are identified and satisfied before removal
- current-authored grammar loading/compilation support is removed or disabled intentionally
- compatibility-only tests/docs/code are cleaned up
- project guidance no longer presents the current grammar as an active authored workflow option
- runtime and documentation converge on the target grammar + IR model only
