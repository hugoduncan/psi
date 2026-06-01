# UI action invocation

## Intent

Implement the side-effecting half of the queryable UI action contract introduced by `190-core-queryable-ui`.

## Scope

Extensions and core callers can submit a previously discovered UI action descriptor through a constrained core-owned request helper/event path, without importing frontend namespaces or using generic extension dispatch permissions.

In scope:

- `:psi.ui/request-action` request construction and validation.
- Request/result data using `:psi.ui.request/...` and `:psi.ui.result/...` keys from task 190.
- Validation for unknown action ids, unavailable descriptors, malformed invocation data, unsupported invocation kinds, stale session/runtime correlation, and current-provider mismatch.
- Active UI adapter subscription/execution for Emacs `psi-emacs-show-active`.
- State/query or event diagnostics for accepted, completed, rejected, unsupported, failed, and timeout outcomes if needed.

Out of scope:

- New arbitrary UI mutation permissions.
- Multiple simultaneous active UI providers.
- New UI capabilities beyond those defined in task 190.

## Acceptance criteria

- Extension-facing code can submit a make-visible descriptor through a constrained helper rather than frontend namespaces.
- Emacs executes the declarative `:emacs-command` invocation for `psi-emacs-show-active` through the adapter boundary.
- Invalid, stale, unavailable, or unsupported requests return bounded structured rejection/unsupported/failed results.
- Query-only UI capability/action discovery from task 190 remains unchanged.
