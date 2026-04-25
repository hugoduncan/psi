Goal: make backend slash-command handling fall through to prompt-template expansion for unknown slash-prefixed input, so loaded session prompt templates are invokable as `/name ...` through shared backend execution semantics and discoverable on supported slash-completion surfaces.

Intent:
- preserve the current startup contract that `.psi/prompts/` are discovered at runtime/context startup
- make prompt-template invocation part of shared backend behavior rather than adapter-specific behavior
- make slash resolution operate over the session's loaded prompt-template set rather than over file origin or transport-specific state
- keep command precedence over templates
- eliminate the current RPC-only `[not a command] /name ...` behavior when `/name` names a loaded prompt template

Problem:
- prompt templates are loaded into session state at startup, but unknown slash-prefixed input on the RPC command path is treated as "not a command" before template expansion is given a chance
- this creates a mismatch between operator expectation and actual behavior: a loaded prompt template exists, but `/template-name ...` can still fail as though it were absent
- the mismatch appears at the command/prompt boundary, not at startup loading time

Context:
- `.psi/prompts/*.md` are discovered during runtime bootstrap and stored in session `:prompt-templates`
- prompt templates may also enter the session through later registration or reload paths; the relevant runtime truth for this task is the session's currently loaded prompt-template set
- prompt-template expansion currently lives in request preparation via `psi.agent-session.prompt-request`
- slash-command dispatch currently lives in `psi.agent-session.commands`
- RPC command handling currently emits `[not a command] ...` when `commands/dispatch-in` returns nil
- existing template semantics already state that commands take priority over templates

Desired outcome:
- built-in commands and extension commands remain authoritative when names collide with templates
- if slash-prefixed input is not a real command but does match a loaded prompt template, backend handling should treat it as prompt/template input rather than unknown-command failure
- loaded prompt templates should appear as slash autocomplete/completion candidates wherever slash completion already consumes backend/session prompt-template state
- execution semantics should be shared backend behavior; completion visibility is required only on supported slash-completion surfaces already consuming backend/session prompt-template state
- adapters should inherit execution behavior without transport-specific logic
- true unknown slash-prefixed input should still surface as unknown/not-a-command after both command and template resolution fail

Scope:
In scope:
- define the canonical backend fallback order for slash-prefixed input
- implement template-aware fallback in the shared backend command/prompt boundary
- preserve command-over-template precedence
- adjust RPC/app-runtime entry paths only as needed to consume the shared backend resolution semantics rather than transport-local guessing
- ensure loaded prompt templates are exposed as slash autocomplete/completion candidates on supported interactive surfaces where slash completion already consumes backend/session prompt-template state
- add focused tests proving loaded prompt templates can be invoked via `/name ...`, appear in completion, and unknown names still fail appropriately

Out of scope:
- adding a new dedicated extension command for each prompt template
- changing prompt-template discovery paths or startup loading behavior
- changing template placeholder semantics
- changing skill invocation semantics
- redefining skill-resolution precedence relative to prompt templates beyond preserving existing command/skill surfaces
- redesigning the full command architecture beyond the minimal fallback needed here
- redesigning general completion architecture beyond restoring prompt-template visibility on existing slash-completion surfaces
- adding new live completion refresh mechanisms beyond what is required for loaded prompt-template visibility
- non-slash completion surfaces

Minimum concepts:
- slash-prefixed user input
- built-in command
- extension command
- loaded prompt template
- shared backend resolution boundary
- unknown slash input
- slash autocomplete/completion candidate surface

Canonical behavior:
1. user enters slash-prefixed input
2. backend resolves against the session's loaded surfaces in this order:
   - built-in command
   - extension command
   - loaded prompt template
   - unknown slash input
3. if a command matches, command semantics run and template expansion does not
4. if no command matches and a loaded prompt template matches, the input is treated as prompt/template input
5. if neither command nor template matches, backend returns unknown/not-a-command

Possible implementation shapes:
- Shape A: keep `commands/dispatch-in` command-only and place template fallback in the shared prompt entry boundary after command dispatch returns no match
- Shape B: enrich slash resolution to return a richer shared result such as command match vs template match vs unknown
- Shape C: introduce a small shared slash-resolution helper used by command/prompt entry paths while keeping RPC transport-only
- Preferred direction: choose the smallest shared-backend shape that avoids duplicating template checks in RPC and app-runtime and preserves one authoritative resolution path

Architecture constraints:
- fix belongs in shared backend semantics, not in RPC-specific transport logic
- RPC should remain transport/result mapping, not the owner of template-vs-command resolution semantics
- RPC and app-runtime must not each carry their own independent template-match fallback logic
- the authoritative match set is the session's loaded prompt-template state
- prefer one authoritative resolution path so TUI, RPC, and future adapters converge
- preserve the existing documented/tested rule that commands take priority over templates

Acceptance:
- a loaded session prompt template can be invoked as `/template-name ...` through the shared backend slash-resolution behavior
- behavior is driven by the session's loaded prompt-template state, including templates present through later registration or reload rather than startup discovery alone
- loaded prompt templates appear as slash autocomplete/completion candidates on the supported interactive surfaces that already consume backend/session prompt-template state
- built-in and extension commands still take priority over prompt templates with the same name
- if a prompt template name collides with a built-in or extension command name, invoking `/name ...` executes the command rather than the template
- collision completion behavior is deterministic and does not produce ambiguous duplicate indistinguishable candidates on the supported surfaces in scope
- RPC no longer emits `[not a command] /template-name ...` for a loaded prompt template
- supported non-RPC interactive prompt-submission paths remain converged on the same backend resolution order
- true unknown slash-prefixed input still yields unknown/not-a-command behavior
- focused tests prove the command/template precedence, fallback behavior, collision handling, loaded-session-state behavior, and completion visibility

Notes:
- this task is motivated by live behavior where `/gh-issue-work-on 27` failed as `[not a command]` even though `gh-issue-work-on` had been loaded as a prompt template
- the design direction is backend/app-runtime ownership of the fallback semantics, not RPC-local special-casing
- the runtime truth surface for this task is the session's loaded prompt-template set, regardless of whether templates came from startup discovery or later registration/reload
