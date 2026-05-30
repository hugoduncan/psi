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
