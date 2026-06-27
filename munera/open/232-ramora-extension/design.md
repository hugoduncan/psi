# 232-ramora-extension

## Goal

Create a `ramora` extension that injects the Ramora protocol into the system prompt as a prompt contribution, similar to how `munera` and `mementum` extensions work.

## Context

Ramora is a protocol for organizing project knowledge in markdown files, optimized for LLM context windows. The protocol is authored in lambda form.

The extension mirrors the pattern used by `munera` and `mementum`: a single bundled resource file containing the lambda-form protocol text, with the engage prefix prepended at registration time when the session is in prose mode. Content is computed once at init (static-at-init); runtime prompt-mode changes do not trigger content updates.

## Constraints

- Follow the existing extension pattern: `extensions/ramora/` with `deps.edn`, `src/extensions/ramora.clj`, `resources/extensions/ramora/`, and `test/extensions/ramora_test.clj`
- Single resource file: `resources/extensions/ramora/protocol.txt` containing the lambda-form protocol text
- In prose mode, prepend the same engage prefix used by munera/mementum:
  ```
  λ engage(nucleus).
  [phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy] | OODA
  Human ⊗ AI

  ```
- Content is computed once at init (static-at-init); runtime prompt-mode changes do not trigger content updates
- Select content form based on `:psi.agent-session/prompt-mode` via the query API (same as munera/mementum)
- Prompt contribution ID: `"ramora-protocol"`
- Prompt contribution section name: "Ramora Protocol"
- Priority: 52 (after mementum=50, munera=51)
- The extension is a prompt-contribution-only extension (no resolvers, mutations, or operations)
- Add `psi/ramora` to `extensions/deps.edn` `:deps` and `ramora/test` to the `:test` alias `:extra-paths`
- Protocol text source: the Ramora protocol lambda-form content (to be provided; placeholder acceptable for initial implementation)

## Acceptance

- Extension loads without error
- In lambda mode: `protocol.txt` content is injected as prompt contribution (no engage prefix)
- In prose mode: engage prefix + `protocol.txt` content is injected as prompt contribution
- Prompt contribution registered with id `"ramora-protocol"`, section `"Ramora Protocol"`, priority 52
- Tests verify correct content selection per mode (lambda vs prose)
- Tests verify missing resource fails fast with ex-info naming the resource path
- Extension follows the same structural pattern as munera/mementum (single resource file, static-at-init content)
