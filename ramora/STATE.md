# State

## Now (2026-03-25)

### remove-active-session branch — shared current-session bridge removed from agent-session

The refactor target is now implemented.

#### Completed architecture

- `:active-session-id` is gone from canonical root state
- shared ctx no longer carries `:current-session-id*`
- core has no shared current session concept
- RPC owns its own focus pointer
- TUI owns its own focus pointer
- service is explicit-only
- core/session logic routes through explicit `session-id` or scoped `:target-session-id` ctx

#### What changed

**Core atom / ctx shape**
- removed root-state `:active-session-id`
- removed shared ctx `:current-session-id*`
- `ss/active-session-id-in` now reads only `:target-session-id`
- added explicit `ss/retarget-ctx` helper for scoped execution

**RPC**
- RPC runtime state owns `focus-session-id*`
- targetable ops are scoped from explicit request `:session-id` or RPC-local focus
- per-request session routing is explicit; RPC no longer uses dynamic `*request-session-id*` binding
- lifecycle flows update RPC focus locally
- no shared ctx focus writes remain

**TUI / console**
- TUI owns `:focus-session-id` in UI state
- main runtime closures scope ctx explicitly from adapter-local focus
- CLI/TUI no longer depend on shared ctx current-session fallback

**Resolvers / service**
- removed `:psi.agent-session/context-active-session-id` from resolver surface
- service APIs require explicit `session-id`

**Lifecycle**
- new/resume/fork return session data and re-scope locally
- no lifecycle operation mutates a shared current-session pointer

**Tests / harness**
- tests updated to re-scope ctx explicitly after lifecycle operations
- test support now seeds `:target-session-id` directly

#### Truthful invariant

> agent-session core has no shared current-session concept.
> Session routing is explicit: either adapter-local focus or scoped `:target-session-id`.

#### Tests

All current Clojure tests are passing:
- `bb clojure:test:unit` ✓ 935 tests, 5660 assertions, 0 failures
- `bb clojure:test` ✓ 935 unit + 86 extension tests green

#### What remains next

The bridge-removal plan is complete. Remaining work is follow-on cleanup / next design steps:
- extension instance state storage
- retire remaining agent-core coupling in some consumers
- configuration scoping
- cache stability improvements
- prompt request-preparation convergence (agent profile / skill injection)
- model selection hierarchy for helper/background task execution
- post-Wave-B executor thinning is complete:
  - `psi.agent-session.executor` has been deleted
  - prompt/streaming ownership now lives in `prompt-turn` / `prompt-loop`

Completed follow-on cleanup since Wave B:
- `psi.ai.providers.openai.common` has been removed after migrating direct callers
- `psi.app-runtime.background-jobs` has been removed after migrating commands, RPC, widgets, and tests to `background-job-view`
- auto-session-name extension vertical slice is now landed:
  - prompt lifecycle emits extension event `session_turn_finished`
  - extension runtime supports delayed extension dispatch via `psi.extension/schedule-event`
  - extension API supports explicit source-session targeting via `:query-session` and `:mutate-session`
  - `extensions.auto-session-name` now infers titles from journal-backed visible session content through a helper child session
  - extension-local guards prevent helper recursion, stale checkpoint overwrite, and overwrite when the current session name diverges from the last auto-applied name

#### Post-Wave-B check

Wave B of the Gordian refactor is complete.

Completed commits:
- `da746cef` — `⚒ prompts: extract shared prompt streaming helpers`
- `177a3551` — `⚒ prompts: split single-turn and loop execution`
- `7221fa57` — `⚒ prompts: narrow prompt-runtime to prepared-request execution`
- `1530e0b9` — `⚒ dispatch: consolidate prompt lifecycle handler registration`
- `5ea34cc0` — `⚒ background-jobs: extract canonical view model`
- `0614907f` — `⚒ openai: separate transport content and reasoning helpers`

Gordian wave-boundary snapshot:
- `src`: propagation cost `13.7%`, cycles `none`, namespaces `181`
- `src+test`: propagation cost `12.9%`, cycles `none`, namespaces `306`
