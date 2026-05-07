2026-05-07

Task created as a concrete child of `105-agent-session-component-extraction-map`.

Creation rationale:
- provider-auth / OAuth is one of the clearest bounded subsystems currently latent inside `agent-session`
- current production consumers span app-runtime, rpc, and agent-session, which is a strong signal that the current `psi.agent-session.*` ownership is incidental rather than essential
- this is a lower-ambiguity first extraction than turn/prompt decomposition

Initial boundary decision:
- extract the whole provider-auth / OAuth cluster as one component rather than splitting provider-auth from OAuth in separate tasks
- keep invocation/orchestration of auth flows in higher-level callers; move the auth implementation itself out of `agent-session`

Known current direct consumer surfaces at task creation time:
- app runtime login/context creation
- agent-session runtime/provider request preparation helpers
- agent-session commands/dispatch effects/state accessors/extensions runtime fns
- rpc login handling
- focused oauth/provider-auth tests plus higher-level integration tests

Review pass for ambiguity/tightness:
- overall this task is materially cleaner than `102`: the provider-auth / OAuth cluster is already coherent and does not show the same ownership blur as turn/prompt work
- found one namespace-surface ambiguity worth keeping explicit:
  - the component is named `provider-auth`, but it contains both the small provider-auth helper surface and the larger OAuth subsystem
  - keep the split explicit in the extracted namespace family:
    - `psi.provider-auth.core` owns provider-scoped API-key/request-option helpers
    - `psi.provider-auth.oauth.*` owns OAuth context/store/provider/pkce/callback-server concerns
  - this avoids a fuzzy “everything in one core namespace” end state
- found one test-location ambiguity worth tightening:
  - the design currently says moved tests should live under `components/provider-auth/test/psi/provider_auth/`, which is correct at the component root, but the moved OAuth-focused tests should preserve subfamily ownership under `psi/provider_auth/oauth/` where appropriate rather than flattening everything into one directory
  - keep provider-auth-helper tests near `psi/provider_auth/core_test.clj` and OAuth-family tests under `psi/provider_auth/oauth/*_test.clj`
- found one configuration-surface ambiguity worth making explicit during implementation:
  - production consumers span multiple components (`app-runtime`, `agent-session`, `rpc`), so dependency/config updates are not only a root `deps.edn` concern
  - this extraction will likely need explicit consuming-component dep updates for at least `components/agent-session`, `components/app-runtime`, and `components/rpc`, and possibly test alias path updates where those are enumerated separately
- found one boundary note worth preserving:
  - `oauth/providers.clj` owns provider registration and default-provider bootstrap with process-global behavior
  - this task should preserve that behavior as-is; do not widen into redesigning provider registration semantics while extracting the component
- found one migration risk worth making explicit:
  - namespace relocation can accidentally change load/init timing around provider registration and default-provider bootstrap if the move is not updated carefully
  - preserve current initialization behavior and treat any registration-timing drift as a regression, not as acceptable extraction fallout
- after those clarifications, the task remains a strong low-ambiguity first extraction candidate under umbrella task `105`

Resolved open questions:
- component naming:
  - keep the component named `provider-auth`
  - keep the internal namespace split explicit as `psi.provider-auth.core` plus `psi.provider-auth.oauth.*`
- test placement:
  - provider-auth helper tests move to `components/provider-auth/test/psi/provider_auth/core_test.clj`
  - OAuth-family tests move under `components/provider-auth/test/psi/provider_auth/oauth/`
  - higher-level app-runtime/rpc/agent-session integration tests stay in their owning components
- consuming component dependency updates:
  - explicitly update `components/agent-session`, `components/app-runtime`, and `components/rpc`
  - update root `deps.edn` / `tests.edn` and any explicit test alias path lists only where needed
- provider registration behavior:
  - preserve existing provider registration/bootstrap semantics exactly
  - treat load/init timing drift as a regression rather than an acceptable side effect of extraction
