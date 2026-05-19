Implementation notes

Current-ordering change recorded
- Before this task, `create-runtime-session-context` created both the runtime context and the first live session, and `bootstrap-runtime-session!` then assembled startup inputs and adopted them into that already-created session.
- After this task, the ordering is explicit: create runtime context → build startup plan → create initial session → adopt startup plan into session.

Phase decisions
1. New authoritative runtime-context creation function or phase
- `psi.app-runtime/create-runtime-session-context`
- It now owns cwd/config resolution, model-registry init, oauth context creation, `session/create-context`, and recursion root-state setup only.
- It no longer creates the initial session.

2. New authoritative startup-plan function or phase
- `psi.app-runtime/build-startup-plan`
- It returns an explicit map-like startup-plan consumed by the later session creation/adoption phases.

3. Single explicit initial-session creation point
- `psi.app-runtime/create-initial-startup-session!`
- `bootstrap-runtime-session!` now calls this only after `build-startup-plan` completes.

4. Existing bootstrap steps moved before session creation
- prompt template discovery
- skill discovery and diagnostics collection
- context-file discovery
- developer-prompt env resolution
- prompt-mode / nucleus-prelude defaults reuse from `:session-defaults`
- startup-time base tool assembly inputs (`tools/all-tools`)

5. Steps that remain after session creation, and why
- background-job UI refresh install: currently session bootstrap/runtime-adoption oriented
- built-in workflow bootstrap: still session-targeted via `workflow-bootstrap/init-built-in!`
- `psi-tool` construction: requires concrete `session-id` for explicit session-scoped querying
- session bootstrap prompt/tool adoption via `session-bootstrap/bootstrap-in!`: mutates concrete session state
- manifest extension activation: existing bootstrap surface is session-targeted
- active-tool refresh: writes session active-tool state
- startup-summary recording: persisted on session data
- graph capability query: explicit session-scoped read of the live graph
- final system-prompt construction/persistence: persisted on session data and enriched from session-scoped graph capabilities
- memory-runtime sync: kept in post-session adoption in this slice to preserve current behavior ordering
- runtime extension-run binding: registered against concrete `session-id`
- startup rehydrate capture: snapshots current session state

6. Built-in workflow bootstrap and `psi-tool`
- Both remained post-session.
- `psi-tool` is inherently session-scoped here because its query helper closes over `session-id`.
- built-in workflow bootstrap remained post-session because the existing bootstrap surface is session-oriented and splitting install from session adoption would broaden this slice.

7. Runtime registries that remained purely runtime-context infrastructure
- extension registry
- deterministic operation registry
- workflow registry
- service registry
- project nREPL registry
- post-tool registry
- dispatch/runtime state root, including recursion root-state setup
- model registry init also remains on the runtime-context side of the split

8. Loader-style helpers introduced or clarified
- introduced `build-startup-plan` as the smallest useful loader/facade for pre-session startup assembly
- introduced `create-initial-startup-session!` to make the creation point singular and explicit
- introduced `adopt-startup-plan-into-session!` to isolate the remaining session-dependent bootstrap work
- did not further split manifest-extension discovery/apply or built-in workflow install/adopt in this slice because that would broaden scope beyond the needed boundary clarification

Tests/proof added or updated
- `create-runtime-session-context-does-not-create-initial-session-test`
- `build-startup-plan-does-not-require-live-session-test`
- updated runtime/TUI/RPC tests to use the new ordering where session creation happens during bootstrap rather than during runtime-context creation

Verification
- `clojure -M:test --focus psi.app-runtime-test --focus psi.rpc-real-delegate-command-test`
- `clj-kondo --lint components/app-runtime/src/psi/app_runtime.clj components/app-runtime/test/psi/app_runtime_test.clj components/rpc/test/psi/rpc_real_delegate_command_test.clj`
