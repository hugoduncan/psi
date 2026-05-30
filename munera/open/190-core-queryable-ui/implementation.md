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
