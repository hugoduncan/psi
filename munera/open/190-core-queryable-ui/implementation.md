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
