Plan:

- center the refactor on `psi.app-runtime/bootstrap-runtime-session!`, keeping one authoritative in-process startup orchestration path for console, TUI, and RPC
- make the first structural cut in `components/app-runtime/src/psi/app_runtime.clj` by splitting the current mixed `create-runtime-session-context` role into runtime-context creation versus explicit initial-session creation
- keep the first extraction narrow: create a runtime-context function that owns cwd/config/model resolution, model-registry init, oauth/runtime context creation, `session/create-context`, and recursion/runtime root-state setup, but does not create the initial session
- update `bootstrap-runtime-session!` to create the initial session explicitly after runtime-context creation, preserving effective behavior before any larger reordering
- introduce a named startup-plan helper that returns an explicit map-like startup-plan value and extract the pre-session discovery/assembly logic from `bootstrap-runtime-session!` into that helper
- move into the startup-plan helper the startup inputs that do not inherently require a session id, including template discovery, skill discovery and diagnostics, context-file discovery, developer-prompt/env resolution, config-derived prompt inputs, and startup-time tool-definition assembly inputs
- stop reading prompt defaults back out of a just-created session when those values are already available from runtime-context creation or startup-plan assembly
- keep runtime-owned registries on the runtime-context side of the split; do not let the startup-plan phase create the initial session or become a second runtime-context constructor
- place global registration/composition work on the pre-session side when that can be done without widening scope, while allowing any truly session-dependent graph reads to remain post-session if necessary
- move the initial startup session to one explicit point after startup-plan assembly, using the existing session lifecycle API unless a very small local input adjustment materially improves the split
- extract the remaining session-dependent bootstrap into a clearly named startup-plan adoption phase that owns only the startup work that still genuinely requires a concrete session id
- keep built-in workflow bootstrap and `psi-tool` handling as narrow as possible in this slice: leave them post-session if that is the smallest coherent outcome, or split them only if a small extraction materially clarifies the boundary
- treat manifest extension handling similarly: split discovery from apply only if the discovery side can move pre-session cheaply while activation/adoption remains post-session when needed
- preserve effective startup behavior throughout, especially around resolved defaults, prompt construction, tools, extensions, startup summaries, and shared entrypoint behavior
- add or reshape proof so it is explicit that runtime-context creation does not create a session, startup-plan assembly does not mutate a live session, initial session creation happens after startup-plan assembly, and the resulting bootstrapped runtime still reaches the expected startup state
- record in `implementation.md` the final phase boundary, the chosen startup-plan shape, which concerns moved pre-session, which remained post-session and why, which registries stayed runtime-context infrastructure, and which loader-style helpers were introduced as the smallest useful extractions
