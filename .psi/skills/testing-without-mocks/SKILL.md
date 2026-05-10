---
name: testing-without-mocks
description: >
  Apply James Shore's "Testing Without Mocks" pattern language to write tests that are narrow, sociable,
  state-based, and free of mocks/spies. Use this skill when writing or refactoring tests for any codebase,
  when the user asks to test code without mocks, when applying Nullable infrastructure patterns, when
  structuring code with A-Frame Architecture for testability, or when converting legacy mock-heavy tests.
  Triggers: "test without mocks", "nullable pattern", "sociable tests", "A-Frame architecture",
  "remove mocks", "testing-without-mocks", "narrow tests", "infrastructure wrapper", "embedded stub".
lambda: "λtest. ¬mocks → methodology"
---

# Testing Without Mocks

Apply James Shore's pattern language for writing tests that are narrow, fast, deterministic, and mock-free.
Full reference: https://www.jamesshore.com/v2/projects/nullables/testing-without-mocks

## Goals

Write tests that satisfy ALL of these properties:

1. **No broad tests required** — only narrow tests focused on specific behaviors; smoke tests are a safety net, not the strategy
2. **Easy refactoring** — test behavior/outcomes, not interactions or method calls between objects
3. **Readable tests** — straightforward arrange/act/assert; tests document externally-visible behavior
4. **No magic** — no DI frameworks, auto-mocking, or reflection tricks required
5. **Fast and deterministic** — entire suite runs in 1–2 seconds with no flaky failures

## Core Decision: Which Pattern Category?

Classify the code under test, then apply the right pattern group:

- **Pure logic / values** → Logic Patterns (easiest to test; prefer this)
- **External system interaction** → Infrastructure Patterns (wrap, then make Nullable)
- **Orchestration of logic + infra** → Architectural Patterns (Logic Sandwich / Traffic Cop with Nullables)
- **Legacy code with mocks** → Legacy Patterns (incremental migration)

## Pattern Summary

For detailed pattern descriptions and examples, see [references/patterns.md](references/patterns.md).

### Foundational Patterns

- **Narrow Tests** — test one concept per test, not the whole system
- **State-Based Tests** — assert on outputs/state, never on how dependencies were called
- **Overlapping Sociable Tests** — let tests exercise real dependencies (not mocked); overlapping coverage across units catches integration gaps
- **Smoke Tests** — one or two end-to-end tests as a safety net only
- **Zero-Impact Instantiation** — constructors must do no I/O, no connections, no heavy work
- **Parameterless Instantiation** — provide factory methods with sensible defaults so tests can create objects without specifying every parameter
- **Signature Shielding** — use options/config objects so adding parameters does not break existing tests

### Architectural Patterns

- **A-Frame Architecture** — structure code so Infrastructure and Logic are peers under an Application layer, with no direct dependencies between them
- **Logic Sandwich** — Application method reads infra → computes with logic → writes infra (three steps, no interleaving)
- **Traffic Cop** — for cases where logic and infra must interleave, use an event-based or callback approach; keep the traffic cop thin and test it with Nullables
- **Grow Evolutionary Seeds** — start with a walking skeleton, grow architecture incrementally

### Logic Patterns

- **Easily-Visible Behavior** — prefer pure functions and immutable objects; for mutable objects, expose state via getters or events
- **Testable Libraries** — wrap third-party libraries that are hard to test into your own API with visible behavior
- **Collaborator-Based Isolation** — when one logical unit depends on another, test each unit focused on its own behavior; don't mock the collaborator, let sociable tests run through it

### Infrastructure Patterns

- **Infrastructure Wrappers** — one wrapper class per external system; clean internal API, messy external details hidden
- **Narrow Integration Tests** — test wrappers against the real external system; focused, slow-but-necessary tests for infra only
- **Paranoic Telemetry** — monitor production to catch integration issues that tests cannot

### Nullability Patterns

- **Nullables** — production classes get a `createNull()` factory that returns an instance with infrastructure disabled but all logic intact
- **Embedded Stub** — the Nullable's stub logic lives inside the production class (not in a separate test file), controlled by a flag
- **Thin Wrapper** — when third-party code is hard to stub, create a thin wrapper whose sole job is to call the third party, then embed the stub in your wrapper
- **Configurable Responses** — `createNull()` accepts optional parameters to control what the Nulled instance returns, defined in terms of the wrapper's public API, not its implementation
- **Output Tracking** — Nulled instances can record what would have been sent to the external system, enabling state-based assertions on side effects
- **Behavior Simulation** — for complex infrastructure (e.g., stateful APIs), the embedded stub simulates realistic behavior
- **Fake It Once You Make It** — when converting legacy code, start by making direct dependencies Nullable and work down the tree

### Legacy Code Patterns

- **Descend the Ladder** — convert one module and its direct deps at a time, working down the dependency tree
- **Climb the Ladder** — alternatively, start from the lowest infrastructure and work up
- **Replace Mocks with Nullables** — swap mocks for Nullables one dependency at a time
- **Throwaway Stub** — create a temporary test double to fill in while you convert deeper dependencies; remove it when you reach that layer

## Workflow: Writing a New Test

1. Classify the code: pure logic, infrastructure wrapper, or orchestration?
2. If pure logic → write state-based tests directly with real collaborators
3. If infrastructure wrapper → write narrow integration tests against the real system, then add `createNull()` with Embedded Stub
4. If orchestration → ensure dependencies are Nullable, then write tests using `createNull()` with Configurable Responses and Output Tracking
5. Verify: does the test assert on outputs/state only (not on calls to dependencies)?
6. Verify: does the test use arrange/act/assert structure?
7. Verify: would a structural refactoring break this test? If yes, revise.

## Workflow: Removing Mocks from Existing Tests

1. Identify the mock/spy being used
2. Determine what the mock's target is: infrastructure or logic?
3. If logic → remove the mock; use real collaborator (sociable test)
4. If infrastructure → make the infrastructure class Nullable, use `createNull()` with Configurable Responses and Output Tracking
5. Delete the mock setup; replace with `createNull()`-based instantiation
6. Assert on outputs/state instead of verifying mock interactions
