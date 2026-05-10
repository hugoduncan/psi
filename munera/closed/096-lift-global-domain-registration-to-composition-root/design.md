Goal: move ownership of global resolver/mutation/domain registration out of `agent-session.context` and into an explicit higher-level composition/bootstrap root, so domain components expose only their own local registration surfaces and no longer discover whole-system bootstrap behavior dynamically.

Context:
- task 095 extracted the abstract state kernel and removed the static `agent-session -> system-bootstrap` dependency
- the current cycle break uses runtime indirection in `agent-session.context` via `requiring-resolve 'psi.system-bootstrap.core/register-all-domains!`
- that seam is useful as a temporary decoupling tactic, but it leaves architectural ownership blurry because `agent-session` still knows there is a global “register everything” operation
- current registration responsibilities are mixed between domain-local registration helpers and whole-application assembly concerns

Problem:
- `agent-session.context` still participates indirectly in global app assembly
- local domain registration and whole-system registration are not structurally separated
- runtime discovery via `requiring-resolve` obscures ownership, makes startup semantics less explicit, and leaves a temporary cycle-breaking seam in production code
- isolated/local query-context construction and global/app query-context construction are not clearly separated as different responsibilities

Intent:
- make one explicit higher-level composition root own whole-system registration
- leave each domain component responsible only for registering its own resolvers/mutations into a provided query context
- remove runtime bootstrap discovery from `agent-session.context`
- clarify the distinction between:
  - local/isolated domain registration
  - assembled isolated query-context registration
  - global/application assembly registration
- preserve current behavior while making ownership and dependency direction explicit

Terminology for this task:
- `session-facing local surface` means the isolated query/mutation registration surface owned by `agent-session` for session-centric local contexts; for this task, that surface may remain broader than strictly agent-session-only if preserving current isolated session behavior requires including adjacent session-centric capabilities such as history
- `assembled isolated registration` means a composition-owned helper that wires multiple domains into a provided isolated query context
- `global/application assembly registration` means composition-owned registration into the global query registry

Explicit decisions for this task:
- the chosen composition root is `psi.system-bootstrap.core` unless implementation uncovers a stronger reason to rename or relocate it; if that happens, the replacement path must be recorded explicitly in `implementation.md`
- this task treats `psi.system-bootstrap.core` as the authoritative composition root, not merely a temporary staging location
- `psi.system-bootstrap.core` is expected to own both:
  - whole-app/global registration into the global query registry
  - fully assembled registration into an isolated query context
- `psi.agent-session.core/register-resolvers!` and `psi.agent-session.core/register-mutations!` should be removed rather than retained as compatibility wrappers, unless a blocking caller makes a temporary compatibility shim unavoidable; blocked here means removal would force unrelated broad startup/runtime churn outside this task’s scope; if a shim is kept, it must be explicitly called out as a follow-on debt and must not preserve dynamic bootstrap discovery
- `agent-session` local registration helpers should mean “register the session-facing local surface needed by isolated session query/mutation contexts”, not “register the whole application”
- for this task, the existing session-facing local surface may continue to include the history query/mutation surface if that is required to preserve current isolated session behavior; the key invariant is that composition ownership for “register everything” moves above the domain
- if some current tests actually require assembled multi-domain registration rather than session-facing local registration, they should migrate to an explicit composition helper owned above the domain rather than stretching `agent-session` helpers to mean “whole system”

In scope:
- identify all current global registration entrypoints/call sites
- define the composition-root namespace/path that will own whole-app registration
- keep or refine per-domain local registration functions such as `register-resolvers-in!` / `register-mutations-in!`
- update startup/composition paths so higher-level bootstrap explicitly assembles all domains
- remove `requiring-resolve`-based discovery from `agent-session.context`
- document the local-vs-global registration split explicitly
- add/update focused tests proving global registration still works and that local domain registration still works in isolation

Out of scope:
- further state-kernel extraction work beyond what 095 already landed
- redesigning resolver semantics or query engine semantics
- changing domain behavior of agent-session/history/memory/etc.
- broad adapter/UI/runtime redesign unrelated to registration ownership

Acceptance:
- `agent-session.context` no longer uses `requiring-resolve` to discover global registration
- one explicit higher-level composition/bootstrap root owns whole-system registration
- production startup paths register all domains only through composition-root-owned entrypoints
- domain components expose only domain-local registration helpers
- local/isolated context construction remains possible without whole-app bootstrap coupling
- no static or runtime dependency edge from `agent-session` back into whole-system bootstrap remains for registration ownership
- focused verification proves:
  - global assembled registration still works
  - isolated/local agent-session registration still works
- docs/task notes explain the resulting ownership split clearly

Concrete done criteria:
- the chosen composition-root namespace/path is recorded explicitly
- all `register-all-domains!` ownership lives above domain components
- `agent-session.context` no longer performs global registration discovery
- any remaining registration helper in `agent-session` is domain-local in meaning only
- `agent-session` source no longer mentions `psi.system-bootstrap` for registration ownership
- tests cover both local registration and global assembled registration
- the temporary cycle-breaking seam introduced in task 095 is removed
- any test or helper needing assembled multi-domain registration uses an explicit composition-owned entrypoint rather than a domain-owned “register everything” wrapper

Design constraints:
- prefer explicit assembly over dynamic discovery
- preserve one-way dependency direction: composition root -> domains
- do not blur local registration helpers into global bootstrap ownership
- keep isolated test/local context setup straightforward
- avoid turning this task into a general startup/runtime rewrite

Related work:
- this is a follow-on to task 095, which extracted the state kernel and introduced a temporary `requiring-resolve` seam to break the static cycle cleanly
- once complete, task 095’s temporary bootstrap seam can be considered retired
