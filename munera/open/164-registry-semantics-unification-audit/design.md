# 164 registry semantics unification audit

## Intent

Create a single task that captures the current registry semantics across the codebase so we can distinguish required behaviour from incidental behaviour before attempting registry unification.

The task should produce an evidence-backed registry semantics matrix and use it to identify:

- which behaviours are part of the contract
- which behaviours are compatibility carry-forward or local implementation choices
- which registries can plausibly share a single implementation
- which registries should share only lower helpers or remain separate

## Scope

This task covers the registry-style components and adjacent registry surfaces currently in the repo, including:

- `tool-registry`
- `command-registry`
- `skill-registry`
- `prompt-registry`
- `workflow-registry`
- `deterministic-operation-registry`

It may also reference adjacent definition/normalization layers where needed to explain why registry behaviour differs, but the primary focus is registry semantics rather than registry-adjacent data-shaping helpers.

## Desired outcome

The task should leave behind a registry semantics matrix that records, for each registry:

- identity model
- validation rules
- duplicate/conflict policy
- ordering policy
- lookup miss behaviour
- removal semantics
- storage and mutation model
- built-in / extension ownership model
- public return contract
- current assessment of likely required vs likely incidental behaviour

The task should also identify convergence clusters, including at minimum:

- the strongest shared-implementation candidates
- registries that may share only a keyed-registry substrate
- registries that may share only collection helpers
- registries that should likely remain separate

## Constraints

- Do not begin by forcing all registries into a generic abstraction.
- Treat behavioural differences as unknown until justified by tests, callers, or design/task history.
- Preserve a distinction between observed behaviour and judged/required behaviour.
- Exclude `query.registry` from this task; it has a different contract and use from the domain registries in scope.

## Initial registry semantics matrix

This matrix captures the pre-migration baseline observed when the audit was first assembled. Some rows below are intentionally historical snapshots and are superseded by later sections in this document, especially the post-`169`–`172` outcome guidance.

| Registry | Identity | Validation | Duplicate / conflict policy | Ordering | Lookup miss | Removal | Storage / mutation model | Built-ins / ownership model | Return contract | Likely required? |
|---|---|---|---|---|---|---|---|---|---|---|
| **tool-registry** | `:name` string | kebab-case ASCII; requires `:format-request` fn after normalization | per-owner storage can overwrite; merged read surface is **first name wins** with built-ins first | built-ins first, then extension `:registration-order`; dedup by first seen name | `nil` from `get-tool-in` | no symmetric public remove here | mutable registry object over shared state | dual store: `:built-in-tools` + extension-owned `:tools` | mutators return registry object | merged ownership model likely required; exact overwrite details maybe incidental |
| **command-registry** | `:name` string | non-blank string | same-extension duplicate **replaces**; cross-extension duplicates allowed; merged reads are **first registration wins** | built-ins first, then extension registration order | `nil` from `get-command-in` | no symmetric public remove here | mutable registry object over shared state | dual store: `:built-in-commands` + extension-owned `:commands` | mutators return registry object | very close to tool-registry; likely good unification candidate |
| **skill-registry** | `:name` string | non-blank string | duplicate name **ignored**; first wins | canonical deterministic `:name` ordering on public read/result surfaces | `nil` from `find-skill` | none | pure vector collection | no built-in vs extension distinction here | returns result map `{:skills ... :added? ...}` | duplicate-ignore, exact lookup, and `:added?`/`:changed?` required; insertion order superseded by task `173` |
| **prompt-registry** | composite identity `ext-path + id`, both string-coerced | very permissive; nil/blank identities normalize to `""`; patchable fields constrained | same identity **replaces** on register; update mutates matching entry logically | stored order preserved; separate canonical sort by `[priority ext-path id]` | `nil` from `find-contribution`; update/remove return unchanged state with flags | targeted unregister by identity; non-throwing | pure vector collection | ownership baked into identity via `ext-path` | returns result maps with status flags | identity permissiveness looks incidental; composite identity and priority ordering likely required |
| **workflow-registry** | `:definition-id`, normalized to string; blankish ids generate UUID | target-authored workflow definition predicate | register **replaces** existing definition at normalized id | public listing sorted by `:definition-id` | `nil` from `workflow-definition` | targeted remove; **throws if missing** | pure root-state transform | no built-ins; root-state owner | tuple `[state id stored-definition]` / `[state removed-definition]` | pure-state ownership likely required; UUID-on-blank maybe incidental or compatibility |
| **deterministic-operation-registry** | canonical `:id` string | strict normalized def validation; canonical namespaced kebab-case ids | duplicate id **throws** | unordered membership/count read contract; no adapter-local registration-order state | `nil` from `get-operation-in`; invoke **throws if missing** | bulk unregister by `ext-path` | runtime-owned registry object over shared `root-registry` state | extension ownership via `:ext-path` for cleanup, plus shared runtime ownership conventions | mutators return registry object | duplicate-throw + bulk-unregister feel strongly required; ordering now looks removable/adapter-level rather than essential |

## Caller-and-test-backed registry matrix

This section began as an evidence pass over the then-current registry surfaces. Where later migration work changed a registry’s lower storage or ordering contract, treat the newer outcome sections later in this document as authoritative over the original audit snapshot.

| Registry | Primary callers / consumers | Direct tests / evidence | Caller-backed semantic signals | Test-backed semantic signals | Initial preserve judgement |
|---|---|---|---|---|---|
| **tool-registry** | `components/agent-session/src/psi/agent_session/extensions.clj` thin delegation wrappers; `components/agent-session/src/psi/agent_session/mutations/extensions.clj`; `components/agent-session/src/psi/agent_session/resolvers/extensions.clj`; `components/agent-session/src/psi/agent_session/psi_tool.clj`; `components/agent-session/src/psi/agent_session/tool_plan.clj`; `components/agent-session/src/psi/agent_session/workflow/bootstrap.clj`; `components/app-runtime/src/psi/app_runtime.clj` | `components/tool-registry/test/psi/tool_registry/registry_test.clj`; `components/tool-registry/test/psi/tool_registry/defs_test.clj`; integration-adjacent proofs in `components/agent-session/test/psi/agent_session/workflow_built_in_targeting_test.clj`, `workflow_reload_runtime_test.clj`, `query_graph_tools_test.clj`, `extensions_io_test.clj`, `extensions_test.clj`, `components/app-runtime/test/psi/gordian_launcher_manifest_runtime_boundary_test.clj` | callers consume a unified read surface via `all-tools-in`, `tool-names-in`, and `get-tool-in`; workflow bootstrap depends on built-in registration; app runtime and resolvers project merged tool lists outward; no caller appears to depend on per-extension duplicate coexistence in merged reads | tests explicitly prove: unregistered extension path rejected; invalid names rejected; missing `:format-request` rejected; cross-extension duplicate names allowed in storage but merged reads are first-registration-wins; built-ins included, listed first, and preferred by `get-tool-in` on collision | preserve merged built-in+extension read semantics and built-in precedence; return shape likely incidental; per-owner overwrite policy still needs caller audit |
| **command-registry** | `components/agent-session/src/psi/agent_session/extensions.clj` thin delegation wrappers; `components/agent-session/src/psi/agent_session/mutations/extensions.clj`; `components/agent-session/src/psi/agent_session/resolvers/extensions.clj`; `components/agent-session/src/psi/agent_session/commands.clj`; `components/agent-session/src/psi/agent_session/turn/handlers.clj`; `components/agent-session/src/psi/agent_session/workflow/bootstrap.clj`; `components/rpc/src/psi/rpc/events.clj` | `components/command-registry/test/psi/command_registry/registry_test.clj`; integration-adjacent proofs in `components/agent-session/test/psi/agent_session/extensions_test.clj`, `workflow_async_path_test.clj`, `workflow_tui_repro_test.clj`, `workflow_reload_runtime_test.clj`, `components/app-runtime/test/psi/gordian_launcher_manifest_runtime_boundary_test.clj` | callers use `get-command-in` for command dispatch and bootstrap-discovered built-ins; RPC and resolvers expose command names as a merged capability surface; workflow bootstrap installs built-in commands into the same registry | tests explicitly prove: unregistered extension path rejected; blank names rejected; same-extension duplicate replaces; slash-prefixed and non-slash names are distinct; cross-extension duplicates are allowed but merged listing/lookup is first-registration-wins; built-ins included, listed first, and preferred on collision | preserve merged built-in+extension surface, built-in precedence, and exact-name identity; very strong candidate to unify with tool-registry under a shared lower implementation |
| **skill-registry** | `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj` for `:session/register-skill`; `components/agent-session/src/psi/agent_session/resolvers/discovery.clj`; `components/agent-session/src/psi/agent_session/context.clj` injects `find-skill`; `components/prompt-assets/src/psi/prompt_assets/skills.clj`; `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj` via execution adapter | `components/skill-registry/test/psi/skill_registry/registry_test.clj`; integration-adjacent proofs in `components/agent-session/test/psi/agent_session/config_compaction_test.clj`; prompt-facing usage in `components/prompt-assets/test/psi/prompt_assets/skills_test.clj`; workflow adapter proof in `components/workflow-runtime/test/psi/workflow_runtime/execution_adapter_test.clj` | callers require deterministic skill listing and lookup by exact name; prompt rendering/discovery/model-visible child prompts need stable canonical output, not preserved registration sequence; session mutation path cares whether registration changed so prompts can refresh conditionally | tests now prove: non-blank names required; duplicate registrations are ignored; visible registry/prompt/resource/workflow skill surfaces use canonical skill-name order; helper APIs return nil for miss and stable counts/names; `config_compaction_test` proves duplicate register-skill leaves skills and prompt unchanged | preserve duplicate no-op behavior, exact lookup, canonical deterministic listing, and change reporting; insertion-order preservation was disproved by task `173` and is no longer a required semantic |
| **prompt-registry** | `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj` for register/update/unregister mutations; `components/session-state/src/psi/session_state/state.clj` sorts prompt contributions canonically; `components/agent-session/src/psi/agent_session/resolvers/extensions.clj` projects prompt contribution count | `components/prompt-registry/test/psi/prompt_registry/contributions_test.clj`; integration-adjacent proof in `components/agent-session/test/psi/agent_session/eql_introspection_test.clj` | callers use register/update/unregister result maps to write back prompt-contribution state; session-state depends on canonical sorting helper; extension introspection depends on count only, not storage shape | tests explicitly prove: identity is string-coerced; nil/blank identity normalizes to empty strings; register replaces same identity; update/remove miss are non-throwing no-op result maps; canonical sort is by `[priority ext-path id]`; patching is constrained to selected fields | preserve composite identity, update/unregister result-map semantics, and canonical priority sort; nil/blank coercion looks more like compatibility than desired contract |
| **workflow-registry** | `components/agent-session/src/psi/agent_session/mutations/canonical_workflows.clj`; `components/agent-session/src/psi/agent_session/resolvers/workflows.clj`; `components/agent-session/src/psi/agent_session/psi_tool_workflow.clj`; `components/agent-session/src/psi/agent_session/workflow/core.clj`; `components/workflow-runtime/src/psi/workflow_runtime/core.clj`; `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime/delegate.clj`; `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj` | `components/workflow-registry/test/psi/workflow_registry/registry_test.clj`; `components/workflow-registry/test/psi/workflow_registry/definition_test.clj`; broad consumer proofs in `components/agent-session/test/psi/agent_session/mutations/canonical_workflows_test.clj`, `workflow_resolvers_test.clj`, `workflow_tools_test.clj`, `workflow_runtime_test.clj`, `workflow_session_integration_test.clj`; runtime-side proofs in `components/workflow-runtime/test/psi/workflow_runtime/core_test.clj`, `ir_runtime_adoption_test.clj`, `terminal_contract_execution_test.clj`, `progression_recording_test.clj` | callers rely on pure root-state transforms and normalized lookup across resolver, mutation, psi-tool, and runtime boundaries; workflow runtime resolves registered definitions by id, and tooling lists definitions in sorted, user-visible order | tests explicitly prove: ids normalize from keyword/string/non-string and blank ids become UUIDs; invalid definitions rejected; register replaces at same id; list/ids sorted by definition-id; public lookup normalizes ids and misses return nil; remove normalizes ids and throws when missing | preserve pure root-state API and normalized/sorted public read semantics; UUID-on-blank and throw-on-missing removal still need caller-intent review |
| **deterministic-operation-registry** | `components/agent-session/src/psi/agent_session/extensions/runtime_fns.clj` registers runtime-owned operations; `components/agent-session/src/psi/agent_session/extensions.clj` bulk-cleans operations on unload/clear and projects extension-facing summaries; `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime/step_execution.clj` invokes operations by id; `components/agent-session/src/psi/agent_session/context.clj` constructs registry | `components/deterministic-operation-registry/test/psi/deterministic_operation_registry/registry_test.clj`; `components/deterministic-operation-registry/test/psi/deterministic_operation_registry/defs_test.clj`; consumer proofs in `components/agent-session/test/psi/agent_session/extensions_test.clj`, `extensions_api_introspection_contract_test.clj`, `workflow_invoke_runtime_test.clj`; extension integration in `extensions/github/test/psi/github/find_issue_integration_test.clj` | callers depend on stable invoke-time ids, explicit runtime registration, bulk unregister by `ext-path` during extension unload/reload, and coherent extension-facing projection seams after migration; workflow invoke execution goes through registry lookup instead of direct function references | current tests prove: canonical namespaced id validation; invalid definitions rejected; duplicate registration throws; unordered membership/count listing contract; invoke throws on missing op; unregister-by-extension removes all matching ops; no-op unregister for missing extension; extension-introspection projections stay coherent | preserve duplicate rejection, bulk unregister by extension, invoke semantics, and higher projection coherence; ordering no longer appears essential, while the registry-object adapter boundary remains materially significant |

## Decision-oriented unification matrix

This matrix records the audit’s preserve-vs-share judgements as they stood during the unification analysis. Later completed migrations refined several of these judgements, most notably for `workflow-registry` and `deterministic-operation-registry`; prefer the later outcome updates when they conflict with the earlier audit stance.

| Registry | Behavior / axis | Evidence | Preserve? | Why | Candidate shared substrate |
|---|---|---|---|---|---|
| **tool-registry** | Built-ins and extension tools share one merged read surface | callers in `agent_session/psi_tool.clj`, `tool_plan.clj`, `resolvers/extensions.clj`, `workflow/bootstrap.clj`; tests in `components/tool-registry/test/psi/tool_registry/registry_test.clj` | **Yes** | external consumers ask for one visible tool set, not separate stores | shared extension/built-in merged registry |
| **tool-registry** | Built-ins win on name collision | `get-tool-in prefers built-in over extension when names collide`; built-ins listed first in `all-tools-in` tests | **Yes** | precedence avoids ambiguity and is explicitly tested | shared extension/built-in merged registry with precedence policy |
| **tool-registry** | Unregistered extension path rejected | registry tests; extension runtime callers register only through extension ownership paths | **Yes** | enforces extension ownership invariant | shared extension-owned registration kernel |
| **tool-registry** | Tool names must be kebab-case ASCII | registry tests; tool defs are provider-facing | **Likely yes** | portability/provider compatibility signal, but still domain-specific | wrapper-level validation over shared merged registry |
| **tool-registry** | `:format-request` required | registry tests; tool prompt/rendering callers rely on it | **Yes** | tools are not valid without request formatting | wrapper/domain validation, not generic substrate |
| **tool-registry** | Mutator returns registry object | implementation style only; no caller evidence of semantic dependency | **Unknown / likely no** | public shape may be convenient but not obviously domain-meaningful | adapter wrapper over lower substrate |
| **command-registry** | Built-ins and extension commands share one merged read surface | callers in `commands.clj`, `rpc/events.clj`, `resolvers/extensions.clj`, `workflow/bootstrap.clj`; tests in `components/command-registry/test/psi/command_registry/registry_test.clj` | **Yes** | command dispatch and capability exposure both expect one visible command set | shared extension/built-in merged registry |
| **command-registry** | Built-ins win on collision | built-in precedence tests in registry test file | **Yes** | same ambiguity-avoidance reason as tools | shared extension/built-in merged registry with precedence policy |
| **command-registry** | Same-extension duplicate replaces | explicit test; weak caller evidence beyond latest stored command being visible | **Unknown** | could be intended or just simplest overwrite semantics | configurable conflict policy in shared merged registry |
| **command-registry** | Cross-extension duplicates allowed but merged reads are first-registration-wins | explicit tests; dispatch callers only consume merged lookup/list surface | **Likely yes** | merged surface contract matters more than underlying coexistence | shared extension/built-in merged registry |
| **command-registry** | Exact command identity, no slash normalization | explicit tests; command dispatcher uses exact command names | **Yes** | command spelling is part of UX/API surface | wrapper/domain validation over shared merged registry |
| **command-registry** | Mutator returns registry object | no caller appears to care semantically | **Unknown / likely no** | probably implementation style | adapter wrapper over lower substrate |
| **skill-registry** | Skills have deterministic public listing order | prompt-assets, discovery, session resources, and workflow child prompts consume ordered vectors/names; task `173` found no true insertion-order dependency | **Yes, canonical order only** | prompt rendering and discovery are order-sensitive to deterministic output, but not to registration sequence | shared collection helpers with canonical `:name` sort |
| **skill-registry** | Duplicate registration is ignored | explicit tests; `config_compaction_test` proves duplicate register leaves skills and prompt unchanged | **Yes** | caller-visible no-op behavior affects prompt refresh and event flow | ordered collection substrate with first-wins policy |
| **skill-registry** | Lookup miss returns nil | direct tests; workflow adapter and prompt-assets callers use nil miss behavior | **Yes** | normal lookup contract, no exceptional miss semantics needed | shared ordered collection helpers |
| **skill-registry** | Result map with `:changed?` / `:added?` | session mutation caller uses change-ness behaviorally | **Likely yes** | conditional prompt refresh depends on changed vs unchanged | wrapper-level result projection over ordered collection substrate |
| **prompt-registry** | Identity is composite `(ext-path,id)` | dispatch handlers and tests both operate on ext-path + id pairs | **Yes** | ownership and update/remove targeting depend on composite identity | richer ordered collection/keyed-entry substrate |
| **prompt-registry** | Identity inputs are string-coerced, including nil/blank to `""` | explicit tests and code comments call this preserved first-cut behavior | **Unknown / likely no** | smells like compatibility carry-forward, not principled contract | wrapper/domain normalization |
| **prompt-registry** | Register replaces same identity | explicit tests; caller update model assumes one contribution per identity | **Yes** | identity uniqueness matters for stable patch/remove semantics | ordered collection substrate with replace-on-identity |
| **prompt-registry** | Update/remove miss are non-throwing no-ops with result flags | explicit tests; dispatch handlers can safely propagate no-op results | **Likely yes** | patch/remove flows are easier and likely intentionally tolerant | ordered collection substrate with miss-policy option |
| **prompt-registry** | Canonical sort by `[priority ext-path id]` | `session_state/state.clj` uses `sort-contributions`; explicit tests | **Yes** | prompt assembly needs deterministic contribution order | wrapper/domain sort policy over shared collection substrate |
| **prompt-registry** | Result maps carry changed/update/remove status | dispatch handlers use returned transformed collection and status | **Likely yes** | state transitions and introspection want structured outcome, not bare collection | wrapper-level result projection |
| **workflow-registry** | Pure root-state transform API | mutations, resolvers, psi-tool workflow, and workflow runtime all use root state directly; tests prove tuple state returns | **Yes** | fits lower boundary ownership and many callers already depend on pure state flow | keyed registry substrate for pure state |
| **workflow-registry** | IDs normalize to string | callers across mutations/resolvers/runtime use heterogeneous ids; explicit tests | **Yes** | one caller-facing id contract reduces ambiguity | keyed registry substrate with identity normalization hook |
| **workflow-registry** | Blank ids generate UUIDs | explicit tests; limited caller evidence beyond permissive registration | **Unknown** | may be compatibility/convenience rather than essential semantics | wrapper/domain normalization |
| **workflow-registry** | Register replaces existing definition at normalized id | explicit tests; runtime resolves one definition per id | **Likely yes** | definition identity should resolve to one authoritative definition | keyed registry substrate with replace policy |
| **workflow-registry** | Public listing sorted by definition-id | resolvers and psi-tool list visible definitions; explicit tests | **Yes** | user-visible deterministic ordering | keyed registry substrate with sorted-read policy |
| **workflow-registry** | Lookup miss returns nil | explicit tests; resolvers naturally project nil miss | **Yes** | ordinary read behavior | shared keyed registry helpers |
| **workflow-registry** | Remove missing throws | explicit tests; weaker caller evidence | **Unknown** | may be principled guardrail or just first-cut choice | keyed registry substrate with configurable miss policy |
| **workflow-registry** | Tuple return contract | visible in tests and callers, but mostly boundary-shape rather than business semantics | **Likely yes at API level, no at substrate level** | preserve public boundary while allowing lower shared mechanics | wrapper over pure keyed substrate |
| **deterministic-operation-registry** | Stable canonical namespaced ids | runtime invoke callers and defs tests both require this | **Yes** | workflow invoke steps target ids, so ambiguity is unacceptable | keyed registry substrate with strict id validation |
| **deterministic-operation-registry** | Duplicate registration throws | explicit tests; extension/runtime callers rely on uniqueness for invoke routing | **Yes** | two operations for one id would break determinism | keyed registry substrate with reject-on-conflict policy |
| **deterministic-operation-registry** | Unordered membership/count listing contract | current tests after `172` prove unordered membership/count/coherence rather than preserved insertion order | **Yes** | caller-visible behavior no longer requires registration-order guarantees; ordering was removable compatibility rather than essential semantics | keyed registry substrate without default ordering guarantees |
| **deterministic-operation-registry** | Invoke missing op throws | explicit tests; workflow invoke runtime expects clear failure on missing op | **Yes** | missing invoke target is a hard workflow error, not an optional read miss | wrapper/runtime-specific invoke seam |
| **deterministic-operation-registry** | Bulk unregister by extension path | extension unload/clear callers explicitly depend on it; tests cover matching removal and missing-ext no-op | **Yes** | lifecycle cleanup is architectural, not incidental | keyed registry substrate with owner-index/bulk-remove support |
| **deterministic-operation-registry** | Registry object mutator API | many callers pass registry object around, but this may still be implementation-level | **Unknown / maybe no** | object identity may be convenient, but semantics live in operations and cleanup behavior | mutable adapter over keyed substrate |

## Design-history evidence pass

These notes remain useful as evidence for why the first-cut contracts looked the way they did at audit time. They are historical input to the unification analysis, not necessarily the final post-migration state for every registry.

| Registry | Design-history evidence | What it strengthens | Impact on current judgement |
|---|---|---|---|
| **tool-registry** | `111-tool-registration-component-extraction/design.md` explicitly preserves `tool-names-in` as the cross-extension registered-name set and `all-tools-in` as first-registration-wins by tool name; preserves rich canonical tool maps including runtime fields; keeps kebab-case validation scoped to extension registration. `111` implementation notes confirm built-in/tool query semantics were intentionally preserved and later hardened with explicit rejection of unregistered `ext-path`. | built-in+extension merged surface is intentional; first-registration-wins read semantics are intentional; extension-path precondition is intentional; validator scope is intentional rather than accidental. | strengthens preserve-yes for merged surface, precedence, and extension registration precondition; reduces uncertainty around those rows. Mutator return shape still looks incidental. |
| **command-registry** | `113-command-registration-component-extraction/design.md` explicitly freezes the first-cut contract: exact `:name` identity, no slash normalization, same-extension replace, cross-extension duplicates allowed, merged lookup/list are first-registration-wins by extension registration order, set-like name queries, non-blank validation, and invalid registration throws. `113` implementation notes confirm these were extracted from live behavior and then made explicit in code/tests. | command duplicate/query semantics were not accidental leftovers; they were deliberately captured as the first-cut contract during extraction. | strengthens preserve-yes for exact-name identity, no slash normalization, merged surface, first-registration-wins reads, and invalid-registration throwing. Same-extension replace remains intentional first-cut behavior even if it may still be a future standardization target. |
| **skill-registry** | `112-skill-registration-component-extraction/design.md` explicitly chose a pure vector/collection component, first-registration-wins ordering, duplicate-ignore semantics, minimal non-blank `:name` validation, and a result contract reporting `:added?`/`:changed?` so `agent-session` can preserve prompt-refresh behavior. Task `173` later refined that first-cut ordering: callers need deterministic skill listing, not registration sequence, so public read/result/model-visible surfaces are canonicalized by exact skill `:name`. `112` implementation notes still confirm `config-compaction` coverage was extended specifically to prove duplicate registration leaves prompt/session state unchanged. | duplicate-ignore and `:changed?` are intentional and caller-visible; first-cut insertion-order preservation was superseded by canonical deterministic listing. | preserves duplicate-ignore and result-map change reporting; replaces preserve-yes for insertion order with canonical-name-order read/result semantics. |
| **prompt-registry** | `114-prompt-contribution-registration-component-extraction/design.md` originally preferred tightening identity validation, but explicitly allowed preserving looser behavior if live code depended on it. `114` implementation notes record that live behavior accepted nil/blank identities via string coercion and that the extraction deliberately preserved that loose contract; canonical sort ownership and patchable fields were also made explicit. The one intentional behavior correction was count reporting, not identity handling. | composite identity, replace/update/unregister semantics, patch contract, and sort contract are intentional. Loose nil/blank coercion is also intentional in the current first cut, but explicitly framed as behavior-preserving compatibility rather than principled design. | strengthens preserve-yes for composite identity, non-throwing update/remove miss behavior, patch semantics, and canonical sort. Reclassifies blank/nil coercion from mere smell to “preserve for now / compatibility-carried”, not “ideal contract”. |
| **workflow-registry** | `115-workflow-registration-component-extraction/design.md` explicitly preserves normalized `:definition-id`, blank/missing id → generated UUID, target-authored definition validation, tuple-shaped lower API, sorted `list-definitions`/`definition-ids`, nil-returning lookup, and throw-on-missing lower remove helper. `115` implementation notes confirm those were preserved intentionally and pushed behind one lower owner. | workflow registry behaviors are strongly intentional first-cut contract choices, especially pure root-state ownership, normalized ids, sorted public reads, and lower tuple API. | strengthens preserve-yes for pure root-state API, sorted reads, normalized lookup, and validation. Blank-id UUID generation and remove-miss throw are no longer weak guesses; they are explicit first-cut preserve decisions, though still possible future redesign targets. |
| **deterministic-operation-registry** | `116-deterministic-operation-registration-component-extraction/design.md` explicitly preserves registry-object API, duplicate rejection, registration-order public queries, nil lookup miss, invoke-miss throwing, and bulk unregister by `ext-path`; later follow-on tasks `170`–`172` refine that first-cut contract by moving canonical storage to shared `root-registry`, keeping duplicate rejection/invoke semantics adapter-owned, and removing adapter-local ordering guarantees in favor of unordered listing/coherence proofs. | duplicate-throw, runtime registry-object model, lifecycle cleanup semantics, and adapter-owned invoke behavior remain deliberate contract; preserved ordering was proven removable. | strengthens preserve-yes for duplicate rejection, invoke-miss throw, bulk unregister, and registry-object boundary significance; weakens any preserve-yes claim for ordering as an essential long-term semantic. |

## Unknowns after design-history pass

This section captures the open questions that remained at the time of the original audit pass. Some were later resolved by completed tasks `165`–`172`; those later resolutions should be read as closing or refining portions of the uncertainty recorded here.

The remaining meaningful unknowns are narrower now:

- whether `tool-registry` mutator return shape is part of a real contract or just parity/convenience
- whether `command-registry` mutator return shape is part of a real contract or just parity/convenience
- whether `tool-registry` same-owner overwrite behavior should be treated as first-cut intentional contract in the same way command replacement was
- whether any of the preserved first-cut workflow behaviors (`blank id -> UUID`, lower remove-miss throw) should remain preserve-yes for a future unification target, versus only preserve-at-adapter/public-API level during migration

## Caller-dependency audit for remaining unknowns

| Question | Caller / evidence | What it shows | Result |
|---|---|---|---|
| Does any caller depend on `tool-registry` mutators returning the registry object? | `components/agent-session/src/psi/agent_session/mutations/extensions.clj` ignores the return from `tool-registry/register-tool-in!` and instead reads post-state through `tool-names-in`; `components/agent-session/src/psi/agent_session/extensions/api.clj` wraps registration in `mutate-local` and ignores the lower return; `extensions/work-on/test/extensions/work_on_test.clj` ignores the return and emits its own result map; direct production callers in `extensions.clj` are thin delegation seams only. | The lower return value is used for side effects on the passed registry, not for semantic data. No production caller appears to branch on or propagate the returned registry object as meaningful information. | **Likely incidental** at the contract level. Safe candidate to preserve only at adapter/API level if a shared substrate wants a different lower result shape. |
| Does any caller depend on `command-registry` mutators returning the registry object? | `components/agent-session/src/psi/agent_session/mutations/extensions.clj` ignores the return from `command-registry/register-command-in!` and projects `command-names-in`; `components/agent-session/src/psi/agent_session/extensions/api.clj` ignores the lower return inside `mutate-local`; command dispatch callers (`commands.clj`, `rpc/events.clj`) only consume later lookup/query helpers. | Same as tools: callers care that registry state changed, not that the mutator returned the registry object. | **Likely incidental** at the contract level. Strong candidate to preserve only for compatibility wrappers if desired. |
| Is `tool-registry` same-owner overwrite behavior actually depended on by callers? | No production caller was found that registers the same tool name twice for the same extension and then depends on replacement semantics. Current direct registrations (`mutations/extensions.clj`, `extensions/api.clj`, workflow bootstrap built-ins) treat registration as fire-and-forget. The lower tests cover cross-extension duplicate merged-read behavior and built-in precedence, but do not pin same-extension replacement the way command tests do. | Caller evidence for same-owner replacement is weak. What *is* clearly depended on is the merged read surface (`all-tools-in`, `tool-names-in`, `get-tool-in`) after registration, especially built-in precedence and first-name-wins visibility. | **Still unknown** as a true semantic requirement. The safer conclusion is that merged-read behavior is required, while same-owner overwrite policy may be substrate-configurable so long as visible read behavior stays unchanged. |
| Is `skill-registry` `:changed?` / `:added?` result data actually behaviorally meaningful? | `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj` was identified in task `112` as using `:changed?` to decide whether to emit `:runtime/refresh-system-prompt`; `components/agent-session/test/psi/agent_session/config_compaction_test.clj` proves first registration returns `{:added? true :changed? true :count 1}` and duplicate registration returns `{:added? false :changed? false :count 1}`, with prompt unchanged and no follow-on refresh events on duplicate. | This return metadata is not just informational; higher orchestration uses change-ness to suppress side effects. | **Required at the public behavior level.** A shared substrate may compute change differently internally, but the exposed change/no-change outcome must be preserved. |
| Should workflow behaviors like blank-id→UUID and remove-miss throw be preserved at substrate level or only adapter/API level? | `115` design explicitly chose these as first-cut lower-registry contract decisions, and `components/workflow-registry/test/psi/workflow_registry/registry_test.clj` proves both. Production consumers (`mutations/canonical_workflows.clj`, `resolvers/workflows.clj`, `psi_tool_workflow.clj`, workflow runtime) rely on normalized lookup/list behavior, but caller evidence for *needing* lower-level throw-vs-report and UUID generation is weaker than the test/design evidence. | These behaviors are currently part of the lower public API, but caller dependence is mostly through adapters that could preserve them even if a future shared substrate used more configurable internals. | **Preserve at current public API; do not assume they must live identically in a future shared substrate.** Good candidate for wrapper-level preservation over a more general pure keyed substrate. |
| Should deterministic-operation registry-object API be treated as semantically required? | `116` design explicitly chose to preserve the runtime registry-object substrate and `-in` naming; production callers in `agent-session.context`, `extensions/runtime_fns.clj`, `extensions.clj`, and workflow invoke runtime pass the registry object around and mutate it in place. | Unlike tool/command, this object model is part of the architectural seam between extension lifecycle and workflow invoke runtime, not just a convenience return shape. | **Likely required for the current lower boundary.** A future deeper unification would need an adapter that preserves object-style ownership, not just helper parity. |

## Updated conclusions after caller-dependency audit

These were the audit’s best conclusions before the later root-registry migration arc completed. Read them as an intermediate checkpoint rather than the final word when later sections provide stronger post-migration evidence.

- **tool-registry mutator return shape**: likely incidental
- **command-registry mutator return shape**: likely incidental
- **tool same-owner overwrite behavior**: still unresolved by callers; treat as lower-policy detail unless more evidence appears
- **skill `:changed?` / `:added?` contract**: definitely behaviorally meaningful
- **workflow blank-id UUID + remove-miss throw**: preserve at current public registry API, but do not treat them as mandatory shared-substrate behavior yet
- **deterministic-operation registry object model**: much more boundary-significant than tool/command mutator return shape

## Aspect-by-aspect registry requirements

This catalog mixes durable behavioral requirements with the audit-time shape of each registry. For registries later migrated onto shared storage, treat the per-aspect bullets as describing either enduring public behavior or the historical baseline that later outcome sections refine.

### 1. Storage / ownership model

- `tool-registry`
  - mutable registry over extension-registry state
  - separate built-in and extension-owned stores
  - exposed as one merged read surface
- `command-registry`
  - mutable registry over extension-registry state
  - separate built-in and extension-owned stores
  - exposed as one merged read surface
- `skill-registry`
  - pure operations over a session-local vector of skill maps
  - not a long-lived registry object
- `prompt-registry`
  - pure operations over a session-local vector of prompt contribution maps
  - not a long-lived registry object
- `workflow-registry`
  - pure operations over root workflow-definition state
- `deterministic-operation-registry`
  - runtime-owned registry object over shared `root-registry` state
  - canonical operation entries now live in shared storage, while object-style host ownership remains adapter-significant

### 2. Identity model

- `tool-registry`
  - identity is tool `:name`
- `command-registry`
  - identity is command `:name` by exact string equality
- `skill-registry`
  - identity is skill `:name`
- `prompt-registry`
  - identity is composite `(:ext-path, :id)`
- `workflow-registry`
  - identity is canonical `:definition-id`
- `deterministic-operation-registry`
  - identity is canonical operation `:id`

### 3. Identity normalization

- `tool-registry`
  - no broad identity normalization beyond validation of tool name
- `command-registry`
  - no normalization; exact input name is identity
  - `"hello"` and `"/hello"` are distinct
- `skill-registry`
  - no normalization beyond validating non-blank `:name`
- `prompt-registry`
  - `ext-path` and `id` are coerced with `str`
  - nil/blank currently preserved as valid after coercion
- `workflow-registry`
  - strings preserved
  - keywords normalized with `name`
  - other values with `str`
  - blank/missing ids generate UUIDs
- `deterministic-operation-registry`
  - canonicalized through operation definition normalization
  - ids must satisfy canonical operation-id rules

### 4. Validation strictness

- `tool-registry`
  - tool name must be kebab-case ASCII
  - registered tool must have `:format-request`
  - extension path must already exist for extension-owned registration
- `command-registry`
  - command name must be present and non-blank
  - extension path must already exist for extension-owned registration
- `skill-registry`
  - skill name must be present and non-blank
- `prompt-registry`
  - very permissive first-cut validation
  - identity is string-coerced rather than rejected
  - patchable fields constrained
- `workflow-registry`
  - definition must satisfy target-authored workflow-definition predicate
- `deterministic-operation-registry`
  - strict definition validation
  - strict canonical id validation

### 5. Duplicate / conflict policy on registration

- `tool-registry`
  - merged reads are first-name-wins across owners
  - same-owner overwrite behavior is still less well evidenced than the visible merged-read contract
- `command-registry`
  - same-extension duplicate replaces
  - cross-extension duplicates allowed
- `skill-registry`
  - duplicate skill names are ignored
  - first registration wins
- `prompt-registry`
  - registering same `(ext-path,id)` replaces prior contribution
- `workflow-registry`
  - registering same normalized `definition-id` replaces prior definition
- `deterministic-operation-registry`
  - duplicate `:id` registration throws

### 6. Cross-owner duplicate policy

- `tool-registry`
  - built-ins and extensions may collide by name
  - merged surface resolves by precedence/first-seen policy
- `command-registry`
  - built-ins and extensions may collide by name
  - merged surface resolves by precedence/first-seen policy
- `skill-registry`
  - not owner-partitioned; single ordered collection
- `prompt-registry`
  - ownership is part of identity via `ext-path`
  - different owners can have same `id` without collision
- `workflow-registry`
  - no owner partitioning in first-cut registry contract
- `deterministic-operation-registry`
  - ownership exists via `:ext-path`, but id uniqueness is global in registry

### 7. Ordering of stored/read results

- `tool-registry`
  - built-ins listed first, then extensions in extension registration order
  - first-seen name wins in merged reads
- `command-registry`
  - built-ins listed first, then extensions in extension registration order
  - first-seen name wins in merged reads
- `skill-registry`
  - canonical deterministic `:name` order on public read/result surfaces
- `prompt-registry`
  - stored order preserved for raw collection
  - canonical sorted order available by `[priority ext-path id]`
- `workflow-registry`
  - public listings sorted by `:definition-id`
- `deterministic-operation-registry`
  - unordered membership/count coherence is preserved through `operation-ids-in` and `all-operations-in`
  - callers should not depend on insertion order

### 8. Built-in support

- `tool-registry`
  - yes; built-ins are first-class and share visible surface with extension tools
- `command-registry`
  - yes; built-ins are first-class and share visible surface with extension commands
- `skill-registry`
  - no built-in registry split in current contract
- `prompt-registry`
  - no built-in split; ownership is just part of contribution identity
- `workflow-registry`
  - no built-in split in the registry itself
- `deterministic-operation-registry`
  - no built-in split; source/owner metadata may exist, but not as a built-in dual-store model

### 9. Built-in / owner precedence

- `tool-registry`
  - built-ins win on collision with extension tools
- `command-registry`
  - built-ins win on collision with extension commands
- `skill-registry`
  - not applicable
- `prompt-registry`
  - not applicable in that form; identity includes owner
- `workflow-registry`
  - not applicable
- `deterministic-operation-registry`
  - not applicable in built-in-vs-extension precedence form; duplicates are rejected globally

### 10. Lookup miss behavior

- `tool-registry`
  - `get-tool-in` returns `nil` on miss
- `command-registry`
  - `get-command-in` returns `nil` on miss
- `skill-registry`
  - `find-skill` returns `nil` on miss
- `prompt-registry`
  - `find-contribution` returns `nil` on miss
- `workflow-registry`
  - `workflow-definition` returns `nil` on miss
- `deterministic-operation-registry`
  - `get-operation-in` returns `nil` on miss

### 11. Missing-target behavior for active operations

- `tool-registry`
  - registry itself does not define active invoke behavior here
- `command-registry`
  - dispatch layer handles command miss; registry lookup itself is nil-returning
- `skill-registry`
  - invocation helpers above the registry treat missing skill as nil/not found
- `prompt-registry`
  - update/remove on missing contribution are non-throwing no-ops
- `workflow-registry`
  - remove on missing definition throws at lower registry boundary
- `deterministic-operation-registry`
  - `invoke-operation-in` throws structured error when operation id is missing

### 12. Removal support

- `tool-registry`
  - no symmetric public remove API in current registry surface
- `command-registry`
  - no symmetric public remove API in current registry surface
- `skill-registry`
  - no unregister in current contract
- `prompt-registry`
  - targeted unregister by `(ext-path,id)`
- `workflow-registry`
  - targeted remove by `definition-id`
- `deterministic-operation-registry`
  - bulk unregister by `ext-path`

### 13. Removal miss policy

- `tool-registry`
  - not applicable in current public API
- `command-registry`
  - not applicable in current public API
- `skill-registry`
  - not applicable
- `prompt-registry`
  - missing remove is non-throwing
  - returns unchanged collection with status flags
- `workflow-registry`
  - missing remove throws
- `deterministic-operation-registry`
  - missing owner on bulk unregister is tolerated
  - operation set remains unchanged

### 14. Update / patch semantics

- `tool-registry`
  - no separate patch API
- `command-registry`
  - no separate patch API
  - same-extension re-register replaces
- `skill-registry`
  - no patch API
  - duplicate re-register is ignored, not merged
- `prompt-registry`
  - explicit patch/update API
  - only selected fields are patchable: `:section`, `:content`, `:priority`, `:enabled`
  - identity and `:created-at` are not patchable
  - unknown patch keys ignored
- `workflow-registry`
  - no patch API
  - re-register replaces whole definition
- `deterministic-operation-registry`
  - no patch API
  - duplicate re-register is rejected

### 15. Canonical read surfaces

- `tool-registry`
  - `tool-names-in`
  - `all-tools-in`
  - `get-tool-in`
  - merged across built-ins and extensions
- `command-registry`
  - `command-names-in`
  - `all-commands-in`
  - `get-command-in`
  - merged across built-ins and extensions
- `skill-registry`
  - `all-skills`
  - `find-skill`
  - `skill-names`
  - `skill-count`
- `prompt-registry`
  - `all-contributions`
  - `find-contribution`
  - `contribution-count`
  - `sort-contributions`
  - register/update/unregister result surfaces
- `workflow-registry`
  - `workflow-definition`
  - `list-definitions`
  - `definition-ids`
  - path helpers and register/remove
- `deterministic-operation-registry`
  - `get-operation-in`
  - `all-operations-in`
  - `operation-ids-in`
  - `operation-count-in`
  - invoke seam

### 16. Public result contract shape

- `tool-registry`
  - mutators return registry object
  - likely compatibility/convenience, not strongly semantically required
- `command-registry`
  - mutators return registry object
  - likely compatibility/convenience, not strongly semantically required
- `skill-registry`
  - returns result map including `:skills`, `:skill`, `:added?`, `:changed?`, `:count`
- `prompt-registry`
  - returns result maps with updated collection plus operation status
  - includes values like `:registered?`, `:replaced?`, `:updated?`, `:removed?`, `:changed?`, `:count`
- `workflow-registry`
  - lower API is tuple-shaped
  - register returns `[state id stored-definition]`
  - remove returns `[state removed-definition]`
- `deterministic-operation-registry`
  - mutators return registry object
  - registry-object API remains intentional, but canonical storage now lives below it in shared `root-registry`

### 17. Change / no-change reporting

- `tool-registry`
  - no explicit change-report contract in lower API
- `command-registry`
  - no explicit change-report contract in lower API
- `skill-registry`
  - explicit `:added?` / `:changed?`
  - behaviorally meaningful because orchestration uses it to suppress prompt refresh on duplicate registration
- `prompt-registry`
  - explicit change/status reporting for register/update/unregister
- `workflow-registry`
  - no lower `:changed?` contract
  - change is implicit in returned next state or exception behavior
- `deterministic-operation-registry`
  - no explicit `:changed?` result map
  - change is implicit in registry mutation or exception behavior

### 18. Ownership metadata in stored entries

- `tool-registry`
  - canonical tool maps preserve internal fields like `:source` and `:ext-path`
  - extension ownership and built-in provenance both matter
- `command-registry`
  - stored command maps preserve incoming keys
  - extension ownership may be projected on read/list surfaces
- `skill-registry`
  - registry accepts already-constructed skill maps
  - ownership/source fields may be present but are not registry identity
- `prompt-registry`
  - ownership is explicit in stored canonical shape via `:ext-path`
- `workflow-registry`
  - no owner metadata requirement is central to current registry identity
- `deterministic-operation-registry`
  - `:ext-path` is important for lifecycle cleanup
  - `:source` is also preserved in canonical shape

### 19. Tolerance vs strictness posture

- `tool-registry`
  - fairly strict at registration boundary
- `command-registry`
  - minimal but explicit strictness
- `skill-registry`
  - minimal strictness
- `prompt-registry`
  - most tolerant / compatibility-preserving
- `workflow-registry`
  - strict on definition shape
  - tolerant on id presence because blank/missing ids normalize
- `deterministic-operation-registry`
  - strictest overall

### 20. Architectural role of the registry

- `tool-registry`
  - extension capability catalog with built-in overlay and merged external surface
- `command-registry`
  - extension command catalog with built-in overlay and merged dispatch/discovery surface
- `skill-registry`
  - session-local skill collection with canonical deterministic `:name` ordering for prompt/discovery/read-result use
- `prompt-registry`
  - session-local ordered collection of extension prompt contributions used by prompt assembly
- `workflow-registry`
  - canonical root-state registry of workflow definitions for tooling, resolvers, and runtime lookup
- `deterministic-operation-registry`
  - runtime invoke-target registry for workflow `:invoke` steps plus extension-lifecycle cleanup

## Initial convergence clusters

These clusters reflect the audit’s original decomposition before the follow-on migration tasks completed. The later outcome section supersedes them where direct adoption versus adapter-backed shared-storage adoption became concrete.

### Group A — strongest shared-implementation candidates

| Registry | Why similar | Blocking differences |
|---|---|---|
| tool-registry | extension-owned, mutable, merged read surface, built-ins, first-wins reads | tool-specific validation and `:format-request`; tool names stricter |
| command-registry | extension-owned, mutable, merged read surface, built-ins, first-wins reads | command names looser; no normalization layer |

### Group B — shared substrate candidates, but not identical implementations

| Registry | Why similar | Blocking differences |
|---|---|---|
| workflow-registry | keyed registry, normalize identity, register/list/remove, pure | replacement semantics, sorted by id, root-state tuples |
| deterministic-operation-registry | keyed registry, normalize identity, register/list/remove-ish, adapter-backed shared storage | mutable object, duplicate throws, invoke seam, bulk unregister by ext-path |

### Group C — partial helper sharing only

| Registry | Why similar | Blocking differences |
|---|---|---|
| skill-registry | pure skill collection, name identity, canonical name-sorted public listing | duplicate ignore, no removal, simple semantics |
| prompt-registry | pure ordered collection, register/find/count | composite identity, update, unregister, canonical sort, timestamps |

## Migration guidance added after 167/168

Recent command-registry (`167`) and tool-registry (`168`) migrations exposed the main operational risk in registry unification work: the storage move itself can be straightforward while stale read seams survive above the new authoritative owner.

The concrete failure mode was:

- registry ownership moved to a lower shared substrate
- public read helpers were mostly updated
- one higher introspection seam still read legacy extension-local state
- focused contract tests existed but the stale seam still survived until full-suite verification exercised it

That yields an explicit migration rule for future registry work:

- a registry migration is not complete when writes and primary reads pass
- it is complete only when every caller-visible read surface and introspection/projection seam has stopped reading legacy local storage

### Registry migration checklist

Use this checklist for future registry migrations, as validated by the completed `workflow-registry` and deterministic-operation follow-ons:

1. **Name the new authoritative owner**
   - identify the single post-migration storage owner/substrate
   - record whether compatibility behavior is preserved in the substrate or only in an adapter/wrapper

2. **Enumerate all write seams**
   - direct registration/update/remove APIs
   - built-in registration paths
   - extension/runtime registration paths
   - bulk cleanup/unload paths

3. **Enumerate all read seams**
   - primary lookup/list/name APIs
   - resolver projections
   - mutation result projections
   - introspection/detail helpers
   - prompt/session/bootstrap rebuilding paths
   - any derived counts, summaries, or provenance projections

4. **Classify each read seam**
   - authoritative post-migration source
   - compatibility fields that must still be projected
   - ordering/precedence expectations
   - miss/throw behavior

5. **Add migration-guard tests at the seam level**
   - one focused test for the main public API contract
   - one focused test for introspection/detail/projection coherence
   - where possible, assert behavior through the higher consumer seam rather than only through the migrated registry itself

6. **Verify legacy storage is no longer read**
   - identify any legacy local maps/fields that used to be authoritative
   - update or remove higher code paths that still consult them
   - explicitly prove replacement with a focused regression test when the seam is subtle

7. **Run focused and full-suite verification before close**
   - focused lower-component tests
   - focused higher-consumer tests
   - full `bb test`
   - lint where relevant

### Outcomes after 169–172

The follow-on work predicted here is now complete enough to refine this audit with concrete migration outcomes:

- `169` migrated `workflow-registry` onto `root-registry` as a thin adapter while preserving the public workflow-registry contract.
- `170` aligned the shared lower contract for future adopters by splitting lower duplicate semantics into explicit duplicate-rejecting `insert` versus replace-capable `register`, rather than forcing every adopter through one conflict policy.
- `171` migrated `deterministic-operation-registry` onto shared `root-registry` storage while preserving adapter-owned duplicate-throw, invoke-miss-throw, and extension-cleanup behavior.
- `172` removed deterministic-operation adapter-local registration-order state and narrowed its public listing contract to unordered membership/count coherence rather than preserved insertion order.

These completed tasks sharpen the audit in three important ways:

1. `workflow-registry` was correctly identified as the next root-registry-style migration target.
2. `deterministic-operation-registry` turned out to be a viable shared-storage adopter after semantic alignment, but only as an adapter-backed migration rather than a direct root-registry-style semantic fit.
3. registration order is now more clearly classified as a compatibility surface that may be removable at the adapter boundary rather than something a shared lower substrate should preserve by default.
4. task `173` applied that rule to `skill-registry`: no real insertion-order dependency was found, so skill read/result and model-visible listing surfaces now use canonical exact-`:name` ordering while preserving duplicate-ignore, exact lookup, and `:added?` / `:changed?` behavior.

### Updated migration conclusions

#### `workflow-registry`

The audit prediction was confirmed.

- It now sits on `root-registry` shared storage.
- Its adapter preserves workflow-specific semantics:
  - id normalization
  - blank-id UUID generation
  - target-authored validation
  - replace-on-register
  - sorted public reads
  - nil lookup miss
  - throw-on-miss removal
  - tuple-shaped public return contract
- The migration also confirmed that preserved compatibility paths such as `[:workflows :definitions]` can remain as explicit projection/coherence surfaces while lower authoritative ownership moves to shared storage.

#### `deterministic-operation-registry`

The earlier “defer” judgement needs refinement.

- It was correct that deterministic operations were not a direct semantic fit for the original shared keyed-registry target.
- But after `170` introduced clearer lower conflict semantics and `172` removed adapter-local registration-order guarantees, `171` successfully moved canonical operation storage into `root-registry`.
- The registry remains adapter-heavy and runtime-shaped:
  - duplicate registration still throws publicly
  - invoke miss still throws publicly
  - owner-scoped cleanup remains required
  - runtime-owned registrations still use adapter-owned host conventions
  - extension-facing projection seams remain synchronized higher-level surfaces rather than canonical storage

So the revised conclusion is:

- `deterministic-operation-registry` can share **storage substrate** with `root-registry`
- but it should still be treated as an **adapter-backed adopter with material boundary semantics**, not as a direct semantic model for the shared lower component

### Updated convergence guidance

- **Direct/shared-substrate adopter pattern confirmed**: `command-registry`, `tool-registry`, `workflow-registry`
- **Adapter-backed shared-storage adopter pattern confirmed**: `deterministic-operation-registry`
- **Still likely helper/substrate-only candidates**: `skill-registry`, `prompt-registry`

### Additional migration rules learned from 169–172

Add the following rules to future registry-unification work:

1. **Separate storage adoption from semantic adoption**
   - a registry may migrate to shared storage without becoming a direct semantic match for the lower component
   - preserve domain-specific miss/conflict/result behavior in the adapter when needed

2. **Do not overfit lower semantics to one adopter**
   - `170` showed that lower shared operations may need distinct contracts such as duplicate-rejecting `insert` and replace-capable `register`
   - avoid forcing incompatible registries to share one conflict policy when adapters can translate appropriately

3. **Treat ordering as opt-in, not default substrate behavior**
   - `172` showed preserved ordering can be an adapter-local compatibility choice rather than a property worth baking into shared storage
   - when callers only need membership/count/coherence, prefer unordered contracts

4. **Projection seams may remain intentionally derived**
   - a migration can leave higher compatibility projections in place so long as canonical ownership is singular and seam-level coherence is tested
   - this was important for workflow canonical-path compatibility and deterministic-operation extension introspection surfaces

5. **Prove higher seams explicitly after storage moves**
   - `169` and `171` both confirmed that lower-component success is insufficient without tests proving resolver/introspection/helper/runtime coherence above the migrated store

### Revised sequencing guidance

1. Use this task as the source-of-truth audit for required-vs-incidental registry semantics.
2. When adopting new registries, decide first whether they are:
   - direct semantic adopters of `root-registry`, or
   - adapter-backed shared-storage adopters.
3. Prefer removing adapter-owned ordering guarantees when caller-visible behavior does not truly require them.
4. Keep future migrations focused on single registries plus their higher read/projection seams rather than broad simultaneous normalization.

## Acceptance

This task is complete when there is a durable, evidence-backed answer to:

- which registry behaviours are required
- which are incidental
- which registries can share one implementation
- which should share only lower substrate/helper code
- which should remain separate
- what migration checklist should govern future registry unification work so stale read seams are caught before task close
- which registry is the next root-registry-style migration target and why
