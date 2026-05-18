# Plan

Implement this as one ownership-convergence vertical slice: identify the authoritative built-in tool definition owners, move built-in prompt descriptions onto those canonical tool maps, switch prompt assembly to render from normalized tool definition maps, and prove one shared fallback/rendering path for built-in and extension tools.

## Review and follow-up surfaces

- `implementation.md` is the append-only review/decision log for this task.
- `design-steps.md` is the actionable ambiguity follow-up surface.
- `steps.md` remains reserved for implementation execution work, not ambiguity-review follow-up capture.

## Approach

1. **Inventory current tool-description ownership and prompt-rendering paths**
   - identify the authoritative built-in tool definition owner(s) for `read`, `bash`, `edit`, `write`, and `psi-tool`
   - inspect canonical tool normalization and registration surfaces to confirm where `:description` and `:lambda-description` are preserved today
   - inspect prompt-rendering paths to find where prompt assembly still operates on built-in tool names instead of normalized tool definition maps
   - record whether any session-state, introspection, compaction, or projection surfaces drop `:lambda-description`

2. **Settle the canonical contract and fallback rule explicitly in code-facing notes**
   - keep the existing flat tool-definition fields:
     - `:description`
     - optional `:lambda-description`
   - preserve one shared fallback rule:
     - prose mode renders `:description`
     - lambda mode renders `:lambda-description` when present and non-blank
     - otherwise lambda mode falls back to `:description`
   - avoid introducing a nested prompt-description map unless inventory proves a concrete blocker

3. **Move built-in prompt descriptions onto the authoritative tool definition maps**
   - update each built-in tool definition owner to carry its canonical prose description and lambda description directly on the tool map
   - preserve current user-visible wording where practical so the task changes ownership first, not prompt copy broadly
   - keep extension-contributed tools on the same contract without requiring a separate built-in path

4. **Switch system-prompt assembly to definition-driven rendering**
   - remove the built-in-only description table from `psi.prompt-assets.system-prompt`
   - converge the `build-system-prompt` boundary onto normalized `:tool-defs` inputs instead of the current split `:selected-tools` plus `:extension-tool-descriptions`
   - allow `:selected-tools` to remain only as a deterministic selector when higher callers need allowlist filtering, not as the prompt-rendering source of truth
   - change tool-section rendering to operate on the selected normalized tool definition maps for both built-ins and extensions
   - preserve existing prompt-mode behavior except for the ownership/path convergence

5. **Tighten proofs and only strengthen schemas where needed**
   - update tool-definition normalization tests to prove preservation of `:description` and `:lambda-description`
   - update system-prompt tests to prove:
     - built-in tools render from their tool definitions
     - extension tools render through the same path
     - lambda fallback to prose is uniform across both built-in and extension tools
   - if needed, strengthen explicit stored/projection schema surfaces narrowly enough to keep the canonical contract visible and testable without broadening into a session-state redesign

## Risks

- The main hidden complexity may be upstream of prompt rendering: some call paths may currently pass only tool names, so the smallest clean change may require adjusting function inputs to carry normalized tool definition maps.
- Built-in tool definitions may be assembled in multiple owners rather than one obvious canonical table; choosing the wrong owner would just move the split elsewhere.
- Some projections may intentionally drop `:lambda-description`; this task should distinguish between acceptable provider-facing omission and accidental loss on prompt/introspection/session-owned surfaces.
- It is easy to broaden this into a general tool-schema or registry redesign; keep the slice focused on description ownership, fallback semantics, and prompt rendering.
