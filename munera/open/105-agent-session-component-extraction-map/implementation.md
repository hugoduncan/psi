2026-05-07

Task created to capture the coherent component extraction map latent inside `agent-session`.

Creation rationale:
- recent turn-runtime/turn-preparation discussion showed that narrow extraction tasks can become structurally awkward when pursued before the broader subsystem map is explicit
- the right frame is the component map just above `session-state`, not a sequence of isolated namespace moves
- this umbrella is intended to guide which existing tasks remain valid children and which should be closed or re-scoped

Initial map captured in `design.md`:
- probable `agent-session` core remains session lifecycle / context / dispatch / statechart / orchestration
- strongest extraction candidates identified as:
  - prompt composition / prompt assets
  - OAuth / provider auth
  - tool runtime
  - turn
  - workflow
  - project nREPL
  - extensions runtime
  - scheduler
  - persistence / journal
  - background jobs

Child-task results now incorporated:
- `106-provider-auth-component-extraction` is landed
  - confirms OAuth / provider auth was correctly identified as a low-ambiguity early extraction
  - new authoritative component is `components/provider-auth/`
  - authoritative namespaces now live under `psi.provider-auth.*`
  - downstream `app-runtime`, `rpc`, and `agent-session` now depend downward on the extracted auth component
- `107-project-nrepl-component-extraction` is landed
  - confirms project nREPL was correctly identified as a low-ambiguity early extraction
  - new authoritative component is `components/project-nrepl/`
  - authoritative namespaces now live under `psi.project-nrepl.*`
  - downstream `agent-session` command/context/psi-tool/resolver code now depends downward on the extracted project-nREPL component

Supersession decision recorded:
- `102-turn-preparation-component-extraction` is superseded by this umbrella
- reason: its narrow extraction target proved structurally premature without the broader component map, especially around prompt composition and turn ownership

Follow-on architectural notes after child tasks `106`, `107`, and `109`:
- `106` did not expose a comparable ownership surprise; it behaved like the clean bounded extraction the umbrella predicted
- `107` exposed a cross-cutting config ownership question
- landed task `109-shared-config-resolution-component-extraction` has now resolved that question by extracting `components/shared-config/` as the lower owner for shared user/project config loading and layered `:agent-session` resolution
- `psi.project-nrepl.config` now depends downward on `psi.shared-config.*` instead of carrying copied project/user config reading logic
- this validates the umbrella's broader ownership-mapping approach: extracted subsystems can reveal lower shared seams that should become explicit components rather than being recopied locally
- `107` still accepted one non-blocking behavior drift at the higher-level tool boundary: `project-repl/start` missing-config handling now returns a structured component result, so the `psi-tool` path may want a later follow-on if stricter tool-facing error semantics matter

Live namespace-surface review completed:
- reviewed the current `components/agent-session/src/psi/agent_session/` tree against the umbrella map
- confirmed the residual `agent-session` core should include orchestration/aggregation namespaces like `bootstrap`, `context`, `runtime`, `commands`, `mutations`, `resolvers`, dispatch coordination, and session lifecycle/statechart wiring
- confirmed that top-level commands/resolvers/mutations should be treated as composition seams, not as permanent ownership claims over workflow/tool/prompt/extension/scheduler domains
- sharpened prompt vs tool vs turn boundaries:
  - prompt composition owns assets/assembly/provider-facing conversation projection
  - tool runtime owns execution/shaping/schema/runtime adapters
  - turn owns single-turn lifecycle orchestration and consumes prompt/tool capabilities rather than owning them
- recorded landed child task `111-tool-registration-component-extraction`
  - this sharpened the tool area further: pure tool-definition registration/query semantics now live below the broader tool runtime/execution boundary
- recorded landed child task `112-skill-registration-component-extraction`
  - this sharpens the prompt/skills area further: `prompt-assets.skills` remains the owner of discovery/parsing/invocation helpers, while pure registered-skill collection semantics now live in a lower `skill-registry` component
- recorded landed child task `113-command-registration-component-extraction`
  - this sharpens command ownership further: extension-owned command registration/query semantics now live in a lower `command-registry` component while command dispatch/routing and higher-level API seams remain above the boundary
- recorded landed child task `114-prompt-contribution-registration-component-extraction`
  - this sharpens the remaining prompt area further: pure extension-owned contribution normalization and register/update/unregister semantics now live in a lower `prompt-registry` component while `agent-session` keeps prompt-refresh orchestration and `prompt-assets.system-prompt` keeps composition semantics
- recorded open child task `115-workflow-registration-component-extraction`
  - this sharpens the workflow-definition registry seam further: canonical workflow-definition registration/removal/query semantics can move into a lower `workflow-registry` component while workflow-file loading, workflow-run execution/progression, and higher adapter seams remain above the boundary
- recorded open child task `116-deterministic-operation-registration-component-extraction`
  - this sharpens the separate workflow-adjacent invoke-operation registry seam further: canonical deterministic-operation registration/removal/query semantics used by workflow `:invoke` can move into a lower component while invoke-step execution sequencing and broader workflow runtime ownership remain above the boundary
- observed that turn extraction is already in an intermediate state because `components/agent-session/src/psi/turn/handlers.clj` exists while high-level prompt-turn orchestration still resides under `psi.agent-session.*`
- recorded open child task `119-expand-turn-runtime-prepared-turn-boundary`
  - this sharpens the current turn follow-on further: the right next move is to expand the existing `turn-runtime` component so it owns more of the lower prepared-turn boundary, while leaving dispatch invocation and higher session-owned orchestration in `agent-session`
  - this replaces any temptation to revive the old sibling `turn-preparation` framing from superseded task `102`
- confirmed workflow is now the clearest remaining extraction candidate by both namespace mass and conceptual cohesion
- clarified that the workflow area now contains at least two distinct lower registry seams rather than one: workflow-definition registration and deterministic-operation registration for workflow `:invoke`
- recorded that the lower shared-config seam exposed by `107` is no longer speculative: it is now landed concretely via task `109`
- updated extraction ordering to move extensions runtime ahead of scheduler/persistence/background jobs
- recorded that narrower registry refinements inside the broader tool/prompt/extension/workflow areas have now started landing concretely through tasks `111`–`114`, with workflow-adjacent follow-on registry tasks `115` and `116` open in the current munera state
- recorded landed task `100-turn-statechart-component-extraction` as a narrow low-level turn child under this umbrella rather than as a substitute for the broader turn boundary
