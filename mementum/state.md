# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `munera/plan.md` — active task orchestration
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state

- 2026-05-21: Fixed OpenAI OAuth `gpt-5.5` routing (commit 009aed51).
  - Root cause: `gpt-5.5` used platform chat-completions while active OpenAI auth was ChatGPT OAuth; platform path returned `insufficient_quota`.
  - Runtime resolution now maps OAuth-backed `openai/gpt-5.5` to `:openai-codex-responses` / `https://chatgpt.com/backend-api`, preserving visible id `gpt-5.5`.
  - Updated prompt-request, app-runtime, RPC, TUI action, and `/model` command resolution seams.
  - Focused tests green: `clojure -M:test --focus psi.provider-auth.core-test --focus psi.ai.model-registry-test --focus psi.agent-session.runtime-test --focus psi.agent-session.prompt-request-test` => 32 tests, 162 assertions, 0 failures.
  - Live reload/eval verified and user successfully switched `/model openai gpt-5.5`.
- Bootstrap simplification arc (159–163) complete:
  - 159: in-process bootstrap simplification
  - 160: removed mutation-mediated bootstrap resource loading
  - 161: single-pass startup, `bootstrap-in!` and `refresh-active-tools-in!` removed
  - 162: `bootstrap-runtime-session!` collapsed to single `(ctx ai-model opts)` arity
  - 163: `start-tui-runtime!` refactored — dead `ai-ctx` removed, nullable exec mode extracted
- Tasks 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 also complete and closed

## Test health

Focused OAuth routing tests ✅. bb tests previously ✅. 5 former test errors fixed (commit 0b37b83f: NPE on nil session-file, SOE in git resolvers). Task 158 addressed persistence test garbage (still open but test-review showed no actionable feedback).

## Suggested next step
- Backlog: `105-agent-session-component-extraction-map`, `124-turn-execution-contract-extraction`, `149-reload-fixup-inventory-and-safety`, `141`/`144`/`147` workflow items

## Latest session notes

- 2026-05-22: Completed Munera task 166 ambiguity follow-up: defined the promotion gate/owner for replacing design-only placeholder `plan.md`/`steps.md`; all `design-steps.md` items are now checked.
- 2026-05-21: OpenAI OAuth-backed `gpt-5.5` now works through Codex transport.
- 2026-05-20: oriented on bootstrap-simplification branch; 159–163 arc confirmed complete; test errors confirmed fixed
