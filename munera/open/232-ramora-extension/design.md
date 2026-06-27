# 232-ramora-extension

## Goal

Create a `ramora` extension that injects the Ramora protocol into the system prompt as a prompt contribution, similar to how `munera` and `mementum` extensions work.

## Context

Ramora is a protocol for organizing project knowledge in markdown files, optimized for LLM context windows. The protocol exists in two forms:
- `RAMORA.md` — prose form
- `RAMORA-LAMBDA.md` — lambda form

The extension should serve the appropriate form based on the current session's lambda mode setting, mirroring the pattern used by `munera` and `mementum` extensions.

## Constraints

- Follow the existing extension pattern: `extensions/ramora/` with `deps.edn`, `src/extensions/ramora.clj`, `resources/extensions/ramora/`, and `test/extensions/ramora_test.clj`
- The extension should read protocol content from bundled resources (not from external paths on disk at runtime)
- Two resource files needed: one for lambda form, one for prose form
- Select which form to inject based on `:psi.agent-session/prompt-mode` (same as munera/mementum)
- Prompt contribution section name: "Ramora Protocol"
- Priority should be set appropriately relative to mementum (50) and munera (51) — ramora is a knowledge organization protocol, so priority 52 seems right (after munera)
- The extension is a prompt-contribution-only extension (no resolvers, mutations, or operations)

## Acceptance

- Extension loads without error
- In lambda mode: `RAMORA-LAMBDA.md` content is injected as prompt contribution
- In prose mode: engage prefix + `RAMORA.md` content is injected as prompt contribution
- Tests verify correct content selection per mode
- Extension follows the same structural pattern as munera/mementum
