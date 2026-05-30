# Implementation steps

## Slice 1 — Discovery and seam selection

- [x] Locate existing Pathom/EQL resolvers that expose extension UI snapshot attrs and root-queryable attrs.
- [x] Locate the extension query path used by extensions and `psi-tool` for root/runtime attrs.
- [x] Locate current runtime context construction and mutation/update seams for app-runtime, TUI, RPC, Emacs, console, and headless modes.
- [x] Locate current `:ui-type`, `:psi.agent-session/ui-type`, and extension API `:ui-type` producers and tests.
- [x] Locate Emacs Lisp entry points that show Psi buffers and focus the input area.
- [x] Decide whether to add a dedicated UI capability resolver or extend the existing extension UI resolver, and record the decision in `implementation.md`.

## Slice 2 — Core model and validation

- [x] Add canonical constants for `:psi.ui.capability/make-visible`, `:psi.ui.action/make-visible`, and the required unavailable reason keywords.
- [x] Add helper for constructing the standard make-visible available descriptor from invocation data.
- [x] Add helper for constructing the standard unavailable make-visible descriptor with required reason and message.
- [x] Add serialisability validation for descriptor and invocation values that rejects functions, atoms, promises, channels, exceptions, frontend objects, and arbitrary Java objects.
- [x] Add invocation validation for `:emacs-command`, including non-empty command string and optional bounded serialisable args/session/runtime fields.
- [x] Add invocation validation for `:ui-event`, including namespaced event keyword and optional/defaulted serialisable payload map.
- [x] Add invocation validation for `:bash-command`, including non-empty argv vector of non-empty strings and optional string-to-string env map.
- [x] Add invocation validation for `:mutation`, including fully qualified mutation symbol and optional/defaulted serialisable params map.
- [x] Add action descriptor validation for required namespaced keys, boolean availability, unavailable reason/message requirements, and duplicate action id rejection.
- [x] Add provider-result normalization that defaults missing safe fields, filters `:psi.ui/actions` to available actions only, and validates capability/action coherence.
- [x] Add provider-error normalization that returns unavailable UI data, empty capabilities/actions, provider-error make-visible descriptor, and bounded redacted `:psi.ui/diagnostic` text.

## Slice 3 — EQL resolver and discovery

- [x] Add resolver output for `:psi.ui/type` using only `:psi/agent-session-ctx` as input.
- [x] Add resolver output for `:psi.ui/available?` using only `:psi/agent-session-ctx` as input.
- [x] Add resolver output for `:psi.ui/capabilities` using only `:psi/agent-session-ctx` as input.
- [x] Add resolver output for `:psi.ui/actions` using only `:psi/agent-session-ctx` as input.
- [x] Add resolver output for `:psi.ui/make-visible-action` using only `:psi/agent-session-ctx` as input and returning a descriptor in all states.
- [x] Add resolver output for `:psi.ui/diagnostic` using only `:psi/agent-session-ctx` as input and exposing nil/absent except provider-error cases.
- [x] Register the new attrs in root-queryable/discovery indexes used by `psi-tool` and extension queries.
- [x] Verify missing provider semantics return nil type, unavailable false, empty capabilities/actions, unavailable no-provider make-visible descriptor, and no diagnostic.
- [x] Verify provider exception semantics return provider-error unavailable data instead of dropping requested attrs.

## Slice 4 — Runtime provider installation

- [x] Add or identify the stable runtime-context key/slot for the active UI capability provider.
- [x] Ensure the provider slot can be installed, replaced, or cleared after agent-session ctx creation.
- [x] Ensure resolver lookup calls the current provider at query time rather than capturing a startup provider value.
- [x] Add helper/API for adapters to install or replace the active UI capability provider.
- [x] Add helper/API for adapters to clear the active UI capability provider or replace it with no-attached semantics on detach/shutdown.
- [x] Ensure only one active provider is authoritative for this slice and stale detached providers cannot continue advertising actions.

## Slice 5 — Frontend providers

- [x] Add Emacs/RPC provider installation that reports `:psi.ui/type :emacs`, available true, make-visible capability, and the Emacs command invocation descriptor when an active Psi UI is attached.
- [x] Add unavailable/no-attached Emacs provider behaviour when the RPC/Emacs connection exists but no usable active Psi buffer/session is known.
- [x] Add interactive Emacs command `psi-emacs-show-active` that locates the active/current Psi buffer, calls `pop-to-buffer`, focuses the prompt, and selects/focuses the frame/window as needed.
- [x] Wire Emacs detach/shutdown/lost-active-buffer handling to clear or downgrade the provider so stale buffers are not advertised.
- [x] Add TUI provider behaviour only if existing state includes real safe reveal target metadata; otherwise ensure TUI omits make-visible capability and exposes unsupported make-visible via the convenience attr.
- [x] Add console provider behaviour for attached console without visibility mechanism: type `:console`, available true, no make-visible capability/actions, unsupported make-visible convenience descriptor.
- [x] Ensure headless/no-UI mode leaves the provider absent or reports no attached UI according to the implementation seam.

## Slice 6 — Invocation contract boundary

- [x] Record the final `:psi.ui/request-action` event/submission contract in `implementation.md`, including payload keys and result statuses.
- [x] Decide whether the constrained side-effecting request helper is small enough for this task and record the decision in `implementation.md`.
- [x] If implemented, add request-time validation for unknown action ids, unavailable descriptors, malformed invocation data, unsupported invocation kinds, stale correlation, and current-provider mismatch. Not applicable in this task because invocation helper was split to follow-up 191.
- [x] If implemented, add the core-owned event/subscription path for `:psi.ui/request-action` without routing extensions through generic manifest `allowed-events` dispatch. Not applicable in this task because invocation helper was split to follow-up 191.
- [x] If implemented, add Emacs adapter handling that turns the declarative `:emacs-command` invocation for `psi-emacs-show-active` into the Emacs-side command call. Not applicable in this task because invocation helper was split to follow-up 191.
- [x] If implemented, add acknowledgement/result data for accepted, completed, rejected, unsupported, failed, and timeout outcomes. Not applicable in this task because invocation helper was split to follow-up 191.
- [x] If not implemented, create a follow-up Munera task for permission-aware side-effecting UI action invocation.

## Slice 7 — Tests

- [x] Add nullable tests for missing provider/headless UI query behaviour.
- [x] Add provider normalization tests for no-attached UI, unsupported make-visible, supported make-visible, provider exception, invalid invocation kind, malformed per-kind invocation, duplicate action ids, and capability/action incoherence.
- [x] Add resolver discovery/index tests proving all new `:psi.ui/...` attrs are root-queryable/discoverable.
- [x] Add extension-query tests proving extension code can query the UI attrs without requiring session-id input or extension permissions.
- [x] Add Emacs provider tests proving the supported make-visible descriptor includes `:emacs-command` invocation with command `psi-emacs-show-active`.
- [x] Add focused Emacs UI test coverage for `psi-emacs-show-active` command behaviour (active/current buffer selection, prompt focus after `pop-to-buffer`, and no-active-buffer error), or explicitly document why byte-compile-only proof is sufficient.
- [x] Add TUI tests proving make-visible is omitted unless a real safe reveal mechanism is present.
- [x] Add console tests proving attached console without visibility mechanism is available but unsupported for make-visible.
- [x] Add regression tests proving existing UI contribution snapshot/query behaviour and legacy UI-type surfaces still pass.
- [x] If invocation helper is implemented, add request validation and result-shape tests. Not applicable in this task because invocation helper was split to follow-up 191.

## Slice 8 — Documentation and verification

- [x] Re-verify `doc/extensions.md` after implementation and update only for implementation-time drift from the already-completed design-doc alignment of capability/action querying guidance and make-visible descriptor semantics.
- [x] Re-verify `doc/extension-api.md` after implementation and update only for implementation-time drift from the already-completed design-doc alignment of the `:psi.ui/...` attrs, descriptor fields, unavailable semantics, and compatibility-only role of UI-type.
- [x] Re-verify `doc/architecture.md` after implementation and update only for implementation-time drift from the already-completed design-doc alignment that qualifies UI-type branching guidance in favour of capability/action querying.
- [x] Update README only if implementation adds or changes an extension-facing pointer that needs to mention the new queryable UI surface. Not needed; extension-facing guidance lives in `doc/extensions.md` and `doc/extension-api.md` for this slice.
- [x] Append implementation decisions, side-effecting invocation status, and any follow-up task reference to `implementation.md`.
- [x] Run focused tests for affected resolver/runtime/frontend namespaces.
- [x] Run targeted lint for changed Clojure and Emacs Lisp files.
- [x] Run broader verification required by project convention if focused changes touch shared runtime/query seams.
- [x] Re-read changed plan, steps, docs, tests, and code for coherence with `design.md` acceptance criteria.
- [x] Add provider-error diagnostic redaction before exposing `:psi.ui/diagnostic`: redact stack traces, frontend object printed forms, tokens/secrets, secret-bearing paths/data, and arbitrary exception data; add focused tests proving bounded redacted output.
- [x] Update `create-nullable-extension-api` so documented UI capability queries that include `:psi.ui/diagnostic` return the nullable unsupported-console UI capability map, and add coverage for that query shape.
- [x] Validate and cover provider results that set `:psi.ui/available? false` while advertising capabilities or available actions; they must not expose contradictory available action descriptors through EQL.
- [x] Ensure RPC/Emacs contexts do not expose the default static Emacs make-visible provider before the late-bound RPC UI capability provider is installed; add coverage for bootstrap/pre-install unavailable semantics.
- [x] Normalize/trim Emacs RPC focused session ids before advertising make-visible availability, and cover nil/blank/whitespace focus state as no-attached UI.
- [x] Ensure TUI contexts do not expose a default attached TUI provider before the TUI frontend/state is installed, and clear or downgrade the provider on TUI shutdown; add pre-start/bootstrap/shutdown coverage.
- [x] Reject provider action descriptors with extra unqualified/foreign keys before exposing them through EQL, and add focused coverage for fail-closed provider-error semantics.
- [x] Reject provider action invocation maps with extra unqualified/foreign keys before exposing them through EQL, and add focused coverage for fail-closed provider-error semantics.
- [x] Add provider normalization tests for omitted optional `:ui-event` payload and `:mutation` params defaulting to `{}`; fix implementation if coverage exposes contract drift.
- [x] Add focused real extension API coverage proving `ext/create-extension-api` with `runtime-fns/make-extension-runtime-fns` can query the documented `:psi.ui/...` capability/action attrs without explicit session-id input or extension permissions.
- [x] Add TUI lifecycle coverage proving `start-tui-runtime!` clears the active UI capability provider when `tui-start-fn!` throws, preventing stale attached UI advertisements after exceptional frontend shutdown.
- [x] Add provider normalization tests proving non-vector `:psi.ui/capabilities` and `:psi.ui/actions` fail closed to provider-error; fix normalization if the tests expose collection-shape drift.
- [x] Update `doc/extensions.md` and `doc/extension-api.md` to state that task 190 exposes queryable UI descriptors only; side-effecting descriptor submission through the core UI action request path is not implemented until `191-ui-action-invocation`, so current extension guidance must not imply callers can submit make-visible descriptors yet.
- [ ] Reject provider action invocation maps with extra same-namespace but non-schema `:psi.ui.invocation/...` keys before exposing them through EQL, and add focused coverage for fail-closed provider-error semantics.
