# Implementation notes

## 2026-05-30 design ambiguity review

Found two actionable ambiguities after reviewing the design and current UI/query seams: the design does not name the concrete adapter-to-core capability provider contract the resolver will call, and it does not define the authoritative invocation route when a descriptor contains frontend-specific invocation data but generic invocation remains optional.

## 2026-05-30 ambiguity follow-up

Completed both ambiguity follow-ups in `design.md`:

- Defined the adapter-to-core UI capability provider as an optional runtime-context provider returning serialisable `:psi.ui/...` data on demand, with missing providers and provider errors mapped to explicit headless/unavailable query results.
- Defined the authoritative invocation route as a core-owned dispatch UI request event/subscription path; descriptors carry invocation data declaratively, and adapters such as Emacs translate that data into frontend-specific behaviour.

## 2026-05-30 design inconsistency review

Found one actionable inconsistency: `design.md` mixes unnamespaced `:ui.capability/make-visible` examples/extension guidance with the resolved `:psi.ui.capability/make-visible` vocabulary used by descriptors and design decisions, leaving extension authors/tests with two possible capability keywords.

## 2026-05-30 inconsistency follow-up

Completed the vocabulary alignment follow-up in `design.md`: normative examples and extension usage now use `:psi.ui.capability/make-visible`, and the older `:ui.capability/...` shorthand is explicitly non-normative and not a contract for implementation, tests, or extension guidance.

## 2026-05-30 design ambiguity review

Found two new actionable ambiguities after re-reading `design.md` and the current UI/query seams: unsupported/headless/provider-error return semantics for `:psi.ui/make-visible-action` remain underspecified, and the UI action request contract still names a route without an exact payload/submission/result shape if invocation is implemented in this slice.


## 2026-05-30 ambiguity follow-up

Completed both new ambiguity follow-ups in `design.md`:

- Defined exact unsupported/headless/provider-error semantics for `:psi.ui/actions` and `:psi.ui/make-visible-action`: root attrs remain present, root actions stay empty when unsupported/error, the make-visible convenience attr always returns a stable descriptor, and capability presence is limited to currently supported capabilities. Added required unavailable reason/message fields and the minimum reason vocabulary.
- Specified the concrete side-effecting UI action request contract for this slice if invocation is implemented: dispatch event `:psi.ui/request-action`, required request keys, extension-facing submission boundary, and acknowledgement/error result shape.

## 2026-05-30 design inconsistency review

Found one new actionable inconsistency: `design-decisions.md` still says `:psi.ui/make-visible-action` may be nil and uses an optional string `:psi.ui.action/unavailable-reason`, while `design.md` now requires `:psi.ui/make-visible-action` to always return an available/unavailable descriptor and requires machine-readable `:psi.ui.unavailable.reason/...` plus an unavailable message.

## 2026-05-30 inconsistency follow-up

Completed the newly added inconsistency follow-up in `design-decisions.md`: `:psi.ui/make-visible-action` now always returns a descriptor, unavailable descriptors require a `:psi.ui.unavailable.reason/...` keyword plus `:psi.ui.action/unavailable-message`, and the unsupported-state guidance now matches `design.md`.

## 2026-05-30 design ambiguity review

Found two new actionable ambiguities after re-reading `design.md` against the current session/UI/RPC seams: the runtime-scoped make-visible descriptor does not define how the active UI/session/buffer identity is selected when no session id is an input to the query, and the provider-error path mentions invalid provider results without defining the normalization/validation boundary that distinguishes defaultable missing data from provider failure.

## 2026-05-30 ambiguity follow-up

Completed both newly added ambiguity follow-ups in `design.md`:

- Defined active UI/session identity for runtime-scoped discovery: no session id is required by the resolver; providers use the attached frontend's active/focused session identity (`:focus-session-id` / active context snapshot for TUI, current Psi buffer/session state for Emacs/RPC), include session/runtime correlation in invocation data when needed, and may use nil session id only for runtime-global actions.
- Specified provider output normalization and validation: defaultable safe fields, required descriptor fields, serialisable pure-data constraints, supported invocation kinds, unavailable descriptor requirements, duplicate-action handling, and invalid-output mapping to provider-error unavailable semantics.

## 2026-05-30 design inconsistency review

Found one new actionable inconsistency: `resolved-design-questions.md` still describes unsupported states as using optional unavailable descriptors for diagnostics, while `design.md` and `design-decisions.md` now require `:psi.ui/make-visible-action` to always return an available or unavailable descriptor.

## 2026-05-30 inconsistency follow-up

Completed the newly added inconsistency follow-up in `resolved-design-questions.md`: unsupported states now match the current unavailable make-visible contract, with `:psi.ui/make-visible-action` always returning a descriptor and unavailable descriptors required rather than optional diagnostics.

## 2026-05-30 design ambiguity review

Found two new actionable ambiguities after re-reading `design.md` against the existing UI/query/frontend-action seams: `:psi.ui/actions` semantics for unsupported make-visible are not singular because the design says missing/no-attached cases return `[]` but attached unsupported only omits an available descriptor, leaving open whether unavailable descriptors may appear in the general actions collection; and provider capability/action coherence is underspecified when a provider reports `:psi.ui.capability/make-visible` without a matching available action descriptor, or an available action whose capability is absent.

## 2026-05-30 ambiguity follow-up

Completed both newly added ambiguity follow-ups in `design.md`:

- Defined canonical `:psi.ui/actions` membership as available-actions-only after resolver normalization. Unavailable make-visible descriptors are reserved for `:psi.ui/make-visible-action`; missing-provider, no-attached-UI, unsupported, and provider-error cases keep `:psi.ui/actions` empty unless other available actions exist.
- Specified capability/action coherence rules: available actions require their capability to be present, passive capabilities need not have actions, and make-visible is action-backed so the capability and exactly one available make-visible descriptor must appear together. Mismatches map the whole UI capability result to provider-error semantics.

## 2026-05-30 design inconsistency review

Found one new actionable inconsistency: `design.md` says the UI capability/action model is owned by core/runtime state, but later requirements and acceptance criteria say advertised capabilities/actions must be derived on demand and not stored in root state. The ownership wording should be aligned to core/runtime contract/provider ownership rather than implying persisted root-state ownership.

## 2026-05-30 inconsistency follow-up

Completed the newly added model-ownership wording follow-up in `design.md`: the UI capability/action model is now described as core/runtime-owned at the query/provider contract boundary, while advertised capabilities/actions are explicitly derived on demand and not stored or cached in root state.


## 2026-05-30 design ambiguity review

Found one new actionable ambiguity after re-reading `design.md` against runtime/RPC/TUI attachment seams: the provider contract says an optional provider lives in runtime context, but adapter-local UI attachment/focus state is created and owned outside the agent-session ctx (RPC connection state after ctx creation, TUI UI state), so the design does not define the provider installation/lifetime/update path that makes the single active UI provider accurately reflect attached/detached frontend state.

## 2026-05-30 ambiguity follow-up

Completed the newly added provider installation/lifetime follow-up in `design.md`: adapter/runtime wiring owns late installation/replacement of the runtime-context UI capability provider, providers are looked up at query time rather than captured at ctx creation, RPC/Emacs and TUI update/clear the provider as UI attachment state appears or disappears, detach/shutdown maps to no-provider or no-attached-UI semantics, and a single active-provider slot/handoff enforces the single-active-UI rule for this slice.

## 2026-05-30 design inconsistency review

Found one new actionable inconsistency after re-reading `design.md` against current extension dispatch/permission seams: the design requires UI action invocation to be available through a core-owned dispatch/request path without extension permissions, but also says extensions submit through the existing core dispatch/effect boundary, where extension-origin events can be permission-gated by manifest `allowed-events`. The design should explicitly define how `:psi.ui/request-action` remains permission-free for this slice or limit the slice to descriptor-only queryability and a follow-up for permission-aware invocation.

## 2026-05-30 inconsistency follow-up

Completed the newly added invocation/permission follow-up in `design.md`: extension submission now uses a dedicated constrained UI action request helper for `:psi.ui/request-action`, explicitly outside manifest `allowed-events` for this slice while still validating action id, descriptor availability, invocation shape, supported kind, active-provider coherence, and session/runtime correlation. If that helper is not practical during implementation, the slice must remain descriptor-only and defer permission-aware invocation to a follow-up rather than using generic permission-gated dispatch.

## 2026-05-30 design ambiguity review

Found one new actionable ambiguity after re-reading `design.md`, the extension API docs, and current extension UI/query seams: the design says extension guidance should avoid/replace UI-type branching, while existing extension APIs and docs expose `:ui-type` / `:psi.agent-session/ui-type`; it does not define whether those legacy UI-type surfaces remain supported diagnostic compatibility, are deprecated, or should be hidden from extension guidance.


## 2026-05-30 ambiguity follow-up

Completed the newly added legacy UI-type compatibility follow-up in `design.md`: existing `:ui-type`, `:psi.agent-session/ui-type`, and session `:ui-type` surfaces remain supported as diagnostic/compatibility data, while new extension-authoring guidance for UI behaviour must use capability/action queries in the `:psi.ui/...` namespace. Any future deprecation/removal is explicitly out of scope for this task and belongs in a separate migration task.

## 2026-05-30 design inconsistency review

Found two new actionable inconsistencies after re-reading `design.md` against referenced design artifacts: `resolved-design-questions.md` still states invocation has no new permissions without carrying the design's dedicated-helper-or-descriptor-only constraint, and `design-decisions.md` says implementation may start with only `:emacs-command` despite `design.md` requiring resolver validation to accept the full supported invocation-kind vocabulary for descriptors in this slice.

## 2026-05-30 inconsistency follow-up

Completed both newly added inconsistency follow-ups:

- Aligned `resolved-design-questions.md` with `design.md` for invocation permissions: query remains permission-free, but side-effecting invocation is permission-free only through the dedicated constrained `:psi.ui/request-action` helper outside manifest `allowed-events`; otherwise the slice must expose descriptors only and defer permission-aware submission.
- Aligned `design-decisions.md` invocation-kind guidance with `design.md`: resolver descriptor validation must recognize all supported design invocation kinds (`:emacs-command`, `:ui-event`, `:bash-command`, `:mutation`) in this slice, even if only Emacs initially advertises an available make-visible action.

## 2026-05-30 design ambiguity review

Found one new actionable ambiguity after re-reading `design.md`, `design-decisions.md`, and current EQL/provider seams: descriptor validation must recognize `:emacs-command`, `:ui-event`, `:bash-command`, and `:mutation`, but the design does not define the per-invocation-kind required keys/value shapes beyond the Emacs example, leaving provider validation and extension request construction underdetermined for the non-Emacs supported kinds.

## 2026-05-30 ambiguity follow-up

Completed the newly added per-kind invocation schema follow-up across `design.md`, `design-decisions.md`, and `resolved-design-questions.md`: `:emacs-command` requires a non-empty command string, `:ui-event` requires a namespaced event keyword plus optional serialisable payload map, `:bash-command` requires a non-empty argv vector and forbids shell strings, and `:mutation` requires a qualified mutation symbol plus serialisable params. Provider-side malformed invocation data maps to provider-error unavailable semantics, while malformed/stale submitted request data is rejected at request time.

## 2026-05-30 design inconsistency review

Found one new actionable inconsistency after re-reading `design.md` against its invocation/request schema: the active-session identity section says a runtime-global advertised action may carry `:psi.ui.request/session-id nil`, but descriptors/invocation data use `:psi.ui.invocation/session-id` and `:psi.ui.request/session-id` exists only on the later request payload. This leaves the descriptor/request boundary with two possible places for session correlation.


## 2026-05-30 inconsistency follow-up

Completed the newly added session/runtime correlation vocabulary follow-up in `design.md`: descriptor invocation data now uses only `:psi.ui.invocation/session-id` / `:psi.ui.invocation/runtime-id` or omits session id for runtime-global actions, while `:psi.ui.request/session-id` / `:psi.ui.request/runtime-id` are explicitly request-payload keys copied from descriptor invocation data by the later `:psi.ui/request-action` submission path.

## 2026-05-30 design ambiguity review

Found two new actionable ambiguities after re-reading `design.md` against the current EQL/provider/frontend-mode seams: provider-error diagnostics mention optional `:psi.ui/diagnostic` data without declaring whether it is an EQL attr/output/discovery surface, and console/headless unavailable semantics leave console without a real make-visible mechanism able to be represented as either no provider, no attached UI, or attached UI with unsupported capability.


## 2026-05-30 ambiguity follow-up

Completed both newly added ambiguity follow-ups in `design.md`:

- Made `:psi.ui/diagnostic` a first-class root-queryable/discoverable EQL output only for provider-error troubleshooting, with bounded serialisable text, default redaction requirements, nil/absent normal-case semantics, and guidance that extensions branch on reason keywords instead of diagnostics.
- Clarified console/headless classification: a console runtime with a valid `:ui-type` but no real visibility mechanism is an attached UI with unsupported make-visible (`:psi.ui/type :console`, `:psi.ui/available? true`, no make-visible capability/action, and an unavailable make-visible descriptor reasoned as `:psi.ui.unavailable.reason/unsupported-capability`), while no provider remains headless/missing-provider and installed-but-unusable state remains no-attached-UI.

## 2026-05-30 design inconsistency review

Found one new actionable inconsistency after re-reading `design.md` against referenced extension-authoring docs: `design.md` makes capability/action `:psi.ui/...` queries the normative UI-behaviour contract and says docs must not recommend UI-type branching, but `doc/architecture.md` still explicitly recommends `:psi.agent-session/ui-type` for "runtime UI surface detection (extension/UI branching)".

## 2026-05-30 inconsistency follow-up

Completed the newly added extension-authoring documentation follow-up in `doc/architecture.md`: EQL introspection guidance now recommends the `:psi.ui/...` capability/action query surface for extension UI behaviour, says to branch on `:psi.ui.capability/...` and action descriptor availability, and reclassifies `:psi.agent-session/ui-type` as runtime UI type diagnostics/compatibility data rather than the normative extension-authoring contract for invokable UI behaviour.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after re-reading `design.md`, referenced design artifacts/docs, and current UI/query/frontend seams. The design now defines provider ownership/lifetime, runtime-scoped query semantics, descriptor/request boundaries, unsupported/error cases, invocation schemas, legacy UI-type compatibility, and console/headless classification clearly enough for implementation planning.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items remained in `design-steps.md`; no `design.md` changes were needed and no ambiguity steps were left blocked.

## 2026-05-30 design inconsistency review

No new actionable inconsistency feedback after re-reading `design.md`, referenced task design artifacts, extension-authoring docs, and current UI/query/frontend seams. Existing aligned decisions cover capability/action vocabulary, provider/query ownership, unsupported/error semantics, invocation/request boundaries, permission constraints, legacy UI-type compatibility, and referenced documentation guidance consistently enough for planning.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items remained in `design-steps.md`; no `design.md` changes were needed and no inconsistency steps were left blocked.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after re-reading `design.md`, referenced task design artifacts/docs, and current UI/query/frontend seams. The design remains clear on provider ownership/lifetime, runtime-scoped query semantics, unsupported/error semantics, capability/action coherence, descriptor/request boundaries, invocation schemas, permission constraints, legacy UI-type compatibility, and console/headless classification.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity design steps are already complete. No `design.md` change was needed, and no ambiguity follow-up item remains blocked.

## 2026-05-30 design inconsistency review

Found one new actionable inconsistency after re-reading `design.md`, `design-decisions.md`, `doc/extensions.md`, and `doc/extension-api.md`: the design makes `:psi.ui/...` capability/action queries the normative extension-authoring surface and explicitly lists both docs for update, but `doc/extensions.md` still documents only existing `:psi.ui/*` contribution snapshot attrs and `doc/extension-api.md` has no UI capability/action guidance, leaving referenced extension docs inconsistent with the designed contract.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after re-reading `design.md`, referenced task design artifacts/docs, and current extension UI/query/frontend seams. The remaining unchecked `design-steps.md` item is a documentation-alignment inconsistency follow-up, not an ambiguity; no new ambiguity follow-up items were added.

## 2026-05-30 ambiguity follow-up execution

Completed the newly added documentation-alignment follow-up in `design-steps.md`: `doc/extensions.md` now documents the queryable UI capability/action surface separately from UI contribution snapshots, and `doc/extension-api.md` now documents the extension-facing `:psi.ui/...` attrs, capability/action branching, unavailable make-visible descriptor semantics, and compatibility-only role of UI-type surfaces. No ambiguity follow-up item remains blocked.

## 2026-05-30 design inconsistency review

No new actionable inconsistency feedback after re-reading `design.md`, referenced task design artifacts, extension-authoring docs, and current UI/query/frontend seams. The design remains internally aligned on capability/action vocabulary, provider/query ownership, unavailable/error semantics, invocation/request boundaries, permission constraints, legacy UI-type compatibility, docs guidance, and console/headless classification.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md`; all inconsistency design steps are already complete. No `design.md` change was needed, and no inconsistency follow-up item remains blocked.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after re-reading `design.md`, referenced task design artifacts/docs, and current UI/query/frontend seams. The design remains clear enough for implementation planning on provider ownership/lifetime, runtime-scoped EQL attrs, descriptor validation, unavailable/error semantics, invocation/request boundaries, permission constraints, legacy UI-type compatibility, and docs expectations.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity design steps remain complete. No `design.md` change was needed, and no ambiguity follow-up item remains blocked.

## 2026-05-30 design inconsistency review

No new actionable inconsistency feedback after re-reading `design.md`, referenced task design artifacts, extension-authoring docs, and current UI/query/frontend seams. The design remains internally consistent on `:psi.ui/...` capability/action vocabulary, provider/query ownership, unavailable/error semantics, invocation/request boundaries, permission constraints, legacy UI-type compatibility, and documentation guidance.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md`; all inconsistency design steps remain complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after re-reading `design.md`, `design-decisions.md`, `resolved-design-questions.md`, referenced extension docs, and current resolver/runtime/frontend seams. Existing design text is specific enough for implementation planning on provider lookup/lifetime, EQL attrs/discovery, unavailable/provider-error normalization, invocation schemas, request/permission boundary, UI-type compatibility, and console/headless classification.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity design steps remain complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design inconsistency review

No new actionable inconsistency feedback after re-reading `design.md`, `design-decisions.md`, `resolved-design-questions.md`, referenced extension-authoring docs (`doc/extensions.md`, `doc/extension-api.md`, `doc/architecture.md`), and current UI/query/frontend seams. The design remains internally and externally aligned on runtime-scoped `:psi.ui/...` attrs, provider-derived capabilities/actions, available-only `:psi.ui/actions`, stable unavailable make-visible descriptors, invocation-kind schemas, permission-free query vs constrained/optional invocation, legacy UI-type compatibility, and console/headless/provider-error semantics.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md`; all inconsistency design steps remain complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after this independent pass over `design.md`, `design-decisions.md`, `resolved-design-questions.md`, extension docs, and current UI/query/runtime seams. The design already specifies the runtime-scoped provider/query contract, active-provider lifetime, unavailable/error normalization, action descriptor validation, invocation/request boundaries, UI-type compatibility, documentation expectations, and frontend mode classification sufficiently for planning.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity design steps remain complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design inconsistency review

No new actionable inconsistency feedback after re-reading `design.md`, `design-decisions.md`, `resolved-design-questions.md`, referenced extension-authoring docs (`doc/extensions.md`, `doc/extension-api.md`, `doc/architecture.md`), and current UI/query/frontend seams. The design remains internally and externally aligned on `:psi.ui/...` capability/action vocabulary, provider-derived runtime query ownership, unavailable/error normalization, available-only actions, stable make-visible descriptors, invocation schemas, constrained/optional request submission, legacy UI-type compatibility, and console/headless classification.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md`; all design follow-up steps are already complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after re-reading `design.md`, `design-decisions.md`, `resolved-design-questions.md`, referenced extension docs (`doc/extensions.md`, `doc/extension-api.md`, `doc/architecture.md`), and current UI/query/runtime seams. The design remains sufficiently specific for implementation planning on provider lookup/lifetime, single-active UI semantics, unavailable/provider-error normalization, available-only actions, capability/action coherence, invocation-kind schemas, descriptor/request correlation, constrained/optional side-effecting invocation, UI-type compatibility, and console/headless classification. No `design-steps.md` items were added because there is no new ambiguity feedback.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity design steps remain complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design inconsistency review

No new actionable inconsistency feedback after re-reading `design.md`, `design-decisions.md`, `resolved-design-questions.md`, referenced extension docs, and current UI/query/permission/frontend seams. The design remains aligned on runtime-scoped `:psi.ui/...` capability/action attrs, provider-derived state, unavailable/provider-error semantics, invocation/request boundaries, permission constraints, legacy UI-type compatibility, and documentation expectations. `plan.md` and `steps.md` were not reviewed.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md`; all design follow-up steps are already complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design ambiguity review

No new actionable ambiguity feedback after re-reading `design.md`, referenced task design artifacts/docs, and current UI/query/runtime/frontend seams. The design remains specific enough for implementation planning on runtime provider ownership/lifetime, EQL discovery/output semantics, unavailable/provider-error normalization, action descriptor validation, invocation/request boundaries, permission constraints, legacy UI-type compatibility, and frontend mode classification. No `design-steps.md` items were added because there is no new ambiguity feedback.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity design steps remain complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 design inconsistency review

No new actionable inconsistency feedback after re-reading `design.md`, referenced task design artifacts (`design-decisions.md`, `resolved-design-questions.md`), extension-authoring docs (`doc/extensions.md`, `doc/extension-api.md`, `doc/architecture.md`), and current UI/query/frontend seams. The design remains aligned on runtime-scoped `:psi.ui/...` query attrs, provider-derived capability/action ownership, unavailable/provider-error semantics, available-only actions, make-visible descriptors, invocation-kind schemas, constrained/optional request submission, legacy UI-type compatibility, and console/headless classification. `plan.md` and `steps.md` were not reviewed.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md`; all design follow-up steps are already complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` were not touched.

## 2026-05-30 plan ambiguity review

No new actionable ambiguity feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, referenced design artifacts/docs, and sampled current UI/query/runtime/frontend seams. The plan and checklist are specific enough for implementation on discovery, resolver/model validation, runtime provider installation, frontend provider wiring, optional invocation boundary handling, tests, docs, and verification. Existing completed `design-steps.md` items already cover prior design ambiguities, and no new unchecked ambiguity follow-up item was added.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all design follow-up steps are already complete. No `design.md` changes were needed, no design step was left blocked, and `plan.md` / `steps.md` required no updates.

## 2026-05-30 plan inconsistency review

Found one new actionable inconsistency after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, completed `design-steps.md`, and referenced extension docs: `implementation.md` records the `doc/extensions.md`, `doc/extension-api.md`, and `doc/architecture.md` queryable-UI documentation updates as already completed design follow-ups, while `steps.md` Slice 8 still lists those same documentation updates as unchecked implementation work. The checklist should distinguish already-completed design-doc alignment from any remaining implementation-time doc verification/update work.

## 2026-05-30 inconsistency follow-up execution

Completed the newly added `steps.md` Slice 8 documentation-checklist alignment follow-up. The implementation checklist now treats `doc/extensions.md`, `doc/extension-api.md`, and `doc/architecture.md` as post-implementation re-verification/final-sync items, because their queryable-UI design-doc alignment was already completed during earlier design follow-up passes. No design step remains blocked.

## 2026-05-30 plan/steps ambiguity review

No new actionable ambiguity feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, referenced design artifacts/docs, and sampled current UI/query/runtime/frontend seams. The implementation plan and checklist are clear enough to execute across discovery, model/validation, EQL discovery, runtime provider lifetime, frontend provider behaviour, optional invocation split, tests, docs, and verification; no new `design-steps.md` follow-up item was added.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all design follow-up steps are already complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` required no updates.

## 2026-05-30 plan/steps inconsistency review

No new actionable inconsistency feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, `resolved-design-questions.md`, completed `design-steps.md`, and referenced extension docs. The implementation plan and checklist remain aligned on queryability-first scope, provider-derived runtime UI state, available-only actions, stable unavailable make-visible descriptors, optional/constrained invocation, frontend provider expectations, documentation re-verification, and verification sequencing. No `design-steps.md` items were added because there is no new inconsistency feedback.

## 2026-05-30 inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md`; all design follow-up steps are already complete. No design step was completed or blocked in this pass, and `plan.md` / `steps.md` required no updates.

## 2026-05-30 plan/steps ambiguity review

No new actionable ambiguity feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, `resolved-design-questions.md`, completed `design-steps.md`, referenced extension docs, and sampled current UI/query/runtime/frontend seams. The plan and implementation checklist are sufficiently specific for execution across resolver/model validation, root-queryable discovery, runtime provider lifetime, frontend provider semantics, optional invocation split, test coverage, documentation re-verification, and final coherence checks. No `design-steps.md` items were added because there is no new ambiguity feedback.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md`; all ambiguity design steps are already complete. No `design.md` change was needed, no design step was left blocked, and `plan.md` / `steps.md` required no updates.

## 2026-05-30 plan/steps inconsistency review

Found one new actionable inconsistency after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, and `resolved-design-questions.md`: `design.md`, `plan.md`, and `steps.md` require `:psi.ui/diagnostic` as a first-class root-queryable/provider-error attr, but the attr/resolver summaries in `design-decisions.md` and `resolved-design-questions.md` still list the initial UI attrs without `:psi.ui/diagnostic`. Those summaries should be aligned so implementation planning has one attr set.

## 2026-05-30 inconsistency follow-up execution

Completed the newly added `:psi.ui/diagnostic` attr-summary alignment follow-up from `design-steps.md`. Updated `design-decisions.md` and `resolved-design-questions.md` so both list `:psi.ui/diagnostic` in the root-queryable `:psi.ui/...` attr set and describe it as bounded redacted provider-error troubleshooting data that is nil/absent for normal states. Marked the design step complete. No blocking follow-up remains.

## 2026-05-30 requested plan/steps ambiguity review

No new actionable ambiguity feedback after this requested independent pass over `plan.md`, `steps.md`, `implementation.md`, `design.md`, related design artifacts/docs, and sampled UI/query/runtime seams. Existing checklist items are actionable enough for implementation across discovery, model/validation, EQL discovery, provider lifetime, frontend provider behaviour, optional invocation handling, tests, docs, and verification; no unchecked `design-steps.md` follow-up item was added.

## 2026-05-30 ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md` after the preceding ambiguity-review pass; all ambiguity design follow-up steps are already complete. No `design.md`, `plan.md`, or `steps.md` updates were needed, and no design step was left blocked.

## 2026-05-30 requested plan/steps inconsistency review

No new actionable inconsistency feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, `resolved-design-questions.md`, completed `design-steps.md`, and referenced extension-authoring docs. The implementation plan and checklist remain aligned on queryability-first scope, provider-derived runtime UI capability/action data, root-queryable `:psi.ui/...` attrs including diagnostics, available-only actions, stable unavailable make-visible descriptors, optional/constrained side-effecting invocation, frontend provider expectations, documentation re-verification, and final verification. No unchecked `design-steps.md` follow-up item was added because there is no new inconsistency feedback.

## 2026-05-30 requested inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md` after the preceding inconsistency-review pass; all design follow-up steps are already complete. No design step was completed or blocked in this pass, and `plan.md` / `steps.md` required no updates.

## 2026-05-30 requested plan/steps ambiguity review

No new actionable ambiguity feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, `resolved-design-questions.md`, completed `design-steps.md`, referenced extension docs, and sampled current UI/query/runtime/frontend seams. The plan and checklist remain actionable for implementation across seam discovery, core model/validation, EQL resolver/discovery, runtime provider lifetime, frontend provider semantics, optional invocation handling, tests, documentation re-verification, and verification. No unchecked `design-steps.md` follow-up item was added because there is no new ambiguity feedback.

## 2026-05-30 requested ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md` after the preceding ambiguity-review pass (`c64c3d7b`); all ambiguity design follow-up steps are already complete. No `design.md`, `plan.md`, or `steps.md` updates were needed, and no design step was left blocked.

## 2026-05-30 requested plan/steps inconsistency review

No new actionable inconsistency feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, `resolved-design-questions.md`, completed `design-steps.md`, referenced extension docs, and current UI/query/runtime architecture guidance. The implementation plan and checklist remain aligned on queryability-first scope, provider-derived runtime UI capability/action data, root-queryable `:psi.ui/...` attrs including diagnostics, available-only actions, stable unavailable make-visible descriptors, optional/constrained side-effecting invocation, frontend provider expectations, documentation re-verification, and final verification. No unchecked `design-steps.md` follow-up item was added because there is no new inconsistency feedback.

## 2026-05-30 requested inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md` after the preceding inconsistency-review pass (`41c0e417`); all design follow-up steps are already complete. No design step was completed or blocked in this pass, and `plan.md` / `steps.md` required no updates.

## 2026-05-30 requested plan/steps ambiguity review

No new actionable ambiguity feedback after this independent review of `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, `resolved-design-questions.md`, referenced extension docs, and current UI/query/runtime/frontend seams. The plan and checklist remain clear enough for implementation across seam discovery, core model/validation, EQL attrs/discovery, runtime provider lifetime, frontend provider behaviour, optional invocation handling, tests, docs, and verification. No unchecked `design-steps.md` follow-up item was added because there is no new ambiguity feedback.

## 2026-05-30 requested ambiguity follow-up execution

No newly added unchecked ambiguity follow-up items were present in `design-steps.md` after the preceding ambiguity-review pass (`0c8284d9`); all design follow-up steps are already complete. No `design.md`, `plan.md`, or `steps.md` updates were needed, and no design step was left blocked.

## 2026-05-30 requested plan/steps inconsistency review

No new actionable inconsistency feedback after re-reading `plan.md`, `steps.md`, `implementation.md`, `design.md`, `design-decisions.md`, `resolved-design-questions.md`, completed `design-steps.md`, referenced extension docs, and sampled current UI/query/runtime seams. The implementation plan and checklist remain aligned on queryability-first scope, provider-derived runtime UI data, the full root-queryable `:psi.ui/...` attr set including diagnostics, available-only actions, stable unavailable make-visible descriptors, optional/constrained side-effecting invocation, frontend provider expectations, documentation re-verification, and final verification. No unchecked `design-steps.md` follow-up item was added because there is no new inconsistency feedback.

## 2026-05-30 requested inconsistency follow-up execution

No newly added unchecked inconsistency follow-up items were present in `design-steps.md` after the preceding inconsistency-review pass (`9cc33cc0`); all design follow-up steps are already complete. No design step was completed or blocked in this pass, and `plan.md` / `steps.md` required no updates.

## 2026-05-30 implementation pass

Implemented the queryability-first slice:

- Added `psi.agent-session.ui-capabilities` as the core-owned pure data model/normalization boundary for `:psi.ui/...` capability/action attrs.
- Added provider slot `:ui-capability-provider*` to agent-session context with install/clear helpers. Resolvers dereference it at query time, so adapters can replace it after ctx creation without storing advertised capabilities/actions in root state.
- Added a dedicated `ui-capabilities-resolver` separate from the existing extension UI contribution snapshot resolver. It exposes `:psi.ui/type`, `:psi.ui/available?`, `:psi.ui/capabilities`, `:psi.ui/actions`, `:psi.ui/make-visible-action`, and `:psi.ui/diagnostic` from only `:psi/agent-session-ctx`.
- Added normalization/validation for available-only `:psi.ui/actions`, stable unavailable make-visible descriptors, provider-error diagnostics, invocation kinds `:emacs-command`, `:ui-event`, `:bash-command`, and `:mutation`, duplicate action ids, and make-visible capability/action coherence.
- Wired default runtime providers by `ui-type`: Emacs advertises `:psi.ui.capability/make-visible` with `{:psi.ui.invocation/kind :emacs-command :psi.ui.invocation/command "psi-emacs-show-active"}`; TUI and console are attached-but-unsupported unless a future provider installs real reveal metadata; missing provider remains headless/no-provider.
- Added interactive Emacs command `psi-emacs-show-active`, which locates an active Psi buffer, `pop-to-buffer`s it, selects/focuses its window/frame, and focuses the prompt.
- Updated nullable extension API query fixture so extension tests can query the new capability/action attrs without mocks.
- Added `CHANGELOG.md` entry for the extension-visible query surface.

Side-effecting invocation was not implemented in this slice. The final request contract remains the one in `design.md` (`:psi.ui/request-action` with `:psi.ui.request/...` and `:psi.ui.result/...` keys), and follow-up task `191-ui-action-invocation` now owns permission-aware/constrained descriptor submission and adapter execution.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test` — 27 tests, 2332 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/src/psi/agent_session/context.clj components/agent-session/src/psi/agent_session/resolvers/extensions.clj components/extension-test-helpers/src/psi/extension_test_helpers/nullable_api.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj components/agent-session/test/psi/agent_session/graph_surface_test.clj` — clean.
- `bb emacs:byte-compile` — clean.

## 2026-05-30 provider-normalization test pass

Expanded `psi.agent-session.ui-capabilities-test` to cover the remaining provider normalization cases from Slice 7: no-attached UI semantics, unsupported make-visible semantics, supported make-visible normalization, all supported invocation-kind schemas, malformed per-kind invocation data, duplicate action id rejection, and capability/action coherence failures. These tests assert normalized EQL-visible state and provider-error outcomes rather than provider interactions/mocks.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 11 tests, 57 assertions, 0 failures.
- `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test` — 33 tests, 2360 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 UI snapshot compatibility regression pass

Added a focused regression test proving the new `:psi.ui/...` capability/action query attrs compose with existing extension UI contribution snapshot attrs and the legacy `:psi.agent-session/ui-type` diagnostic surface in one session query. The test uses real dispatch/query state, not mocks, and covers widget/status snapshots alongside attached-console unsupported make-visible semantics.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 12 tests, 66 assertions, 0 failures.
- `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test --focus psi.agent-session.resolvers-test` — 57 tests, 2505 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/ui_capabilities_test.clj components/agent-session/test/psi/agent_session/graph_surface_test.clj components/agent-session/test/psi/agent_session/resolvers_test.clj` — clean.

## 2026-05-30 Emacs RPC provider lifecycle pass

Added an Emacs RPC UI capability provider that derives active session state at query time from the RPC connection focus-session atom rather than advertising a static startup descriptor. When a focused session id is present, the make-visible descriptor remains available and now carries `:psi.ui.invocation/session-id` correlation data. When the RPC connection exists but no active/focused session id is known, the provider returns attached Emacs type with `:psi.ui/available? false`, empty capabilities/actions, and the stable unavailable make-visible descriptor reasoned as `:psi.ui.unavailable.reason/no-attached-ui`.

`psi.rpc.runtime/start-runtime!` installs this late-bound Emacs RPC provider after creating the RPC state and clears the active provider when the stdio loop exits, preventing detached/stopped RPC frontends from continuing to advertise stale make-visible actions. The default `:ui-type :emacs` context provider remains for non-RPC/test contexts that intentionally model an attached Emacs UI.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test --focus psi.rpc-invariants-test` — 40 tests, 2414 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/rpc/src/psi/rpc/runtime.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 broader verification pass

Ran full project verification after the Emacs RPC provider lifecycle slice touched shared runtime/query seams.

Verification:

- `bb test` — all tests passed.

## 2026-05-30 checklist closure pass

Closed the remaining conditional checklist items after confirming side-effecting invocation stayed out of this task and is owned by follow-up `191-ui-action-invocation`. No README update is needed for this slice because the extension-facing queryable UI guidance is already in `doc/extensions.md` and `doc/extension-api.md`; README does not add a more specific extension-authoring pointer for this surface.

## 2026-05-30 implementation review

Found one new actionable implementation issue after reviewing task artifacts, changed UI capability/provider code, RPC/Emacs wiring, docs, and focused tests: provider-error diagnostics are bounded but not redacted. `diagnostic-text` normalizes and truncates exception/provider messages, yet the design and checklist require redaction of stack traces, frontend object printed forms, tokens, secret-bearing paths/data, and arbitrary exception data before exposing `:psi.ui/diagnostic` through EQL. Focused verification remained green: `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test --focus psi.rpc-invariants-test`; targeted `clj-kondo` green.

## 2026-05-30 provider-error diagnostic redaction follow-up

Completed the newly added implementation-review follow-up for provider-error diagnostics. `:psi.ui/diagnostic` now derives from a bounded redaction path that includes provider-error class/message only, avoids arbitrary `ex-data`, collapses stacktrace frames, redacts Emacs/frontend object printed forms, token/secret/password/API-key values, bearer/token-looking values, and secret-bearing paths before truncation. Added focused tests proving bounded diagnostics and redaction of stack frames, frontend object forms, token/secret values, secret-bearing paths, and arbitrary exception data.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 14 tests, 82 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 implementation review

Found one new actionable implementation issue after re-reading task artifacts, implementation notes, UI capability/provider code, nullable extension API fixture, docs, and focused tests: the nullable extension API only recognizes the exact five-attr UI capability query without `:psi.ui/diagnostic`, while `doc/extension-api.md` recommends querying `:psi.ui/diagnostic` with the capability/action attrs and the real EQL surface exposes it. Extension tests that follow the documented query against `create-nullable-extension-api` currently get `{}` instead of the unsupported-console UI capability map. Add nullable API support and coverage for documented UI capability queries including diagnostics.


## 2026-05-30 nullable UI diagnostic query follow-up

Completed the newly added implementation-review follow-up for nullable extension API UI capability queries. `create-nullable-extension-api` now recognizes any non-empty query composed of the documented `:psi.ui/...` capability attrs, including `:psi.ui/diagnostic`, and returns the nullable attached-console unsupported-make-visible map with `:psi.ui/diagnostic nil` instead of `{}`. Added nullable API coverage for the documented query shape and added the extension-test-helpers test path to the project test configuration so this fixture coverage runs under Kaocha.

Verification:

- `clojure -M:test --focus psi.extension-test-helpers.nullable-api-test` — 3 tests, 6 assertions, 0 failures.
- `clojure -M:test --focus psi.agent-session.extensions-service-protocol-api-test --focus psi.agent-session.extensions-post-tool-api-test --focus psi.extension-test-helpers.nullable-api-test` — 6 tests, 11 assertions, 0 failures.
- `clj-kondo --lint deps.edn tests.edn components/extension-test-helpers/src/psi/extension_test_helpers/nullable_api.clj components/extension-test-helpers/test/psi/extension_test_helpers/nullable_api_test.clj` — clean.

## 2026-05-30 implementation review

Found one new actionable implementation issue after re-reading task artifacts, UI capability normalization/provider code, RPC lifecycle wiring, docs, and focused tests: provider normalization can expose contradictory unavailable/available UI state. If a provider returns `:psi.ui/available? false` while also returning capabilities and available actions, `normalize-provider-result` currently preserves `available? false` but can still expose `:psi.ui.capability/make-visible` and an available make-visible descriptor. The design's no-attached/provider-error semantics require unavailable providers to expose empty capabilities/actions and a stable unavailable make-visible descriptor, while supported make-visible implies `:psi.ui/available? true`. Add validation/coverage so unavailable provider results with capabilities or available actions fail closed to provider-error or normalize to the explicit unavailable state.

## 2026-05-30 unavailable-provider advertisement follow-up

Completed the newly added implementation-review follow-up for contradictory provider output. Provider normalization now treats `:psi.ui/available? false` with advertised capabilities or available actions as invalid provider output and fails closed to provider-error semantics, ensuring EQL exposes `:psi.ui/available? false`, empty capabilities/actions, and the stable provider-error unavailable make-visible descriptor rather than contradictory available action data. Added focused coverage for unavailable providers that advertise make-visible capability/action data.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 15 tests, 90 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 implementation review

Found one new actionable implementation issue after re-reading task artifacts, UI capability/provider code, RPC runtime wiring, extension docs, and focused tests: RPC/Emacs contexts still install the default static `:emacs` make-visible provider during `create-context` before `psi.rpc.runtime/start-runtime!` installs the late-bound RPC provider. Because `start-runtime!` bootstraps the session before replacing that provider, extension/bootstrap queries can observe an available Emacs make-visible action without RPC focus-session correlation or a confirmed attached frontend, contradicting the design's late-install/no-stale-provider lifecycle. Ensure the RPC path does not expose the static default Emacs provider before the connection-local provider is installed, and add coverage for the pre-install/bootstrap semantics.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test --focus psi.extension-test-helpers.nullable-api-test` — 40 tests, 2399 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/src/psi/agent_session/context.clj components/agent-session/src/psi/agent_session/resolvers/extensions.clj components/rpc/src/psi/rpc/runtime.clj components/extension-test-helpers/src/psi/extension_test_helpers/nullable_api.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj components/agent-session/test/psi/agent_session/graph_surface_test.clj components/extension-test-helpers/test/psi/extension_test_helpers/nullable_api_test.clj` — clean.

## 2026-05-30 RPC pre-install provider follow-up

Completed the newly added implementation-review follow-up for RPC/Emacs pre-install provider semantics. Agent-session context creation now accepts `:install-default-ui-capability-provider?` (default true) so RPC runtime contexts can preserve legacy `:ui-type :emacs` diagnostics without installing the static Emacs make-visible provider. The main RPC session factory disables that default provider, and `psi.rpc.runtime/start-runtime!` now creates the RPC connection state and installs the late-bound `emacs-rpc-provider` before bootstrap runs, so bootstrap/extension queries observe the connection-correlated descriptor rather than a static startup advertisement. Added coverage for no-provider pre-install semantics, no-attached installation semantics, and bootstrap-time observation of the RPC provider with session correlation.

Verification:

- `clojure -M:test --focus psi.rpc-transport-test --focus psi.agent-session.ui-capabilities-test` — 25 tests, 139 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/context.clj components/app-runtime/src/psi/app_runtime.clj components/rpc/src/psi/rpc/runtime.clj bases/main/src/psi/main.clj components/rpc/test/psi/rpc_transport_test.clj` — clean.

## 2026-05-30 implementation review

Found one new actionable implementation issue after re-reading task artifacts, UI capability/provider code, RPC runtime wiring, docs, and focused tests: `emacs-rpc-provider` intends to downgrade nil or blank focused session ids to no-attached UI, but it checks `(not-empty (str ...))`, so whitespace-only focus ids (for example `"   "`) still advertise an available make-visible action with invalid session correlation. Normalize/trim the focused session id before advertising availability, and add coverage proving blank/whitespace focus state returns no-attached semantics.

## 2026-05-30 Emacs RPC focused-session normalization follow-up

Completed the newly added implementation-review follow-up for Emacs RPC focused-session correlation. `emacs-rpc-provider` now trims the focused session id before advertising make-visible availability, treats nil/blank/whitespace focus state as no-attached UI, and stores the trimmed session id in descriptor invocation correlation data. Added focused coverage for nil, empty, whitespace, and padded focused-session values.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 15 tests, 107 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 implementation review

Found one new actionable implementation issue after re-reading task artifacts, UI capability/provider code, app-runtime TUI startup wiring, RPC lifecycle fixes, docs, and focused tests: TUI contexts still install the default attached `:tui` provider during context creation, before the TUI frontend/state is actually attached, and there is no TUI shutdown clear/downgrade path. This mirrors the already-fixed RPC pre-install/stale-provider class: bootstrap/extension queries during TUI startup can observe `:psi.ui/available? true` for an attached TUI before `tui-start-fn!` runs, and the provider can remain attached after the TUI exits if the ctx is queried. Align TUI provider installation/lifetime with the design's adapter-owned late install/clear semantics and add pre-start/bootstrap/shutdown coverage.

## 2026-05-30 TUI provider lifecycle follow-up

Completed the newly added implementation-review follow-up for TUI provider lifetime. `start-tui-runtime!` now suppresses the context default static TUI provider, so bootstrap/pre-frontend queries see missing-provider semantics instead of an attached TUI. After the TUI focus state exists and before handing control to the frontend, app-runtime installs the adapter-owned attached-but-unsupported TUI provider. The provider is cleared in a `finally` after `tui-start-fn!` returns or throws, preventing stale attached TUI advertisements after shutdown.

Added app-runtime coverage for explicit default-provider suppression, bootstrap-time no-provider semantics, frontend-time attached unsupported semantics, and shutdown provider clearing.

Verification:

- `clojure -M:test --focus psi.app-runtime-test/start-tui-runtime-installs-and-clears-tui-ui-provider-test --focus psi.app-runtime-test/create-runtime-session-context-can-suppress-default-tui-ui-provider-test` — 2 tests, 6 assertions, 0 failures.
- `clojure -M:test --focus psi.app-runtime-test --focus psi.agent-session.ui-capabilities-test` — 45 tests, 222 assertions, 0 failures.
- `clj-kondo --lint components/app-runtime/src/psi/app_runtime.clj components/app-runtime/test/psi/app_runtime_test.clj` — clean.

## 2026-05-30 implementation review

Found one new actionable implementation issue after re-reading task artifacts, UI capability normalization code, resolver/docs, and focused lifecycle tests: action descriptor validation does not reject extra unqualified descriptor keys. `valid-action?` verifies required `:psi.ui.action/...` keys and EDN serialisability, but a provider can still expose additional unqualified keys (for example `:handler` or `"frontend-object"`) in `:psi.ui/actions` / `:psi.ui/make-visible-action`. The design requires descriptors to be pure data with fully namespaced descriptor keys, so provider normalization should fail closed to provider-error for unqualified/foreign action descriptor keys and add focused coverage.

## 2026-05-30 action descriptor foreign-key follow-up

Completed the newly added implementation-review follow-up for provider action descriptor key validation. Action descriptors now require all descriptor keys to be in the `:psi.ui.action/...` namespace, so providers cannot expose unqualified keys such as `:handler`, foreign namespaced keys, string keys, or adapter-local data through `:psi.ui/actions` / `:psi.ui/make-visible-action`. Invalid descriptor keys fail closed to provider-error unavailable semantics. Added focused coverage for unqualified, foreign, and string action keys.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 16 tests, 110 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 implementation review

Found one new actionable implementation issue after re-reading task artifacts, UI capability normalization/provider code, resolver/docs, and focused tests: invocation maps nested inside action descriptors still allow extra unqualified or foreign keys. `valid-action?` now rejects non-`:psi.ui.action/...` descriptor keys, but `valid-invocation?` only checks EDN serialisability plus required per-kind fields, so a provider can expose adapter-local invocation data such as `:handler`, `:foreign.ui/object`, or string keys inside `:psi.ui.action/invocation`. The design says descriptor invocation data uses only the `:psi.ui.invocation/...` namespace and must not expose adapter/frontend internals. Provider normalization should fail closed to provider-error for unqualified/foreign invocation keys and add focused coverage.

## 2026-05-30 invocation foreign-key follow-up

Completed the newly added implementation-review follow-up for provider action invocation key validation. Invocation maps nested under action descriptors now require every key to be in the `:psi.ui.invocation/...` namespace, so providers cannot expose unqualified keys, foreign namespaced keys, string keys, or adapter-local invocation data through EQL. Invalid invocation keys fail closed to provider-error unavailable semantics. Added focused coverage for unqualified, foreign, and string invocation keys.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 17 tests, 113 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 implementation review

No new actionable implementation feedback after reviewing task artifacts, UI capability normalization/resolver code, context provider lifecycle wiring, RPC/TUI provider installation, Emacs command implementation, nullable API support, extension docs, and focused tests. The latest review-fixed issues for diagnostic redaction, nullable diagnostic queries, contradictory unavailable provider output, RPC/TUI late provider lifecycle, focus-session normalization, and descriptor/invocation foreign-key rejection are covered by focused tests. Verification: `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test --focus psi.app-runtime-test --focus psi.rpc-transport-test` — 79 tests, 2580 assertions, 0 failures.

## 2026-05-30 test review

Found one new actionable test issue after reviewing task artifacts, UI capability/provider tests, RPC/TUI lifecycle tests, nullable API coverage, extension docs, and Emacs UI code: the Emacs Lisp `psi-emacs-show-active` command implementation has no direct test coverage. Existing tests prove the descriptor advertises `psi-emacs-show-active` and byte-compilation catches syntax errors, but no test proves the command locates the active/current Psi buffer, calls the prompt-focus path after `pop-to-buffer`, or errors when no active Psi buffer exists. Add focused Emacs UI coverage or explicitly justify why byte-compile-only proof is sufficient for this frontend behaviour.

## 2026-05-30 Emacs show-active test follow-up

Completed the newly added test-review follow-up for direct `psi-emacs-show-active` coverage. Added focused Emacs ERT tests proving the command focuses the current Psi buffer prompt after `pop-to-buffer`, can fall back to a tracked active Psi buffer when invoked outside a Psi buffer, and raises `user-error` when no active Psi buffer exists. Marked the Slice 7 follow-up item complete.

Verification:

- `bb emacs:test` — 321 tests, 321 expected, 0 unexpected.
- `bb emacs:byte-compile` — clean.

## 2026-05-30 test review

Found one new actionable test issue after reviewing task artifacts, UI capability/provider tests, lifecycle tests, nullable coverage, Emacs ERT coverage, and the invocation schema contract: provider normalization tests cover valid `:ui-event` and `:mutation` invocations only when optional `:psi.ui.invocation/payload` / `:psi.ui.invocation/params` are present, but the design/checklist require those fields to be optional/defaulted to `{}` when omitted. Add focused coverage for omitted payload/params defaulting (and fix implementation if the test exposes drift) so the descriptor contract is executable.

## 2026-05-30 invocation defaulting test follow-up

Completed the newly added test-review follow-up for omitted optional invocation maps. Provider normalization now defaults omitted `:psi.ui.invocation/payload` for `:ui-event` invocations and omitted `:psi.ui.invocation/params` for `:mutation` invocations to `{}` before exposing descriptors through EQL. Added focused provider normalization coverage for both omitted-field cases and marked the Slice 8 follow-up item complete.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 18 tests, 115 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/ui_capabilities.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj` — clean.

## 2026-05-30 test review

Found one new actionable test issue after reviewing task artifacts, UI capability/provider tests, lifecycle tests, nullable API coverage, and extension API query paths: the checklist marks extension-query coverage complete, but no real `ext/create-extension-api` + `runtime-fns/make-extension-runtime-fns` test queries the new `:psi.ui/...` attrs. Existing coverage exercises `session/query-in` directly and the nullable helper, which does not prove extension code can discover UI capabilities/actions through the actual extension API without session-id input or permissions. Add focused real extension API coverage for querying the documented UI capability attrs.

## 2026-05-30 real extension API UI query follow-up

Completed the newly added test-review follow-up for real extension API query coverage. Added focused coverage proving an extension API created through `ext/create-extension-api` with `runtime-fns/make-extension-runtime-fns` can query the documented `:psi.ui/...` capability/action attrs without explicit session-id input. The test also narrows the extension's allowed events to an empty set, proving the permission-free query path does not depend on manifest `allowed-events`.

Verification:

- `clojure -M:test --focus psi.agent-session.extensions-test` — 24 tests, 124 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/extensions_test.clj` — clean.

## 2026-05-30 test review

No new actionable test feedback after reviewing task artifacts, UI capability/provider normalization tests, resolver discovery tests, runtime extension API query coverage, nullable API coverage, RPC/TUI lifecycle tests, Emacs ERT coverage, and extension docs. The tests now cover each designed behaviour cluster: provider unavailable/error semantics, descriptor/invocation validation and defaulting, root-queryable discovery, real extension API querying without session-id input or allowed events, frontend lifecycle/provider attachment, legacy UI snapshot compatibility, and direct `psi-emacs-show-active` command behaviour. No new follow-up items were added.

Verification:

- `clojure -M:test --focus psi.agent-session.ui-capabilities-test` — 18 tests, 115 assertions, 0 failures.
- `clojure -M:test --focus psi.agent-session.extensions-test` — 24 tests, 124 assertions, 0 failures.
- `bb emacs:test` — 321 tests, 321 expected, 0 unexpected.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/extensions_test.clj components/agent-session/test/psi/agent_session/ui_capabilities_test.clj components/app-runtime/test/psi/app_runtime_test.clj components/rpc/test/psi/rpc_transport_test.clj` — clean.

## 2026-05-30 test-shaper review

Found one new actionable test-shaping issue after reviewing task artifacts, UI capability/provider tests, RPC/TUI lifecycle tests, nullable/extension API coverage, graph discovery tests, Emacs ERT coverage, and docs: TUI provider lifecycle coverage proves normal frontend return clears the provider, but not the exceptional shutdown path. The implementation note/design require clearing/downgrading stale providers on TUI shutdown, and robust lifecycle tests should prove `start-tui-runtime!` clears the active UI provider even when `tui-start-fn!` throws so failed frontend startup cannot leave stale attached UI advertisements.

## 2026-05-30 test-review follow-up execution

Completed the TUI exceptional lifecycle coverage follow-up. Added `start-tui-runtime-clears-tui-ui-provider-when-frontend-throws-test`, which proves the attached TUI provider is visible during frontend startup, `start-tui-runtime!` propagates the frontend exception, the active provider is cleared in the `finally`, and post-throw UI capability queries return no-provider semantics rather than stale attached TUI advertisements.

Verification:

- `clojure -M:test --focus psi.app-runtime-test/start-tui-runtime-clears-tui-ui-provider-when-frontend-throws-test --focus psi.app-runtime-test/start-tui-runtime-installs-and-clears-tui-ui-provider-test` — 2 tests, 8 assertions, 0 failures.
- `clj-kondo --lint components/app-runtime/test/psi/app_runtime_test.clj` — clean.

## 2026-05-30 test-shaper review

Found one new actionable test-shaping issue after reviewing task artifacts, UI capability/provider tests, runtime lifecycle tests, nullable/extension API coverage, Emacs ERT coverage, docs, and provider normalization code: provider-result collection shape validation is not executable. The design/provider contract requires `:psi.ui/capabilities` and `:psi.ui/actions` to be vectors, but current normalization tests only cover valid vectors and malformed entries; they do not prove non-vector collections such as lists or sets fail closed. Because `normalize-provider-result` currently vectorizes these fields before validating their shape, this contract drift can pass silently. Add focused provider normalization coverage for non-vector capabilities/actions and fix normalization if the tests expose drift.

## 2026-05-30 provider collection-shape follow-up

Completed the newly added test-shaper follow-up for provider collection-shape drift. Added focused normalization coverage proving non-vector `:psi.ui/capabilities` and `:psi.ui/actions` fail closed to provider-error semantics, and fixed normalization to validate raw collection shapes before defaulting/normalizing instead of silently vectorizing lists or sets. Verification: `clojure -M:test --focus psi.agent-session.ui-capabilities-test`; targeted `clj-kondo` for the changed UI capability source/test files.

## 2026-05-30 implementation review

No new actionable implementation feedback after reviewing task artifacts, UI capability normalization/resolver code, provider lifecycle wiring, RPC/TUI tests, extension API/nullability coverage, Emacs command coverage, docs, and the latest provider collection-shape follow-up. Non-vector `:psi.ui/capabilities` / `:psi.ui/actions` now fail closed to provider-error semantics with focused tests, and targeted verification passed: `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test --focus psi.app-runtime-test --focus psi.rpc-transport-test --focus psi.agent-session.extensions-test`; targeted `clj-kondo` passed for changed UI/RPC/TUI/extension test files.


## 2026-05-30 test review

No new actionable test feedback after applying `task-test-review` to task artifacts, UI capability/provider normalization tests, resolver discovery coverage, real extension API query coverage, nullable API coverage, RPC/TUI lifecycle tests, Emacs ERT coverage, and extension docs. The test suite covers the designed behaviours: unavailable/provider-error semantics, descriptor and invocation validation/defaulting, collection-shape fail-closed behaviour, root-queryable discovery, permission-free extension API querying without explicit session id, frontend provider lifecycle including exceptional TUI cleanup, legacy UI snapshot compatibility, and direct `psi-emacs-show-active` behaviour. Verification: `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.extensions-test --focus psi.app-runtime-test --focus psi.rpc-transport-test` — 84 tests, 419 assertions, 0 failures; `bb emacs:test` — 321 tests, 321 expected, 0 unexpected.

## 2026-05-30 implementation review

No new actionable implementation feedback after re-reading task artifacts, UI capability normalization/resolver code, runtime provider lifecycle wiring, RPC/TUI provider tests, nullable and real extension API coverage, Emacs show-active implementation/tests, extension docs, and changelog. The latest provider collection-shape fail-closed follow-up aligns the implementation with the vector-only provider contract. Focused verification passed: `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.graph-surface-test --focus psi.app-runtime-test/start-tui-runtime-installs-and-clears-tui-ui-provider-test --focus psi.app-runtime-test/start-tui-runtime-clears-tui-ui-provider-when-frontend-throws-test --focus psi.rpc-transport-test/rpc-start-runtime-installs-rpc-provider-before-bootstrap-test --focus psi.rpc-transport-test/rpc-context-can-start-before-late-bound-provider-install-test` — 45 tests, 2445 assertions, 0 failures.

## 2026-05-30 requested task-test-review pass

No new actionable test feedback after an independent `task-test-review` pass over the task artifacts, referenced code/tests/docs, UI capability/provider normalization coverage, extension API/nullability query tests, RPC/TUI lifecycle tests, and Emacs ERT coverage. Existing test-review/test-shaper follow-ups cover the prior gaps; no new unchecked `steps.md` item was added. Verification in this pass: `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.extensions-test --focus psi.app-runtime-test --focus psi.rpc-transport-test` — 84 tests, 419 assertions, 0 failures; `bb emacs:test` — 321 tests, 321 expected, 0 unexpected.

## 2026-05-30 docs review

Found one new actionable documentation issue after reviewing the task artifacts, implementation notes, `doc/extensions.md`, `doc/extension-api.md`, `doc/architecture.md`, `README.md`, `CHANGELOG.md`, and follow-up task 191: the extension docs say callers may "submit" a make-visible descriptor through the core UI action request path, but task 190 intentionally left side-effecting submission unimplemented and delegated it to `191-ui-action-invocation`. Update the docs to qualify the current surface as query/descriptor-only until task 191 lands, while still describing the planned request path without implying it is currently usable.

## 2026-05-30 requested task-test-review pass

No new actionable test feedback after re-reading the task artifacts, referenced UI capability/provider code, resolver/runtime/frontend seams, docs, and the current test coverage. Existing tests cover provider unavailable/error semantics, descriptor/invocation validation and defaulting, collection-shape fail-closed behaviour, root-queryable discovery, real extension API and nullable queries, RPC/TUI lifecycle including exceptional cleanup, legacy UI snapshot compatibility, and direct `psi-emacs-show-active` Emacs behaviour. Verification in this pass: `clojure -M:test --focus psi.agent-session.ui-capabilities-test --focus psi.agent-session.extensions-test --focus psi.app-runtime-test --focus psi.rpc-transport-test` — 84 tests, 419 assertions, 0 failures; `bb emacs:test` — 321 tests, 321 expected, 0 unexpected. No new `steps.md` item was added.

## 2026-05-30 docs follow-up execution

Completed the newly added documentation follow-up. Updated `doc/extensions.md` and `doc/extension-api.md` so extension guidance states task 190 exposes queryable UI descriptor data only. The docs now say side-effecting descriptor submission through the core UI action request path is not implemented in this task, is owned by `191-ui-action-invocation`, and callers may inspect/display/store descriptors but must not assume an API exists yet to execute `:psi.ui.action/invocation` values. Marked the new `steps.md` item complete.
