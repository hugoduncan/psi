# Plan

## Chosen slice

Implement the render-hook shape as the smallest coherent vertical slice.

Attach optional `:render-call-fn` and `:render-result-fn` hooks to the canonical registered tool definition, then project those hooks into the existing interactive UI tool-renderer registry during registration.

## Canonical ownership and projection

- The authoritative display contract lives on the registered tool definition in the runtime tool registry.
- Interactive UI state remains the execution-time projection surface that frontends read for renderer functions.
- EQL/UI snapshots remain metadata-only and must continue stripping executable functions.
- Registration/backfill should therefore copy any tool-level render hooks from the canonical tool definition into UI state for interactive use, without making EQL snapshots function-bearing.

## Decision sequence

1. Refine the design so the ownership/projection path, in-scope built-ins, and result-rendering scope are unambiguous.
2. Inspect current built-in tool-row special cases and extension UI registration flow.
3. Add the canonical tool-definition fields and registration projection path.
4. Migrate the in-scope built-ins onto the shared registration path.
5. Keep generic fallback rendering for tools without hooks.
6. Update focused proofs for TUI, Emacs, and extension registration behavior.
7. Add the canonical tool-definition normalization/registration wiring so `normalize-tool-def` and any registration/backfill path preserve and project `:render-call-fn` / `:render-result-fn` from runtime tool definitions into interactive UI renderer state.
8. Implement and prove the concrete Emacs parity slice through the shared renderer path, or narrow scope if a shared Emacs consumption path is not actually present.
9. Update extension-facing docs to replace primary `:register-tool-renderer` guidance with the tool-definition display path, keeping imperative renderer registration documented only as compatibility/advanced usage if it remains available.

## Constraints to preserve

- No `steps.md` implementation items are executed as part of this ambiguity follow-up pass.
- Do not broaden into generalized widget or message rendering redesign.
- Preserve the current EQL snapshot contract that omits executable renderer fns.
- Preserve existing built-in user-visible summaries for the built-ins explicitly migrated in this task.

## Deferred choice

A declarative display-spec DSL is intentionally deferred. This task only needs the render-hook shape, while keeping field ownership on the tool definition so a later declarative contract can replace or subsume the hooks without reintroducing frontend-only special cases.
