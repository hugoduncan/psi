# Learning

Accumulated discoveries from ψ evolution.

---

## 2026-02-28 - Tool Output Delta

### λ Tool-output EQL introspection contract (stable attrs)

Tool-output policy and telemetry are queryable via these stable attrs:

- `:psi.tool-output/default-max-lines`
- `:psi.tool-output/default-max-bytes`
- `:psi.tool-output/overrides`
- `:psi.tool-output/calls`
- `:psi.tool-output/stats`

Per-call entities use `:psi.tool-output.call/*` attrs:

- `:psi.tool-output.call/tool-call-id`
- `:psi.tool-output.call/tool-name`
- `:psi.tool-output.call/timestamp`
- `:psi.tool-output.call/limit-hit?`
- `:psi.tool-output.call/truncated-by`
- `:psi.tool-output.call/effective-max-lines`
- `:psi.tool-output.call/effective-max-bytes`
- `:psi.tool-output.call/output-bytes`
- `:psi.tool-output.call/context-bytes-added`

### λ Policy + truncation semantics

- Default tool output policy: `max-lines=1000`, `max-bytes=25600`.
- Per-tool overrides are read from session data `:tool-output-overrides`.
- `read`, `eql_query`, `ls`, `find`, `grep` use head-style truncation.
- `bash` uses tail-style truncation.
- Truncated `bash`/`eql_query` responses include `:details {:full-output-path ...}`.

### λ Context-bytes-added semantics

`contextBytesAdded` is measured from the shaped tool result content that is
actually recorded in the tool result message (post-truncation / post-formatting),
not raw underlying command/file output bytes.

### λ Temp artifact lifecycle

- Truncated full-output artifacts are persisted under one process temp root.
- `tool-output/cleanup-temp-store!` is invoked on orderly teardown paths.
- Cleanup failures are warning-only and do not block shutdown.

### λ EQL query pattern for telemetry

Example query shape:

```clojure
[:psi.tool-output/stats
 {:psi.tool-output/calls
  [:psi.tool-output.call/tool-name
   :psi.tool-output.call/limit-hit?
   :psi.tool-output.call/output-bytes
   :psi.tool-output.call/context-bytes-added]}]
```

Use this after one or more tool calls to inspect per-call limit hits plus session
aggregates (`:total-context-bytes`, `:by-tool`, `:limit-hits-by-tool`).

## 2026-02-27 - mcp-tasks-run Orchestration + Tool Scoping

### λ mcp-tasks CLI Is CWD-Sensitive

Running `mcp-tasks add/update` from the wrong directory can create/use
`.mcp-tasks` in an unexpected parent and write tasks to the wrong repo
(observed: `/Users/duncan/projects/hugoduncan/.mcp-tasks/tasks.ednl`).

Always execute task mutations with an explicit project working directory
(shell `:dir`) and verify `pwd` before any write operation.

### λ Extension Workflow Futures Must Keep Invoke IDs Free of Runtime Keys

Fulcrologic statecharts `:future` invocation runtime tracks active futures in
an internal map keyed by `child-session-id = <session-id>.<invokeid>`.
When the parent state exits, `stop-invocation!` looks up that key and calls
`future-cancel` on the stored value.

If workflow data injects keys that collide with invoke metadata (e.g. `:control`
or other invoke-runtime keys), the runtime can store/read a non-Future value
for the invoke slot and fail with:

`class clojure.lang.Keyword cannot be cast to class java.util.concurrent.Future`

Observed symptom in `mcp-tasks-run`: workflow entered `:running`, then failed
immediately on `done.invoke.runner` with the Future cast exception before any
step execution.

Rule: keep extension runtime control values namespaced and avoid generic keys
that may overlap invocation internals.

### λ Standard Tool `:cwd` Support Prevents Extension Tool Forking

Worktree-aware orchestration needs tools to run relative to a worktree.
Re-defining `read/bash/edit/write` inside an extension duplicates behavior
and drifts from core semantics.

Better pattern:
- built-in tools accept optional `:cwd`
- expose a shared factory (e.g. `make-tools-with-cwd`)
- extensions/sub-agents reuse standard tools with scoped cwd

## 2026-02-26 - Hierarchical API Error Resolvers

### λ Hierarchical Resolver Pattern — Cheap List, Lazy Detail

Pathom3 resolvers naturally support hierarchical drill-down: a list
resolver outputs entities with identity keys + ctx, and downstream
detail resolvers only fire when their output attributes are queried.

Pattern:
```
Level 1 (list, cheap): scan for entities, output identity + ctx
Level 2 (detail, moderate): seeded by identity, parses/enriches
Level 3 (expensive): seeded by identity, reconstructs full state
```

Each level's resolver is independent — querying L1 never triggers L2/L3.
The ctx passthrough (`{:psi/agent-session-ctx agent-session-ctx}`) in
each list entity seeds downstream resolvers automatically.

### λ Request Shape as Diagnostic Surface

Computing "what would the API request look like?" is valuable both for
error forensics and live "will my next prompt fit?" checks.

Key insight: the same `compute-request-shape` fn serves both:
- `:psi.api-error/request-shape` — messages[0..error-index), post-mortem
- `:psi.agent-session/request-shape` — all current messages, live check

The shape is provider-agnostic (token estimate from char count / 4,
structural checks on agent-core messages directly).

### λ 400 Root Cause: headroom-tokens = 186

The resolvers immediately revealed the root cause: 320 messages with
~183K estimated tokens + 16K max-output left only 186 tokens of headroom.
The actual tokenizer likely pushed it over 200K. Auto-compaction didn't
trigger before this final call.

### λ Context Window Info Not in Session Data Atom

The session-data-atom `:model` only stores `{:provider :id :reasoning}`,
not the full model config. Context window and max-tokens come from the
ai-model config (stored separately in session-state). Resolvers need
fallback paths: session-data → model-config-atom → defaults.

## 2026-02-26 - OAuth Module

### λ No Clojure OAuth Client Library Fits CLI Use Case

All existing Clojure OAuth libs (`ring-oauth2`, `clj-oauth2`, `clj-oauth`)
are **server-side Ring middleware** — they authenticate users visiting a web
app. A CLI agent needs **client-side** OAuth: build auth URL, open browser,
receive local callback, exchange code. None of the libs do this.

**Decision**: Build directly on `clj-http` (already a dep) + JDK built-ins.
The actual OAuth logic is ~150 lines of shared infra + ~40 lines per provider.

### λ JDK HttpServer for OAuth Callback — Zero New Deps

`com.sun.net.httpserver.HttpServer` is built into the JDK, confirmed
available. Binds to localhost, receives one redirect callback, shuts down.
No need for http-kit or Ring just for this.

```clojure
(doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
  (.createContext "/" handler)
  (.start))
```

### λ Nullable Callback Server — Deliver Without Network

The callback server's Nullable uses a `LinkedBlockingQueue` with a
`:deliver` fn instead of a real HTTP server:

```clojure
;; Production: real HTTP callback
(let [srv (cb/start-server {:port 0})]
  ((:wait-for-code srv) 30000))

;; Test: inject result directly
(let [srv (cb/create-null-server)]
  ((:deliver srv) {:code "abc" :state "xyz"})
  ((:wait-for-code srv) 3000))
```

Same interface, same `wait-loop` fn — only the delivery mechanism differs.

### λ Credential Store Nullable — :persisted Atom for Inspection

The null store captures what was persisted via a `:persisted` atom,
letting tests verify persistence without disk I/O:

```clojure
(let [s (store/create-null-store)]
  (store/set-credential! s :anthropic {:type :api-key :key "k"})
  @(:persisted s))
;; => {:anthropic {:type :api-key :key "k"}}
```

### λ OAuth Context Composes Store + Providers — Three Nullable Layers

`oauth.core/create-null-context` composes a null store with stub providers.
Tests can override login/refresh behaviour per-context:

```clojure
(create-null-context
  {:credentials {:anthropic {:type :oauth :access "old" :expires 1000}}
   :login-fn    (fn [_] {:type :oauth :access "new" ...})})
```

Three independent Nullable layers (server, store, context) compose cleanly.

### λ Distill Spec Before Build — Scope Decisions Made Upfront

Writing `oauth-auth.allium` before code forced explicit decisions:
- Credential as sum type (ApiKeyCredential | OAuthCredential)
- 5-level API key priority chain
- Token refresh with concurrent-process awareness
- Provider contract surface (login, refresh, getApiKey, modifyModels)
- What's excluded (PKCE crypto, HTTP details, TUI rendering)

The spec excluded concerns cleanly, preventing scope creep during
implementation.

---

## 2026-02-26 - Retry, Tool Call Introspection, bash stdin

### λ should-retry? Guard Read From Wrong Data Source

The `should-retry?` statechart guard checked `(:messages sd)` from
session-data — but messages live in **agent-core**, not session-data.
`(:messages sd)` was always nil, so retry never fired.

**Fix**: Read from the `:pending-agent-event` which carries `:messages`
from agent-core's `:agent-end` event:

```clojure
;; ✗ session-data has no :messages key
(let [msgs (:messages @(:session-data-atom data)) ...])

;; ✓ agent-end event carries messages
(let [msgs (:messages (:pending-agent-event data)) ...])
```

### λ HTTP Status Lost Through Error Chain — Propagate Structured Data

clj-http throws `ExceptionInfo` with `:status` in `ex-data`, but every
layer stringified it: `(str e)` / `(ex-message e)`. The numeric status
was lost by the time it reached the retry guard.

**Fix**: Extract `:http-status` from `ex-data` at each catch site and
propagate it through the entire chain:

```
provider catch → :http-status in error event
streaming catch → :http-status preserved
turn statechart → :http-status in turn data
executor → :http-status on message map
statechart guard → numeric check
```

`retry-error?` now checks numeric status first (reliable), falls back
to string patterns (legacy compatibility):

```clojure
(def ^:private retriable-http-statuses #{429 500 502 503 529})

(defn retry-error? [stop-reason error-message http-status]
  (and (= stop-reason :error)
       (or (contains? retriable-http-statuses http-status)
           (some #(re-find % (or error-message "")) ...))))
```

### λ Hierarchical Pathom3 Resolvers — Split List from Detail

One monolithic resolver that computes everything can't be decomposed by
the EQL consumer. Split into list + detail resolvers:

```clojure
;; List resolver — cheap, no result loading
(pco/defresolver agent-session-tool-calls [...]
  {::pco/output [{:psi.agent-session/tool-call-history
                  [:psi.tool-call/id :psi.tool-call/name
                   :psi.tool-call/arguments :psi/agent-session-ctx]}]})

;; Detail resolver — runs only when result/error queried
(pco/defresolver tool-call-result [...]
  {::pco/input [:psi.tool-call/id :psi/agent-session-ctx]
   ::pco/output [:psi.tool-call/result :psi.tool-call/is-error]})
```

Three query levels, each triggers only what's needed:
- `[:psi.agent-session/tool-call-history-count]` → count only
- `[{… [:psi.tool-call/name]}]` → list resolver only
- `[{… [:psi.tool-call/name :psi.tool-call/result]}]` → list + detail

**Key**: Pass `:psi/agent-session-ctx` through the list entities so the
detail resolver can access agent-core messages.

### λ Agent-Core Messages vs Session Journal

Messages live in **agent-core** (`(:messages (agent/get-data-in agent-ctx))`),
not the session journal. The journal stores session entries (`:kind :message`)
but the journal atom was empty while agent-core had 9 messages. Always check
where data actually lives before writing resolvers.

### λ shell/sh Stdin Pipe Breaks rg

`clojure.java.shell/sh` connects stdin as a pipe. ripgrep detects
`is_readable_stdin=true` and searches **stdin** instead of the working
directory. Result: `rg pattern` returns nothing + exit 1.

**Fix**: Use `babashka.process/shell` with `:in (java.io.File. "/dev/null")`.
This gives rg a file descriptor (not a pipe), so it correctly searches cwd.
Pipes within commands still work because bash establishes their own stdin.

```clojure
(proc/shell {:out :string :err :string :continue true
             :in (java.io.File. "/dev/null")}
            "bash" "-c" command)
```

**Note**: `:in ""` does NOT work — empty string still creates a readable pipe.

### λ Protocol Reload Breaks Running Records

`:reload-all` redefines protocols, but existing record instances still
reference the old protocol. Statechart `LocalMemoryStore` records created
at session startup break with `No implementation of method` errors.

**Rule**: Changes to code that touches protocols/records used by the
statechart require a process restart. Namespace `:reload` (not `:reload-all`)
is safe for most changes but not protocol-dependent code.

### λ Statechart Guards Are Captured at Startup

Statechart guard functions are closures captured when the chart is created.
Reloading the namespace that defines `should-retry?` doesn't replace the
guard in the already-running statechart. The new code only takes effect
after a process restart.

### λ Pathom3 Index Is the Resolver Registry

The Pathom3 environment indexes are queryable:

```clojure
(let [env (resolvers/build-env)]
  ;; All resolver names
  (keys (:com.wsscode.pathom3.connect.indexes/index-resolvers env))
  ;; All queryable attributes
  (keys (:com.wsscode.pathom3.connect.indexes/index-attributes env)))
```

25 resolvers, 80 attributes across 8 namespaces (agent-session, tool-call,
turn, extension, prompt-template, skill, ui, agent-session-ctx).

---

## 2026-02-25 - UI Extension Points Implementation

### λ Extension UI State Lives in TUI Component, Not Agent-Session

`psi.tui.extension-ui` lives in the `tui` component because both `tui/app.clj`
(which renders it) and `agent-session/extensions.clj` (which creates UI contexts)
need to require it.  Since agent-session depends on tui but not vice versa,
the module must live in tui.

### λ Promise Bridge for Blocking Dialogs in Elm Architecture

The challenge: extensions call `(confirm "title" "msg")` and block, but the
TUI is message-driven (Elm update/view).  Solution — enqueue a dialog with a
`promise`, block the extension thread on `deref`, and have the TUI update fn
call `deliver` when the user responds.

```clojure
;; Extension thread (blocking)
(let [result (deref (:promise dialog))]  ; blocks here
  (if result "confirmed" "cancelled"))

;; TUI update thread (resolves)
(deliver (:promise active-dialog) true)  ; unblocks extension
```

FIFO queue ensures one dialog at a time. `advance-queue!` promotes next.

### λ clear-all! Must Snapshot Before Reset

When clearing all UI state (e.g. extension reload), snapshot the active and
pending dialogs **before** resetting the atom.  If you cancel the active dialog
first, `cancel-dialog!` calls `advance-queue!` which promotes a pending dialog
to active — then the pending loop finds nothing to deliver:

```clojure
;; ✗ Race: cancel advances queue, doseq finds empty pending
(cancel-dialog! ui-atom)
(doseq [d (get-in @ui-atom [:dialog-queue :pending])] (deliver (:promise d) nil))

;; ✓ Snapshot all, reset, then deliver
(let [active  (get-in @ui-atom [:dialog-queue :active])
      pending (get-in @ui-atom [:dialog-queue :pending])]
  (reset! ui-atom {...empty...})
  (when active (deliver (:promise active) nil))
  (doseq [d pending] (deliver (:promise d) nil)))
```

### λ Dialog Routing in Elm Update — Check Before Normal Input

When a dialog is active, **all keypresses** go to the dialog handler, not the
editor.  The update function must check for active dialog before checking idle
state, otherwise escape/enter goes to the wrong handler:

```clojure
(cond
  ;; ctrl+c always quits
  (msg/key-match? m "ctrl+c") [state charm/quit-cmd]
  ;; Dialog intercepts ALL key input when active
  (and (has-active-dialog? state) (msg/key-press? m))
  (handle-dialog-key state m)
  ;; Normal idle input...
  ...)
```

### λ charm.clj Key Press Messages Have :key Field

charm.clj key press messages are maps with `:key` (a string).  For single
printable characters, `(:key m)` returns the character.  There is no
`key-runes` function — that's a Bubble Tea concept.

### λ EQL Snapshot Must Strip Promises and Functions

Promises and function objects are not serialisable.  The `snapshot` fn for
EQL resolvers strips `:promise` from dialog maps and `:render-*-fn` from
renderer maps, returning only data the resolver can safely expose.

### λ Allium Spec → Design Decisions → Implementation

Writing the allium spec first forced 10 design decisions to be explicit
before any code was written:

1. State ownership (centralized Elm model)
2. Dialog model (promise bridge)
3. Dialog queueing (FIFO)
4. Widget placement (above/below editor, keyed by ext-id)
5. Status vs widget vs notification (three primitives)
6. Custom rendering (register fn by tool-name/custom-type)
7. Render output (ANSI strings)
8. UI availability (:ui key, nil when headless)
9. Screen takeover (deferred)
10. EQL queryability (yes, read-only)

The spec's `open_question` blocks captured decisions to revisit later without
blocking implementation.  This is significantly faster than discovering
these decisions during coding.

### λ Deferred Items Get Smaller After Spec

Items originally on the deferred list (`RegisteredCommand`, extension tool
wrapping) were already partially implemented by the time the UI spec was done.
Writing the spec clarified that tool wrapping pre/post hooks were already
implemented in `wrap-tool-executor`.  The deferred list shrinks when you spec
precisely enough to see what's already done.

---

## 2026-02-25 - Extension System Implementation

### λ Clojure Extensions = load-file + init fn

Extensions are `.clj` files with a namespace that defines an `init` function.
The loader reads the `ns` form, `load-file`s the source, resolves the `init`
var, and calls it with an ExtensionAPI map.

```clojure
;; ~/.psi/agent/extensions/hello_ext.clj
(ns my.hello-ext)
(defn init [api]
  ((:register-command api) "hello" {:description "Say hi"
                                    :handler (fn [args] (println "Hello" args))})
  ((:on api) "session_switch" (fn [ev] (println "switched!" ev))))
```

This is simpler than pi's TypeScript approach (jiti + virtualModules + aliases)
because Clojure's `load-file` handles compilation natively.

### λ ExtensionAPI as a Plain Map

The API passed to extension `init` fns is a plain Clojure map of functions.
No protocol, no deftype — just a map with keyword keys for registration and
action fns.  Each action fn delegates to the session context via closures:

```clojure
{:on                 (fn [event-name handler-fn] ...)
 :register-tool      (fn [tool] ...)
 :register-command   (fn [name opts] ...)
 :register-flag      (fn [name opts] ...)
 :get-flag           (fn [name] ...)
 :set-session-name   (fn [name] ...)
 :events             {:emit (fn [ch data] ...) :on (fn [ch handler] ...)}}
```

Action fns that haven't been wired to a session throw on call
(same pattern as pi's "throwing stubs until runner.initialize()").

### λ Tool Wrapping via wrap-tool-executor

Tool wrapping creates a higher-order function around the tool executor:

```clojure
(let [wrapped (ext/wrap-tool-executor reg execute-tool)]
  (wrapped "bash" {"command" "ls"}))
```

Pre-hook: dispatches `tool_call` event → handler returns `{:block true}` to prevent.
Post-hook: dispatches `tool_result` event → handler returns `{:content "modified"}`.

This is compositional — the wrapped fn has the same signature as the original.

### λ Forward Declarations for Mutual References

When `make-extension-action-fns` (early in core.clj) needs to call
`set-session-name-in!` (defined later), use `(declare set-session-name-in!)`.
This is standard Clojure but easy to forget when adding cross-referencing
functions to an existing namespace.

### λ Discovery Convention: .clj in dir OR extension.clj in subdir

Extension discovery searches:
1. `.psi/extensions/*.clj` — direct files
2. `.psi/extensions/*/extension.clj` — subdirectory convention

This mirrors pi's `index.ts` convention for directories.

---

## 2026-02-25 - Per-Turn Streaming Statechart (Step 6)

### λ FlatWorkingMemoryDataModel Uses Its Own Namespace Key

The fulcrologic `FlatWorkingMemoryDataModel` stores and reads data from
`::wmdm/data-model` (`:com.fulcrologic.statecharts.data-model.working-memory-data-model/data-model`),
**NOT** from `::sc/data-model` (`:com.fulcrologic.statecharts/data-model`).

These are different fully-qualified keywords. Using the wrong one means
scripts receive `nil` as their `data` parameter — actions silently don't
fire because the execution model swallows the nil.

```clojure
;; ✗ WRONG — different namespace, scripts get nil
(update wm ::sc/data-model merge extra-data)

;; ✓ CORRECT — matches what FlatWorkingMemoryDataModel reads
(require '[com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm])
(update wm ::wmdm/data-model merge extra-data)
```

### λ sp/start! Puts Initial Data at WM Top-Level, Not in Data Model

`sp/start!` (processor-level) merges the `params` map into the working
memory at the **top level** alongside `::sc/configuration`, `::sc/session-id`,
etc.  It does NOT populate `::wmdm/data-model`.

Scripts read from `(sp/current-data data-model env)` which reads
`::wmdm/data-model`.  So initial user data (actions-fn, context atoms)
must be explicitly placed there after `start!`:

```clojure
(let [wm (sp/start! processor env :chart-id {::sc/session-id sid})]
  (save-working-memory! sc-env sid
    (assoc wm ::wmdm/data-model {:actions-fn af :turn-data td})))
```

### λ Session Statechart Data Model Bug (Pre-existing)

The existing session statechart (`statechart.clj`) uses `::sc/data-model`
for merging extra-data in `send-event!`.  This is the **wrong key** — the
flat data model reads from `::wmdm/data-model`.  Guards that check
`:pending-agent-event` receive nil and always return false.

The system still works because:
1. Auto-compact and retry guards silently fail (return false on nil)
2. The `:session/agent-event` with `:agent-end` falls through all guards,
   leaving the session in `:streaming` — but `run-agent-loop!` manages
   the session lifecycle directly via `end-loop-in!`
3. No test exercises the reactive guard path end-to-end

**Impact**: Auto-compaction and auto-retry via statechart guards are
non-functional.  They need the same `::wmdm/data-model` fix.

### λ Debugging WM Data Flow — Use Assertion Messages

When `println` output is captured by test harnesses (kaocha captures
stdout per test), use **assertion message comparison** to surface values:

```clojure
;; ✗ println swallowed by test output capture
(println "DM:" dm)

;; ✓ intentionally-wrong assertion shows actual value in failure output
(is (= :show-me-the-keys (keys wm)))
```

### λ Self-Transitions Required for simple-env Accumulation

Targetless transitions (no `:target` attribute) in fulcrologic's
`simple-env` are untested territory.  Use **self-transitions** (`:target`
pointing to the current state) for accumulation events like
`:turn/text-delta`.  The exit/re-entry overhead is negligible when there
are no `on-entry`/`on-exit` handlers.

```clojure
;; Self-transition — safe, works in simple-env
(ele/transition {:event  :turn/text-delta
                 :target :text-accumulating}
  (ele/script {:expr (fn [_env data] (dispatch! data :on-text-delta))}))
```

### λ Per-Turn Statechart Architecture

Each streaming turn gets its own short-lived statechart context:

```
:idle → :text-accumulating ⇄ :tool-accumulating → :done | :error
```

- **Statechart** owns state transitions (explicit, queryable)
- **turn-data atom** owns accumulated content (text buffer, tool calls)
- **actions-fn** bridges statechart events to side effects (agent-core calls)
- **turn-ctx-atom** on agent-session context enables nREPL introspection

Provider events are translated 1:1 to statechart events:
`:text-delta` → `:turn/text-delta`, `:toolcall-start` → `:turn/toolcall-start`, etc.

The executor creates a fresh turn context per `stream-turn!` call and
stores it in the session's `:turn-ctx-atom` for live EQL queries.

### λ with-redefs Works on Private Vars

`with-redefs` resolves symbols via `(var sym)` at compile time.  Private
vars ARE accessible through fully-qualified `#'ns/private-var`.  This is
the standard pattern for stubbing internal functions in tests:

```clojure
(with-redefs [psi.agent-session.executor/do-stream!
              (fn [_ctx _conv _model _opts consume-fn]
                (consume-fn {:type :start})
                (consume-fn {:type :text-delta :delta "hello"})
                (consume-fn {:type :done :reason :stop}))]
  (executor/run-agent-loop! ...))
```

---

## 2026-02-25 - agent-session Component

### λ Statechart Working Memory Data Pattern

`simple/simple-env` uses a **flat** working memory data model.  Guard and script
functions receive `(fn [env data])` where `data` is the flat WM map.  The current
event is stored at `:_event` inside `data` by the v20150901 algorithm.

To pass extra data to guards (e.g. the agent event that triggered a transition),
merge it into the WM before calling `sp/process-event!`:

```clojure
(defn send-event! [sc-env session-id event-kw extra-data]
  (let [wm  (get-working-memory sc-env session-id)
        wm' (if extra-data
              (update wm ::sc/data-model merge extra-data)
              wm)]
    (sp/save-working-memory! ...)
    (sp/process-event! ... wm' evt)))
```

Guards then read `(:pending-agent-event data)` — the key we merged in.

Initial WM is populated via `sp/start!`:
```clojure
(sp/start! processor env :chart-id {::sc/session-id id
                                     :session-data-atom a
                                     :actions-fn f
                                     :config c})
```

### λ Reactive Agent Event Bridge via add-watch

Agent-core's `events-atom` accumulates events (never reset between calls).
Bridge to session statechart using `add-watch` with old/new comparison:

```clojure
(add-watch (:events-atom agent-ctx) ::session-bridge
  (fn [_key _ref old-events new-events]
    (let [new-count (count new-events)
          old-count (count old-events)]
      (when (> new-count old-count)
        (doseq [ev (subvec new-events old-count new-count)]
          (sc/send-event! sc-env sc-session-id :session/agent-event
                          {:pending-agent-event ev}))))))
```

This is simpler than a callback and avoids modifying agent-core's API.

### λ Statechart Script Elements Pattern

Use `(ele/script {:expr (fn [env data] ...)})` inside transitions for side
effects, and `(ele/on-entry {} (ele/script {:expr ...}))` for entry actions.
Guards go in `{:cond (fn [_env data] ...)}` on the transition map.

The `actions-fn` in WM is a dispatcher: `(fn [action-key] ...)`.  Statechart
scripts call `(dispatch! data :action-key)` which is pure (reads from data, no
closures over ctx), keeping the statechart definition portable.

### λ Allium Sub-spec Splitting Pattern

When a monolithic `.allium` spec grows too large, split by orthogonal concern:
- Each sub-spec `use`s its dependencies by path
- Cross-references use `ext/` and `compact/` namespace prefixes
- Open questions follow each spec and reference the parent spec's original questions
- The original spec is retained as reference; sub-specs are the authoritative source

---

## 2026-02-25 - Global Query Graph Wiring (Step 4)

### λ register-resolvers! / register-resolvers-in! Pattern

Every component that contributes resolvers to the graph gets two registration
functions matching the pattern in `psi.ai.core`:

```clojure
(defn register-resolvers-in!
  "Isolated — for tests. rebuild? flag avoids double-rebuild when caller
   batches multiple components."
  ([qctx] (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r all-resolvers]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))))

(defn register-resolvers!
  "Global — call once at startup."
  []
  (doseq [r all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!))
```

The `rebuild?` flag prevents double-rebuild when a higher-level component
(e.g. introspection) registers multiple sub-component resolver sets then
rebuilds once at the end.

### λ Batched Registration — Single Rebuild

When a component wires N sub-components into a shared `QueryContext`, register
all resolver sets first and rebuild once at the end:

```clojure
;; introspection/register-resolvers-in!
(doseq [r introspection-resolvers] (register-resolver-in! qctx r))
(when session-ctx
  (agent-session/register-resolvers-in! qctx false))  ; rebuild?=false
(rebuild-env-in! qctx)                                  ; single rebuild
```

This avoids N intermediate Pathom index compilations (each `rebuild-env-in!`
recompiles the full index from scratch).

### λ Introspection Context Accepts Optional Sub-Contexts

When a component's isolated context needs to include another component's
graph, use an options map factory with optional keys — nil means "omit":

```clojure
(defn create-context
  ([] (create-context {}))
  ([{:keys [engine-ctx query-ctx agent-session-ctx]}]
   (->IntrospectionContext
    (or engine-ctx (engine/create-context))
    (or query-ctx  (query/create-query-context))
    agent-session-ctx)))  ; nil = no agent-session resolvers registered
```

`register-resolvers-in!` checks `(:agent-session-ctx ctx)` and conditionally
registers the additional resolver set.  Tests that don't need agent-session
simply call `(create-context)` and get the old behaviour.

### λ Unused Public Var — clojure-lsp INFO vs Error

`register-resolvers!` (global) has no callers in the codebase until startup
code is added — clojure-lsp reports it as `INFO: Unused public var`.  This is
**not an error** and does not block compilation.  It is suppressed by either:

- Adding `^:export` metadata to the var, or
- Calling it from startup code (e.g. `main.clj`)

The warning is expected for intentional public API entry points.

### λ Drop Unused Requires Immediately

Adding a require for a future refactor (e.g. `[psi.query.core :as query]` in
`main.clj`) and never using the alias leaves dead weight.  Remove it in the
same commit or the next atomic one.  clj-kondo and clojure-lsp may not warn
in all configurations, so check manually after every namespace edit.

---

## 2026-02-25 - Introspection Component

### λ Introspection = Engine Queries Itself via EQL

The introspection component wires engine + query together so the system
is self-describing via a uniform EQL surface.  Two namespaces:

- `psi.introspection.resolvers` — five Pathom3 resolvers; all accept
  context objects as EQL seed inputs (`:psi/engine-ctx`, `:psi/query-ctx`)
- `psi.introspection.core`      — public API, Nullable pattern throughout

Key design decisions:
1. **Contexts as EQL seeds** — resolvers receive engine/query contexts
   through the EQL input map, not as closed-over globals.  This makes
   every resolver testable in isolation with `create-context`.
2. **Self-describing graph** — `query-graph-summary-in` queries the graph
   for its own resolver list, so introspection resolvers appear in their
   own output (`graph-self-describes-test`).
3. **Derived properties live in engine** — `has-interface?`, `is-ai-complete?`
   etc. are computed by `psi.engine.core`; the resolver just surfaces them.

### λ EQL Attribute Namespace Convention for Cross-Component Queries

Use `psi.X/Y` namespaces for attributes that cross component boundaries:

| Prefix          | Domain                          |
|-----------------|---------------------------------|
| `:psi/`         | top-level system context inputs |
| `:psi.engine/`  | engine entity attributes        |
| `:psi.system/`  | system state attributes         |
| `:psi.graph/`   | query graph attributes          |

Seed inputs (`:psi/engine-ctx`, `:psi/query-ctx`) are opaque Clojure
records — Pathom treats them as plain values in the entity map.

### λ trigger stored as (str keyword) — contains colon

`engine/trigger-engine-event-in!` stores `(str event)` on each transition.
For a keyword `:configuration-complete` this yields `":configuration-complete"`
(with leading colon).  Tests must match the stringified form, not the bare name.

## 2026-02-25 - clj-http Cookie Policy

### λ clj-http :cookie-policy :none → CookieSpecs/IGNORE_COOKIES

clj-http's `get-cookie-policy` multimethod dispatches on the `:cookie-policy`
key.  The correct key to fully disable cookie processing is **`:none`**, not
`:ignore-cookies`:

```clojure
(defmethod get-cookie-policy :none [_] CookieSpecs/IGNORE_COOKIES)
```

An unknown key (e.g. `:ignore-cookies`) falls through to the `nil` default
which uses `CookieSpecs/DEFAULT` — cookies are still processed, warnings still appear.

Use `:none` when calling APIs (e.g. OpenAI) that return cookies (Cloudflare
`__cf_bm`) with non-standard `expires` date formats.  Apache HttpClient
emits a `WARNING: Invalid cookie header` via `ResponseProcessCookies` when
it cannot parse the date.  `:none` prevents that interceptor from running.

```clojure
(http/post url (merge request {:as :stream :cookie-policy :none}))
```

---

## 2026-02-25 - JVM Shutdown / CLI Entry Point

### λ clj-http Parks a Non-Daemon Thread — Call System/exit

`clj-http` (Apache HttpClient) starts a connection-eviction background thread
with `isDaemon() = false`.  Non-daemon threads block JVM shutdown.  After the
CLI prompt loop exits (e.g. `/quit`), the JVM hangs indefinitely waiting for
this thread to finish.

**Fix**: call `(System/exit 0)` at the end of `-main`:

```clojure
(defn -main [& args]
  (run-session ...)
  ;; clj-http parks a non-daemon connection-eviction thread.
  ;; Explicitly exit so the JVM does not hang after /quit.
  (System/exit 0))
```

This is the standard pattern for CLI tools using clj-http (or any library
that parks non-daemon threads).  It only runs after `run-session` returns
normally so it does not swallow exceptions during the session.

Other potential culprits investigated and cleared:
- `simple-env` statecharts — use manually-polled queue, **no** background threads
- `future` in streaming layer — uses ForkJoin pool, **all daemon** threads

### λ Provider is Encoded in the Model Key — No --provider Flag Needed

The model key already identifies the provider.  `--model <key>` is the one
obvious way to select both:

| CLI flag | Model | Provider |
|----------|-------|----------|
| `--model claude-3-5-haiku` | Claude 3.5 Haiku | Anthropic |
| `--model claude-3-5-sonnet` | Claude 3.5 Sonnet | Anthropic |
| `--model gpt-4o` | GPT-4o | OpenAI |
| `--model o1-preview` | GPT-o1 Preview | OpenAI |

`PSI_MODEL` env var is the alternative.  A separate `--provider` flag would
be redundant and violate the **One Way** principle.

---

## 2025-02-24 23:34 - Bootstrap Testing

### λ Testing Infrastructure Works

**Test Command**: `clojure -M:test` (not `-X:test`)
- `-X:test` fails (no :exec-fn defined) 
- `-M:test` succeeds (uses :main-opts with kaocha.runner)

**AI Component Status**: ✓ All tests passing
- psi.ai.core-test: 5 tests, 23 assertions, 0 failures
- Core functionality verified:
  - Stream options validation
  - Message handling 
  - Usage calculation
  - Model validation (Claude/OpenAI)
  - Conversation lifecycle

**Test Configuration**:
- Kaocha runner with documentation reporter
- Test paths: test/ + components/ai/test/
- Integration tests skipped (marked with :integration meta)
- Colorized output enabled

### λ System State Understanding

**Runtime**: JVM Clojure ready
- deps.edn: Polylith AI component + pathom3 + statecharts
- tests.edn: Kaocha configuration
- Latest: AI component integrated (commit 8663e14)

**Architecture Progress**:
- ✓ AI component implemented & tested
- ✓ Allium specs defined  
- ? Engine (statecharts integration)
- ? Query interface (pathom3 integration)
- ? Graph emergence from resolvers

**Next**: System integration beyond component tests

---

## 2026-02-25 - EQL Query Component

### λ psi/query Component Built and Clean

**Component**: `components/query/` — Pathom3 EQL query surface

Three namespaces, one responsibility each:
- `psi.query.registry` — additive resolver/mutation store (atoms, malli-validated)
- `psi.query.env`      — Pathom3 environment construction (`build-env`, `process`)
- `psi.query.core`     — public API: `register-resolver!`, `query`, `query-one`,
                          `rebuild-env!`, `graph-summary`, `defresolver`, `defmutation`

**Status**: 10 tests, 32 assertions, 0 failures. 0 kondo errors/warnings. 0 LSP diagnostics.

### λ clj-kondo Config Import

Run this after adding new deps or components — imports hook/type configs from jars:
```bash
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```
Run at **root** and in **each component dir** separately.

New configs gained this session: pathom3, promesa, guardrails, potemkin, prismatic/schema.

### λ Two Separate Lint Systems

**clj-kondo** (`.clj-kondo/config.edn`) and **clojure-lsp** (`.lsp/config.edn`) are distinct:

| Concern | Config file | Linter key |
|---------|-------------|------------|
| clj-kondo unused public var | `.clj-kondo/config.edn` | `:unused-public-var` |
| clojure-lsp unused public var | `.lsp/config.edn` | `:clojure-lsp/unused-public-var` |

**✗ Do not** put `:unused-public-var` or `:clojure-lsp/*` keys in `.clj-kondo/config.edn`
— clj-kondo will warn "Unexpected linter name".

**Authoritative check**: `clojure-lsp diagnostics --project-root .` — not the pi tool
(which caches stale results).

### λ Test Isolation Pattern

Polylith components use `defonce` atoms — state bleeds between tests in the same JVM.
`use-fixtures :each` resets between *test functions* but not between `testing` blocks.

**Pattern** — use a `with-clean-*` macro:
```clojure
(defmacro with-clean-registry [& body]
  `(do (registry/reset-registry!)
       (try ~@body (finally (registry/reset-registry!)))))
```
Wrap each isolated scenario in its own `with-clean-*` call.

### λ Inline defs in Tests

`pco/defresolver` / `pco/defmutation` inside a `deftest` body triggers
clj-kondo `inline-def` warning and confuses clojure-lsp symbol resolution.

**Fix**: define resolvers/mutations at **top-level** in the test namespace.
If the test needs a clean registry, re-register the top-level var inside
`with-clean-*` rather than redefining.

### λ Kaocha --focus Syntax

`--focus psi.query` does not match test namespaces (needs exact ns name).
Use: `--focus psi.query.core-test --focus psi.query.registry-test`

### λ Architecture Progress

- ✓ AI component implemented & tested
- ✓ Engine (statecharts) component implemented & tested
- ✓ Query (EQL/Pathom3) component implemented & tested
- ✓ AI integrated with engine + query — resolvers registered, core.async removed
- ? Graph emergence from resolvers (next: add domain resolvers)
- ? Introspection (engine queries engine via EQL)
- ? History / Knowledge resolvers (git + knowledge graph)

---

## 2026-02-25 - AI ↔ Engine/Query Integration

### λ Callback > Channel for blocking I/O

Provider HTTP streaming is purely blocking I/O.  `core.async/go` +
`async/chan` added scheduler complexity with no benefit.  Replacing with:

- **`consume-fn` callback** — provider calls it synchronously per event
- **`future` + `LinkedBlockingQueue`** — bridges background thread to a
  lazy seq when callers prefer pull-style consumption

Pattern:
```clojure
;; Push style (callback)
(stream-response provider conv model opts
  (fn [ev] (when (= :text-delta (:type ev)) (print (:delta ev)))))

;; Pull style (lazy seq)
(let [{:keys [events]} (stream-response-seq provider conv model opts)]
  (doseq [ev events] ...))
```

### λ AI Resolvers in EQL Graph

Register AI capabilities as Pathom resolvers so the whole system can query
them via a uniform EQL surface:

```clojure
(core/register-resolvers!)
(query/query {} [:ai/all-models])
(query/query {:ai.model/key :gpt-4o} [:ai.model/data])
(query/query {:ai/provider :anthropic} [:ai/provider-models])
```

### λ clj-kondo Hooks Must Be Imported Per Component

Each Polylith component has its own `.clj-kondo/` dir.  When a component
gains a new dependency whose macros need kondo hooks (e.g. pathom3's
`pco/defresolver`), the hooks must be imported **in that component's dir**:

```bash
cd components/<name>
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

The component `deps.edn` must already declare the dep for it to appear
on the classpath.  Symptom of missing import: "Unresolved symbol" for
every var/binding the macro generates.

### λ Stub Provider Pattern for Tests

Use a stub provider closure to drive streaming tests without HTTP:

```clojure
(defn stub-provider [text]
  {:name   :stub
   :stream (fn [_conv _model _opts consume-fn]
             (consume-fn {:type :start})
             (consume-fn {:type :text-delta :delta text})
             (consume-fn {:type :done :reason :stop ...}))})
```

Swap it into the registry for the test, restore afterward.

---

## 2026-02-25 - Nullable Pattern / Testing Without Mocks

### λ Nullable Pattern in Clojure — Isolated Context Factory

The Nullable pattern replaces global-atom resets and mock/spy setups with
isolated context factories.  Every component that owns mutable state gets a
`create-context` (or `create-registry`, `create-query-context`) factory that
returns a plain map of fresh atoms:

```clojure
(defn create-context []
  {:engines           (atom {})
   :system-state      (atom nil)
   :state-transitions (atom [])
   :sc-env            (atom nil)})
```

All mutable functions gain a `*-in` context-aware variant taking the context
as first arg.  The global (singleton) API becomes thin wrappers via a
`global-context` helper that returns the `defonce` atoms:

```clojure
(defn- global-context []
  {:engines engines :system-state system-state ...})

(defn create-engine [engine-id config]
  (create-engine-in (global-context) engine-id config))
```

Tests create their own context — no shared state, no cleanup fixtures:

```clojure
(deftest engine-lifecycle-test
  (let [ctx (engine/create-context)
        eng (engine/create-engine-in ctx "test" {})]
    (is (= :initializing (:engine-status eng)))))
```

### λ Isolated Query Context (QueryContext record)

`query/create-query-context` returns a `QueryContext` record with its own
registry + env atom.  Tests register resolvers into it and query against it:

```clojure
(let [ctx (query/create-query-context)]
  (query/register-resolver-in! ctx my-resolver)
  (query/rebuild-env-in! ctx)
  (query/query-in ctx {:user/id 1} [:user/name]))
```

This replaces the `with-clean-query` macro that reset global atoms.

### λ Isolation Tests Are Worth Adding

Adding an explicit test that two contexts are independent catches regressions
if the factory accidentally shares state:

```clojure
(deftest context-isolation-test
  (let [ctx-a (query/create-query-context)
        ctx-b (query/create-query-context)]
    (query/register-resolver-in! ctx-a greeting-resolver)
    (is (= 1 (:resolver-count (query/graph-summary-in ctx-a))))
    (is (= 0 (:resolver-count (query/graph-summary-in ctx-b))))))
```

### λ Nullable Pattern for External Process Infrastructure (git)

When infrastructure is an external process (not mutable state), the Nullable
pattern uses a **context record + embedded temp environment** rather than a
stub closure:

```clojure
;; GitContext — the infrastructure wrapper
(defrecord GitContext [repo-dir])

(defn create-context
  "Production: points at a real repo dir."
  ([] (create-context (System/getProperty "user.dir")))
  ([repo-dir] (->GitContext repo-dir)))

(defn create-null-context
  "Test: builds an isolated temp git repo with seeded commits.
   Real git, controlled data, no shared state, no mocking."
  ([] (create-null-context default-seed-commits))
  ([commits]
   (let [tmp (make-temp-dir)]
     (git-init! tmp)
     (doseq [{:keys [message files]} commits]
       (write-files! tmp files)
       (git-commit! tmp message))
     (->GitContext tmp))))
```

Key points:
- **Real git subprocess** — not a stub. Tests exercise the same code path as production.
- **Seeded data** — commits carry controlled messages with vocabulary symbols.
- **Isolated per test** — each `create-null-context` call gets a fresh temp dir.
- **mkdirs before spit** — files in subdirs need parent dirs created first.
- **No cleanup needed** — JVM temp dirs are cleaned on exit.

Two-context isolation test verifies independence:
```clojure
(deftest two-null-contexts-are-independent
  (let [ctx-a (git/create-null-context [{:message "only in A" :files {"a.txt" "a"}}])
        ctx-b (git/create-null-context [{:message "only in B" :files {"b.txt" "b"}}])]
    (is (not (some #(str/includes? (:git.commit/subject %) "B")
                   (git/log ctx-a {}))))))
```

---

## 2026-02-26 - charm.clj Alt-Screen Bug

### λ charm.clj v0.1.42 enter-alt-screen! Never Fires

`create-renderer` stores `:alt-screen` from opts into the renderer atom.
`enter-alt-screen!` checks `(when-not (:alt-screen @renderer))` — which
short-circuits because the flag is already `true`. Alt-screen is never
actually entered.

**Impact**: TUI runs inline in the main terminal buffer. JLine's `Display`
uses relative cursor tracking in non-fullscreen context. Any content height
change (streaming toggle, notifications, errors) desyncs cursor position.
Symptom: typed text renders after the footer instead of at the prompt.

**Root cause chain**:
1. `create-renderer` stores `:alt-screen true` in atom (from opts)
2. `start!` sees `:alt-screen true`, calls `enter-alt-screen!`
3. `enter-alt-screen!` checks `(when-not (:alt-screen @renderer))` → skip

**Fix**: `alter-var-root` patch (same pattern as keymap fix):
```clojure
(alter-var-root
 #'charm.render.core/enter-alt-screen!
 (constantly
  (fn [renderer]
    (let [terminal (:terminal @renderer)]
      (charm-term/enter-alt-screen terminal)
      (charm-term/clear-screen terminal)
      (charm-term/cursor-home terminal))
    (swap! renderer assoc :alt-screen true))))
```

**Lesson**: When a library stores "desired config" and "current state"
in the same key, idempotency guards can prevent initial setup from
running. Two charm.clj patches now — both `alter-var-root` at load time.

---

## 2026-02-25 - TUI: charm.clj Elm Architecture (Step 5)

### λ charm.clj Replaces Custom Terminal Layer

Custom `ProcessTerminal` with `stty -echo raw` + manual differential
rendering had cursor position desync on macOS. The cursor would move
incorrectly after PTY-level echo, making typed text invisible.

**Fix**: Replace the entire TUI layer with charm.clj's Elm Architecture.
charm.clj uses JLine3 (`jline-terminal-ffm`) which handles raw mode,
input parsing, and rendering correctly via JLine's `Display` differ.

Architecture:
```
init    → [state nil]        ; initial state
update  → (state msg) → [state cmd]  ; pure state transitions
view    → state → string     ; pure render
```

The agent runs in a `future`. Communication uses `LinkedBlockingQueue`:
```
submit → future puts {:kind :done/:error} on queue
poll-cmd → reads queue with 120ms timeout → returns message
poll timeout → advances spinner, issues new poll-cmd
```

No separate timer thread. Spinner is driven by poll ticks.

### λ JLine3 FFM Requires --enable-native-access

charm.clj uses `jline-terminal-ffm` which needs the JVM flag
`--enable-native-access=ALL-UNNAMED` on JDK 22+. Add to `:jvm-opts`
in the `:run` alias.

### λ charm.clj v0.1.42 JLine String vs char[] Bug

`charm.input.keymap/bind-from-capability!` calls `(String. ^chars seq)`
but JLine 3.30+ `KeyMap/key` returns `String`, not `char[]`.
ClassCastException at runtime.

**Fix**: `alter-var-root` the private fn at namespace load time:
```clojure
(alter-var-root
 #'charm.input.keymap/bind-from-capability!
 (constantly
  (fn [^KeyMap keymap ^Terminal terminal cap event]
    (when terminal
      (when-let [seq-val (KeyMap/key terminal cap)]
        (let [^String seq-str (if (string? seq-val)
                                seq-val
                                (String. ^chars seq-val))]
          (when (and (pos? (count seq-str))
                     (= (int (.charAt seq-str 0)) 27))
            (.bind keymap event (subs seq-str 1)))))))))
```

**Lesson**: Add a JLine integration smoke test that creates a real
terminal + keymap. Unit tests exercising pure init/update/view don't
touch JLine and miss this class of bug.

### λ Elm Architecture Patterns for Agent TUI

**Polling command pattern** — when background work runs in a future,
use a command that reads from a queue with a short timeout:
```clojure
(defn poll-cmd [queue]
  (charm/cmd
   (fn []
     (if-let [event (.poll queue 120 TimeUnit/MILLISECONDS)]
       (translate event)
       {:type :agent-poll}))))  ; timeout → keep polling
```

The update function returns a new poll-cmd on each poll/timeout,
creating a self-sustaining loop that ends when the agent is done.

**Avoiding clojure.core/run! collision** — charm.clj's `run` is a
common function name. Don't name your entry point `run!` to avoid
shadowing `clojure.core/run!`. Use `start!` instead.

### λ Always Require, Never Inline-Qualify in Tests

Using `clojure.string/includes?` or `charm.components.text-input/value`
inline (without a `:require`) compiles fine but triggers clj-kondo
"Unresolved namespace" warnings.  This bit us twice in the same session.

**Rule**: always add the namespace to `:require` with an alias:
```clojure
;; ✗ inline — triggers clj-kondo warning
(is (clojure.string/includes? out "hello"))
(is (= "hi" (charm.components.text-input/value (:input s))))

;; ✓ required + aliased
(:require [clojure.string :as str]
          [charm.components.text-input :as text-input])
(is (str/includes? out "hello"))
(is (= "hi" (text-input/value (:input s))))
```

### λ clojure-lsp Caches Stale Diagnostics via Pi Tool

After editing a file, the pi `clojure_lsp` tool may report warnings that
no longer exist in the source.  Verify with `grep` before chasing phantom
errors:
```bash
grep -n 'clojure\.string/' components/tui/test/psi/tui/app_test.clj
```
If grep finds nothing but clojure-lsp still warns, the cache is stale.

### λ clj-kondo Cache Goes Stale After Refactors

After adding new public vars to a namespace, clj-kondo's `.cache/` still
holds the old snapshot → LSP reports "Unresolved var" for the new fns even
though they compile fine.

**Fix**: re-lint the source directories to rebuild the cache:
```bash
clj-kondo --lint components/query/src components/ai/src ...
```
No flags needed — linting source updates the cache in place.

### λ Avoid Redundant `let` for clj-kondo

clj-kondo warns "Redundant let expression" when a `let` has a single binding
(even if it's map destructuring).  Merge the inner `let` into the outer one
to silence it:

```clojure
;; ✗ triggers warning
(let [ctx (create-context)]
  (let [{:keys [future session]} (stream-response-in ctx ...)]
    @future ...))

;; ✓ merge bindings
(let [ctx                       (create-context)
      {bg :future session :session} (stream-response-in ctx ...)]
  @bg ...)
```
