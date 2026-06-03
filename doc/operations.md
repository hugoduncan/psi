# Deterministic operations

Psi exposes registered **deterministic operations** for direct invocation
outside a workflow run, through two surfaces that share one underlying
invocation/listing mechanism:

- a **slash command** surface (`/operations`, `/operation <id> {edn-args}`), and
- a **psi-tool `operation` action** (`op "list" | "invoke"`).

Both surfaces are thin adapters over the same shared mechanism — they differ
only in input parsing and output rendering, never in the invocation path. A
deterministic operation is the same reusable, pure or side-effect-bearing unit
that a workflow IR `invoke` step runs; these surfaces add direct entry points
into that existing runtime boundary without changing the operation contract,
registry, or runtime semantics.

Side-effecting operations (e.g. `github/edit-labels`) are invokable from both
surfaces; no new permission/capability gating applies beyond what already
governs psi-tool and commands.

## psi-tool surface

Operation requests use:

```clojure
{:action "operation"
 :op "list" | "invoke"}
```

### `op: "list"`

List the deterministic operations registered for the invoking session.

```clojure
{:action "operation"
 :op "list"}
```

Semantics:

- returns each operation's **id and description**, **sorted by id** (ascending,
  string compare) for deterministic output
- `operation-id` and `args` are **ignored** (not rejected) — list takes no
  parameters
- an empty registry yields an empty `:operations []` collection (not blank
  output)

Structured result:

```clojure
{:psi-tool/action :operation
 :psi-tool/operation-op :list
 :psi-tool/overall-status :ok
 :psi-tool/operations [{:id "github/find-issue" :description "…"}
                       {:id "workflow/constant-routing" :description "…"}]
 :psi-tool/duration-ms 0}
```

### `op: "invoke"`

Invoke an operation by id with EDN-map args.

```clojure
{:action "operation"
 :op "invoke"
 :operation-id "github/find-issue"
 :args "{:number 205}"}
```

Params:

- `operation-id` (string, required for `invoke`) — the registered operation id
- `args` (EDN **map string**, default `{}` when absent) — the invocation args;
  must parse to a map or the request is rejected with a `must be an EDN map`
  validation error

Semantics:

- routes through the existing runtime boundary; `operation-id` is passed
  positionally and injected into the invocation map by the runtime
- the invocation map carries `:args`, `:ctx`, `:session-id`, an optional
  `:parent-session-id` (only when the invoking session has a known parent), and
  `nil` `:workflow-run-id` / `:step-id`
- the tagged result is projected into `:psi-tool/result`, rendering **every
  top-level key** of the tagged result (no enumerated subset). Each value is
  `pr-str`'d and **per-key truncated** to 2000 characters; when exceeded, the
  first 2000 characters are kept and a `… (truncated, N chars total)` marker is
  appended (`N` is the untruncated character count)
- `:psi-tool/overall-status` reflects the tagged result's `:status`

Structured result:

```clojure
{:psi-tool/action :operation
 :psi-tool/operation-op :invoke
 :psi-tool/overall-status :ok
 :psi-tool/result {:status ":ok" :data "{:number 205}" :summary "…"}
 :psi-tool/duration-ms 3}
```

By the result schema, `:ok` results carry `:status` and `:data` (plus optional
`:summary` / `:details`); `:error` results carry `:status`, `:reason`, and
`:message` (plus optional `:details`). Whatever keys are present are all
rendered.

## Error surfacing

Both surfaces surface failures clearly rather than crashing, dispatching on the
ex-info `:type`:

- **unknown operation id** → `:missing-deterministic-operation`
- **malformed operation result** → `:malformed-operation-result`
- **malformed / non-map `args`** → a validate-phase error (`must be an EDN map`,
  or an unreadable-EDN parse failure)

On the psi-tool surface these render `:psi-tool/overall-status :error` with a
distinct `:psi-tool/error` summary (the summary's `:kind` distinguishes
`:missing-operation`, `:malformed-result`, and `:validate`); the serialized
result is marked `:is-error true`. Any other throwable is canonicalized by the
runtime into an `:error` tagged result.

A handler **returning** `{:status :error …}` (a domain error, not an exception)
is a normal tagged-error result: it is projected like any other result and
drives `:psi-tool/overall-status :error` / `:is-error true`.

## Command surface

- `/operations` lists the registered operations as `<id> — <description>`
  lines, sorted by id. An empty registry prints
  `No deterministic operations registered.`
- `/operation <id> {edn-args}` invokes operation `<id>` with the EDN-map
  `{edn-args}` (default `{}` when omitted). `<id>` is the first
  whitespace-delimited token; the remainder is parsed as the EDN args map.
- The tagged result renders as text: the `:status` line first, then the
  remaining top-level keys sorted ascending by their printed (`pr-str`) form,
  one `<key> <value>` line per key. Each value is `pr-str`'d and truncated to
  2000 characters with the same `… (truncated, N chars total)` marker as the
  psi-tool surface (rendering is identical across surfaces).
- A blank `<id>` prints `Usage: /operation <id> {edn-args}`; malformed or
  non-map args print a clear parse error; an unknown id or a malformed result is
  surfaced as a distinct text message (never a crash).

See [`doc/tui.md`](tui.md) for where these commands sit among the other
interactive commands.
