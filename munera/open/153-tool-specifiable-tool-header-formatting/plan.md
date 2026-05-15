# Plan

## Approach

1. Inventory the current compact tool-header rendering path end to end.
   - identify the built-in-specific hardcoding sites in TUI and Emacs
   - identify any RPC/backend surfaces that already shape or could carry tool header summaries
   - identify the built-in tool registration sites that should own formatter metadata after migration
   - identify the extension tool normalization/registration points that must preserve formatter metadata

2. Realize the fixed tool-definition formatter mechanism.
   - require executable `:format-request` on canonical normalized tool definitions
   - preserve it through built-in and extension registration paths
   - migrate every currently registered built-in and owned extension tool in the runtime catalog onto the contract in this task
   - reject missing formatter metadata during registration; only a named, task-documented, temporary shim is allowed during in-flight migration and it must be removed before task completion
   - keep it runtime-only and out of provider/agent-core boundary projections
   - implement the agreed single-map formatter contract and defensive fallback rules

3. Introduce one backend-owned shared formatter helper under `components/tool-runtime/`.
   - preferred namespace: `psi.tool-runtime.call-summary`
   - resolve a registered tool definition for a tool call
   - invoke the tool-specific formatter safely when configured
   - preserve a stable fallback summary when absent or failing
   - keep the helper independent of TUI-specific rendering code and reusable from canonical tool lifecycle/RPC shaping

4. Migrate existing built-in header formatting onto the new mechanism.
   - preserve current `read`, `edit`, `write`, and `bash` compact header behavior
   - implement `psi-tool` formatting to the task-local canonical spec in `psi-tool-format.md`
   - define and implement explicit `delegate` compact header formatting around action plus primary workflow/run target, matching `delegate-format.md`
   - remove or narrow built-in-name special casing from the canonical display path

5. Converge extension tool participation where task-local formatter specs or parity rules already exist.
   - implement `edit-clj` formatting with the same request-summary semantics as built-in `edit` where applicable
   - preserve the rendered `edit-clj` tool identity rather than aliasing it to `edit`
   - implement `work-on` formatting to the task-local canonical spec in `work-on-format.md`

6. Update display consumers.
   - TUI compact tool header rendering uses the shared helper
   - RPC/backend display shaping uses the shared helper where it exposes tool header lines or summaries
   - canonical tool lifecycle payloads/events carry the preformatted compact header string under `:call-summary`, including the pre-completion `tool/executing` path and RPC-facing tool row payloads derived from it
   - Emacs migrates in this task to consume that RPC/backend-produced `:call-summary` surface

7. Add focused proof.
   - built-in parity for current formatted headers
   - `psi-tool` parity against `psi-tool-format.md`
   - `delegate` parity against `delegate-format.md`
   - `work-on` parity against `work-on-format.md`
   - `edit-clj` parity with built-in `edit` request-summary semantics while keeping the `edit-clj` label
   - formatter failure fallback
   - TUI and RPC/backend shared helper usage
   - RPC payload carries preformatted compact header string
   - Emacs consumes RPC-provided compact header rather than local formatting
   - boundary with extension custom renderers remains explicit

## Fixed owner decision

- shared compact header summary ownership lives in `components/tool-runtime/`
- preferred namespace: `psi.tool-runtime.call-summary`
- if the final namespace spelling differs slightly, it must remain a lower tool-runtime owner adjacent to canonical tool lifecycle shaping rather than moving upward into TUI, RPC, or agent-session

## Risks

- executable formatter values fit the current in-process tool-definition model, but implementation must keep them out of provider/agent-core boundary projections so runtime-only display metadata does not leak across boundaries
- TUI, RPC, and Emacs may currently consume slightly different local notions of “summary,” so careless unification could regress visible behavior
- overlap with extension UI renderer registration could blur responsibility unless the compact-header boundary stays explicit

## Verification

- focused tests for the chosen formatter mechanism and normalization
- focused tests proving built-in parity for `read`, `edit`, `write`, and `bash`
- focused tests proving extension-defined formatting works without core UI changes
- focused tests proving fallback behavior on absent/invalid/failing formatter metadata
- focused tests proving TUI and RPC/backend display shaping use the shared helper
- focused proof that Emacs consumes the RPC/backend-produced `:call-summary` surface in this task, with no remaining local compact-header tool-name dispatch in the canonical tool-row path
