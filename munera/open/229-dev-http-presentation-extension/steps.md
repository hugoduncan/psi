# 229 — Steps

Checklist derived from `plan.md`. Tick items as completed; note sha/decisions
inline. Grouped by slice. Each slice ends green (focused Scry + clj-kondo) before
the next begins.

## Slice 0 — Scaffold + wiring

- [x] Create `extensions/dev-http/` dir tree (`src/extensions/`, `dev/`,
      `resources/dev_http/vendor/`, `test/extensions/`).
- [x] Write `extensions/dev-http/deps.edn`: `:paths ["src"]`, deps
      (`metosin/reitit-ring`, `http-kit`, `hiccup`, `integrant`, clojure,
      `psi/agent-session`), a scoped `:dev` alias adding the `dev` extra-path,
      and a `:test` alias with `extension-test-helpers` + kaocha.
- [x] Add `psi/dev-http {:local/root "dev-http"}` to `extensions/deps.edn`
      `:deps` and `"dev-http/test"` to its `:test` `:extra-paths`.
- [x] Add `extensions/dev-http/src` to the relevant `:extra-paths` blocks in root
      `deps.edn` (also `tests.edn` suite source/test paths; new deps added to root
      `:deps`; launcher catalog `psi-owned-extension-catalog` entry).
- [x] Add `psi/dev-http {}` to `.psi/extensions.edn` `:deps`.
- [x] Create entry-point `extensions/dev-http/src/extensions/dev_http.clj` with
      ns `extensions.dev-http`, a `(defonce ^:private state (atom {…}))`, and a
      stub `(defn init [api] …)` capturing the api map into the atom.
- [x] Smoke: resolve deps + REPL-load the entry-point ns with no errors
      (de-risk R1).
- [x] Add a minimal `dev_http_test` asserting `init` captures the api map
      (nullable extension API). Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: scaffold dev-http extension + wiring` (39086baec).

## Slice 1 — Platform end-to-end

- [x] `dev_http/config.clj`: build config (host `127.0.0.1`, port `0`,
      generate per-launch token, api map). `route-url` lives in the entry-point
      ns (needs the live server's resolved port/token), not config.
- [x] `dev_http/registry.clj`: session-route registry atom + `register-entry!`
      (last-write-wins by route-id), `get-entry`, `entries`.
- [x] `dev_http/router.clj`: reitit-ring router builder combining persisted
      routes + the stable `/s/:route-id` dispatch subtree (looks up registry at
      request time); `create-default-handler` 404.
- [x] `dev_http/middleware.clj`: token middleware (read token from query
      string/header; mismatch → 403); applied at each dynamic subtree root.
- [x] `dev_http/system.clj`: integrant `init-key`/`halt-key!` for
      `:dev-http/config`, `:dev-http/registry`, `:dev-http/router`,
      `:dev-http/server` (http-kit start/stop); `:server` exposes resolved
      port/URL/token.
- [x] Persisted-route loader (`dev_http/routes.clj`): scan extension-local `dev/`
      for `extensions.dev-http.dev.*` namespaces; require + collect their `routes`
      var (reitit route-data) at router-build time.
- [x] One persisted demo route under
      `extensions/dev-http/dev/extensions/dev_http/dev/demo.clj` (hiccup HTML page).
- [x] `init`: register `/dev-http` command (handler parses `start|status|stop`);
      `start` = idempotent integrant init (halt prior `:system` first); `status`
      = running?/URL/token; `stop` = `ig/halt!`.
- [x] Declare minimal `:allowed-events`: none required this slice — Slice 1
      dispatches no core events (uses api-level `:register-command`/`:log` only).
      Revisit at Slice 3 (synthetic-prompt path, R8).
- [x] Tests: registry last-write-wins; router dispatch for persisted + `/s/`
      routes (pure ring handler, no socket); token middleware 403 on each subtree;
      integration boot on ephemeral port → request demo route 200 (AC-2),
      `start`/`status`/`stop` lifecycle with no orphaned server on restart
      (AC-1), `127.0.0.1` bind + token required (AC-8).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: platform end-to-end (lifecycle + server + router + registry + demo route)` (dc68345fc).

## Slice 2 — `dev-present` tool + renderer set

- [x] `dev_http/renderers.clj`: dispatch on `:renderer` →
      `:markdown` (commonmark) · `:table` (hiccup) · `:hiccup` (raw) ·
      `:file` (serve disk artifact, content-type by extension) ·
      `:vega` (HTML embedding vendored Vega-Lite JS + spec) ·
      `:mermaid` (HTML embedding vendored Mermaid JS + source).
      Dispatch via a plain `:renderer → fn` map (no multimethod). `data`
      accessors read keyword *or* string keys (REPL vs JSON-tool input).
- [x] Vendor Vega-Lite + Mermaid client JS into
      `resources/dev_http/vendor/`; add an **ungated** `/assets/:asset` route
      serving them locally; pin + document versions (vega 5.30.0,
      vega-lite 5.21.0, vega-embed 6.26.0, mermaid 10.9.1). Added
      `extensions/dev-http/resources` to all classpath blocks.
- [x] `dev_http/tool.clj`: `dev-present` tool (`:register-tool`) — content map
      `{:renderer … :data …}` → register session route → return URL. Takes a
      `register-content!` seam fn; nil return ⇒ "server not running" error.
- [x] Public `register-route!` REPL/dev fn registering an arbitrary ring handler
      into the registry (already present from Slice 1; added
      `register-content-route!` for declarative content).
- [x] Wire `dev-present` + `register-route!` to last-write-wins replace on
      route-id collision (registry `register-entry!` semantics).
- [x] Tests: each renderer produces expected response for representative input
      (AC-5); Vega/Mermaid assets served locally with no network (AC-5,
      byte-equality + over-the-wire ungated fetch); `dev-present` returns a URL
      that renders the content (AC-3); `register-route!` reachable + re-register
      replaces prior entry (AC-4).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: dev-present tool + renderer set (vendored vega/mermaid)`.

## Slice 3 — Choice interaction loop

- [x] Core mutation: add `psi.extension/submit-synthetic-prompt` in
      `components/agent-session` mutations (thin wrapper dispatching
      `:session/submit-synthetic-user-prompt` with `{:session-id … :user-msg …}`,
      returning `{:psi.extension/prompt-submitted? …}`); add to `all-mutations`.
      Wraps plain text in a canonical user message record; dispatches with
      `:origin :mutations`. Kept minimal — no new semantics (R4).
- [x] Core mutation test (agent-session): wrapper injects a mid-conversation
      **user** message into the target session and reports submitted? (drives the
      downstream turn deterministically via the `:execute-prepared-request-fn`
      ctx seam — no network).
- [x] `dev_http/choices.clj`: `:choices` renderer emits a token-gated form
      POSTing the selection back to `/s/:route-id` (method-dispatched handler).
- [x] Submit handler: write via `:mutate-session` calling
      `psi.extension/submit-synthetic-prompt`; origin session-id captured at
      registration time (from the `dev-present` tool `opts`/`register-content!`,
      invoking-session-only).
- [x] Single-shot guard: store `{:answered? true :answer …}` in the registry
      entry on first successful submit (atomic `claim-answer!`); second submit →
      "already answered" page, no second injection.
- [x] Tests: submitting a `:choices` form injects exactly one user message into
      the originating session (AC-6); second submission rejected, at most one
      injection (AC-7); handler writes only via `:mutate-session` (AC-9). Unit
      handler test + real-server integration loop (nullable api records the
      mutation).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: choice interaction loop (submit-synthetic mutation + single-shot)`.

  Note (R8 resolved): no `:allowed-events` declaration needed — the submit path
  is a Pathom mutation (`:mutate-session`), and its inner core dispatch uses
  `:origin :mutations`; the permission interceptor only gates `:origin :extension`
  dispatches.

## Slice 4 — SSE live-updates

- [x] `dev_http/sse.clj`: `text/event-stream` endpoint within the token-gated
      subtree using http-kit `as-channel`/`send!`/`close`. `make-handler`
      `(emit-fn send! close!)` opens the stream, sends an initial `open` event
      (sets headers), then hands `send!`/`close!` to the feed.
- [x] One demonstrated live feed: `/sse/registry` (token-gated) emits a snapshot
      of the current session-route count then closes; `register-sse-route!` REPL
      fn registers arbitrary live feeds as session routes.
- [x] Tests: SSE event formatting; `/sse/registry` token-gated (403 without
      token, unit); integration — connected client over the real server receives
      `data: open` + `data: routes N` reflecting registry state (AC-8 token
      required).
- [x] Focused Scry green; clj-kondo clean.
- [x] Commit: `⚒ 229: SSE live-updates`.

## Close-out (cross-cutting)

- [x] Verify no HTTP handler reads/writes core state except via
      `:query`/`:mutate`; integrant + live handle stay inside the extension
      (AC-9). HTTP handlers touch state only via `(:mutate-session api)` (choices
      submit); renderers are pure; the integrant system + registry live in the
      extension atom only.
- [x] Docs: add `doc/dev-http.md` (lifecycle, renderers, choices, SSE, token,
      dev-only posture) and link from README extensions list.
- [x] Changelog: `[Unreleased]` Added entry for the dev-http extension.
- [x] Update `mementum/state.md` capabilities section to mention dev-http.
- [x] Full Scry suite green; clj-kondo clean across extension + touched core.
- [x] Commit: `⚒ 229: docs + changelog + coherence`.

## Implementation review follow-ups (round 1)

- [x] Remove the dead no-op subscription in `init`:
      `((:on api) "session_switch" (fn [_ev] nil))`
      (`extensions/dev-http/src/extensions/dev_http.clj`). It was added in the
      Slice 1 commit, is documented nowhere (design/plan/steps/implementation),
      and registers a real session-event subscription that does nothing —
      incidental complexity. Delete it, or, if a future hook is genuinely
      intended, replace the no-op with the actual behaviour and document it.
      Deleted (no future hook intended).
- [x] Make the not-running precondition consistent across the sibling
      registration fns in `dev_http.clj`. `register-route!` threw `ex-info`
      while `register-content-route!` / `register-sse-route!` returned `nil` for
      the same "server not running" condition. Unified on the nil idiom:
      `register-route!` now returns nil when the server is not running
      (`register-sse-route!` delegates to it, so it is nil too); this matches
      the `register-content!` seam contract the `dev-present` tool relies on.
- [x] De-duplicate shared helpers:
      `kget` is defined verbatim in both `renderers.clj` and `choices.clj`;
      the urlencoded key/value regex parse `([^&=]+)=([^&]*)` is duplicated
      between `middleware/query-token` and `choices/form-choice`. Extract each
      into one shared location rather than copies. `kget` → new
      `extensions.dev-http.util`; urlencoded parse → `mw/urlencoded-param`
      (HTTP concern, in middleware) used by both `query-token` and
      `form-choice`.
- [x] Resolve the dead `source` param in the core `submit-synthetic-prompt`
      mutation (`components/agent-session/.../mutations/prompts.clj`): `source`
      is destructured and `(or source :extension)` is used, but `source` is not
      in `::pco/params` and no caller supplies it, so the non-`:extension`
      branch is unreachable. Either drop the param (always `:extension`) or
      declare and wire it through a caller. Dropped the param; `:source` is
      always `:extension`.

## Implementation review follow-ups (round 2)

- [x] `claim-answer!` (`extensions/dev-http/src/extensions/dev_http/choices.clj`)
      performs a side effect inside the `swap!` update fn: it `reset!`s an
      external `won` atom from within the function passed to `swap!`. This is the
      documented swap-side-effect anti-pattern (`swap!` may retry its fn). It
      happens to converge here because every invocation re-`reset!`s `won`, but
      it violates the "update fn is pure" idiom. Rewrite using `swap-vals!` and
      derive the win from the returned old/new state, e.g.
      `(let [[old _] (swap-vals! reg (fn [m] …))] (not (:answered? (get old route-id))))`,
      removing the inner `won` atom.
      Done: rewrote `claim-answer!` with `swap-vals!`; the update fn is now pure
      (no inner `won` atom) and the win derives from `(not (:answered? (get old route-id)))`.
- [x] Duplicate session-route URL path shape `"/s/" <route-id> "?token=" <token>`
      is hand-built in two places — `route-url` (`dev_http.clj`, with base-url)
      and `render-form`'s form `action` (`choices.clj`, relative). The reitit
      template `/s/:route-id` (`router.clj`) is the third encoding of the same
      dispatch path. A path change must be made in all three. Extract the
      relative session-route path (`"/s/" route-id`) into one shared helper used
      by both URL builders so the dispatch path has a single source of truth.
      Done: added `util/session-route-prefix` (`"/s"`) + `util/session-route-path`
      (`<prefix>/<route-id>?token=<token>`). `route-url` and choices `render-form`
      action both use `session-route-path`; `router` derives its reitit template
      from `session-route-prefix`. All three encodings now flow from `util`.
- [x] `handle-command`'s `"start"` branch (`dev_http.clj`) hand-builds a
      `"dev-http started\n  url:   …\n  token: …"` string that duplicates the
      url/token formatting already produced by `status-text`. Reuse a single
      formatting helper (or have `start` log `status-text` after starting) so the
      running-status presentation has one source.
      Done: extracted `url-token-lines` (private); both `status-text` and the
      `start` command branch render the indented url/token block through it.

## Implementation review follow-ups (round 3)

- [x] Choice-submit result/ordering robustness gap
      (`extensions/dev-http/src/extensions/dev_http/choices.clj`,
      `make-handler` POST branch). The handler commits the single-shot
      answered flag via `claim-answer!` **before** calling
      `(:mutate-session api … 'psi.extension/submit-synthetic-prompt …)`, then
      **ignores** the mutation's `:psi.extension/prompt-submitted?` return and
      unconditionally renders the "Recorded your choice" success page.
      Consequence: if the mutation throws (or ever returns `submitted? false`),
      the route is already permanently marked answered (claim won) yet **zero**
      user messages were injected and the route can never be retried; in the
      non-throwing-false case the page would falsely report success. AC-6
      requires the submission to actually inject a user message. Mitigation:
      (a) inspect the mutation result and render a failure page when
      `prompt-submitted?` is falsey, and/or (b) reconsider claim-then-inject
      ordering so a failed injection does not consume the single-shot, while
      still preserving AC-7's at-most-once guarantee. Low-severity given the
      dev-only/localhost/single-user posture and that the current handler
      contract always returns `submitted? true`, but the unconditional success
      page plus claim-before-effect ordering is a latent robustness gap. If the
      claim-first ordering is retained deliberately (favouring AC-7), document
      that trade-off and the accepted failure mode at the call site.
      Done: the POST branch now inspects the mutation result via `inject-choice!`
      (try-wrapped; throw or falsey `:psi.extension/prompt-submitted?` ⇒ false)
      and, on failure, calls `release-claim!` to revert the single-shot flag and
      renders a `failed-page` ("try again") instead of a false success. A failed
      injection therefore injects zero user messages **and** does not consume the
      single-shot (preserving AC-6: a success page implies a real injection; and
      AC-7: only a successful injection latches the answered flag, so at most one
      user message is ever injected). The nullable extension api now models the
      real mutation contract (`submit-synthetic-prompt` →
      `{:psi.extension/prompt-submitted? true}`) so the result-aware handler is
      exercised over the real server. New unit tests cover the falsey-result
      (claim released → retry succeeds) and throwing-mutation (claim released)
      paths.

## Implementation review follow-ups (round 4)

- [x] De-duplicate the HTTP response-map construction (same class as the
      round-1 `kget`/urlencoded and round-2 path/format dedups, missed earlier).
      `choices/text-response`
      (`extensions/dev-http/src/extensions/dev_http/choices.clj`) is a verbatim
      duplicate of the private `renderers/html-response`
      (`extensions/dev-http/src/extensions/dev_http/renderers.clj`) — identical
      `{:status 200 :headers {"content-type" "text/html; charset=utf-8"} :body}`
      shape — and is **misnamed** (`text-response` returns `text/html`, not
      text). Separately, the plain-text error-response shape
      `{:status N :headers {"content-type" "text/plain; charset=utf-8"} :body …}`
      is hand-built ~6× across `choices` (400), `router` (404), `middleware`
      (403), and `renderers` (400 + two 404s) with no shared builder. Extract a
      single shared response location (e.g. public `renderers/html-response`
      reused by `choices`, plus a `text-response`/`error-response` builder used
      by the plain-text 4xx/404 sites), and remove the misnamed
      `choices/text-response`. Keep it minimal — small builder fns, not a
      framework.
      Done: extracted two small builders into `extensions.dev-http.util` —
      `html-response` (200 `text/html`) and `text-response` (`status` + `body`,
      `text/plain`). The misnamed private `choices/text-response` and the private
      `renderers/html-response` are deleted; their HTML-page call sites use
      `util/html-response`, and all the hand-built plain-text 4xx/404 maps in
      `choices` (400), `router` (404), `middleware` (403), and `renderers`
      (400 + two 404s) now call `util/text-response`. Each response shape now has
      one source; the misname is gone. (`sse`'s event-stream response is a
      distinct streaming-header shape, left as-is.)

- [x] Reconcile the demonstrated `/sse/registry` feed with the design's
      platform-thin / content-churny split
      (`extensions/dev-http/src/extensions/dev_http/router.clj`,
      `build-handler`). The router builder is otherwise pure platform mechanism
      (the `/s/:route-id` dispatch subtree + the `/assets` subtree); the
      concrete `/sse/registry` *content* feed is the only specific route baked
      into the generic builder — unlike the `/demo` content route, which lives
      as persisted content under `dev/`. The mechanism reason is real (persisted
      `dev/` routes are static route-data and cannot reach the live registry
      atom the feed needs). Low-severity. Either (a) accept and document at the
      design/plan level (not just implementation.md) that one demonstrated feed
      is platform-wired by necessity, or (b) provide a content-side registration
      path (e.g. register `/sse/registry` as a session route at `start!` time
      like `register-sse-route!` does) so no specific content route is hardcoded
      into the generic router builder.
      Done (option b): removed the hardcoded `/sse/registry` route (and the `sse`
      require) from `router/build-handler`, which is now pure platform mechanism
      (`/s/:route-id` dispatch subtree + ungated `/assets` subtree only). The
      demonstrated feed is now registered as an ordinary session route at
      `start!` time via a private `register-demo-feeds!` (`register-route!
      "registry" (sse/registry-feed-handler reg)`), reachable at `/s/registry`
      and token-gated by the `/s/` subtree like any session route. Updated the
      unit token-gating test (register the feed as a session route, assert 403 at
      `/s/registry`) and the integration feed test (`/s/registry?token=…`; the
      snapshot now reports `routes 2` because the feed is itself a session route
      and counts alongside the added `live-1`). Docs (`doc/dev-http.md`),
      CHANGELOG, and `plan.md` Slice 4 updated to `/s/registry` and the
      platform/content split note.

## Implementation review follow-ups (round 5)

- [x] Add test coverage for the `dev-present` tool threading `:session-id` from
      its `:execute` `opts` into the registered content map
      (`extensions/dev-http/src/extensions/dev_http/tool.clj` line ~66:
      `:session-id (:session-id opts)`). This wiring is the mechanism that makes
      a model-registered `:choices` route target the **invoking session only**
      (AC-6 "the originating session"; design §Interaction "Routes target the
      invoking session only"). It is currently untested: `dev-present-tool-test`
      passes `{}` as `opts` and asserts `[:content :renderer]`/`[:content :data]`/
      `:route-id` but never `[:content :session-id]`, and the choices integration
      test (`choices-interaction-loop-test`) calls `register-content-route!`
      directly with an explicit `:session-id`, bypassing the tool. So no test
      proves the model-callable surface routes a choice back to the originating
      session. Extend `dev-present-tool-test` to execute with
      `opts {:session-id "sess-x"}` and assert the captured content carries
      `:session-id "sess-x"` (ideally with `renderer "choices"`), and that an
      absent opts session-id threads `nil` rather than fabricating one.
      Done: added two `testing` blocks to `dev-present-tool-test`. The first
      executes with `opts {:session-id "sess-x"}` and `renderer "choices"`,
      asserting the captured content carries `[:content :renderer] :choices`
      and `[:content :session-id] "sess-x"` — proving the model-callable surface
      threads the invoking session into the content map. The second executes
      with empty `opts {}` and asserts the content map *contains* the
      `:session-id` key but threads `nil` (no fabricated session).

## Implementation review follow-ups (round 6)

- [x] Add test coverage for the `:choices` map-option (label≠value) code path
      (`extensions/dev-http/src/extensions/dev_http/choices.clj`
      `normalize-option`). The map branch
      (`(kget o :value "value" :label "label")` / `(kget o :label "label" …)`)
      is a real feature: a choice option given as `{:label … :value …}` renders
      the **label** as the button text but submits the **value**, and the
      submitted value (not the label) is the user message injected into the
      origin session. Every choices test (`choices-handler-test`,
      `choices-failed-injection-releases-claim-test`, integration
      `choices-interaction-loop-test`) uses only scalar options (`["A" "B"]`)
      where label==value, so the distinction is unverified — a regression (label
      and value swapped, or the label submitted instead of the value) would pass
      all current tests. Add a `render-form`/handler test with options
      `[{:label "Yes please" :value "y"} {:label "No" :value "n"}]` asserting the
      button displays the label, the form posts `choice=<value>`, and submitting
      injects the **value** (`"y"`) as the user message. Also cover the
      string-keyed (JSON-tool) variant `{"label" … "value" …}`. Focused Scry
      (kaocha `--focus`) green: 21 assertions; clj-kondo clean.
      Done: added `choices-map-option-test` (`extensions/dev-http/test/extensions/dev_http_test.clj`).
      It runs both the keyword-keyed (`{:label "Yes please" :value "y"}`) and
      string-keyed (`{"label" … "value" …}`) variants via `doseq`, asserting for
      each that (a) the GET form renders the **label** as the button text
      (`>Yes please</button>`) and the **value** as the submitted value
      (`value="y"`), and never `value="Yes please"`; and (b) a POST of
      `choice=y` injects the **value** `"y"` (not the label) as the single
      user message into the origin session. Focused kaocha `--focus` green
      (16 assertions in the new deftest; 42 across all three choices unit
      tests); clj-kondo clean.

## Test review follow-ups (round 1)

- [x] Back the unit `:choices` handler tests with the sanctioned nullable
      extension API instead of bespoke spy maps. `choices-handler-test`,
      `choices-failed-injection-releases-claim-test`, and
      `choices-map-option-test`
      (`extensions/dev-http/test/extensions/dev_http_test.clj`) inject
      hand-rolled `capturing-api`/`result-api` maps whose `:mutate-session`
      records calls into an atom and returns canned results — the spy/stub
      pattern the project's own `psi.extension-test-helpers.nullable-api`
      docstring explicitly says to avoid, and contrary to task-test-review's
      `∀d ∈ infra_deps. nullable ∧ ¬mock ∧ ¬stub`. The happy-path tests can use
      `nullable/create-nullable-extension-api` (already used by the integration
      tests; its default `submit-synthetic-prompt` handler returns
      `{:psi.extension/prompt-submitted? true}` and records into `:mutations`)
      and assert on `(:mutations @state)`. The two failure-path cases that need
      a failing mutate seam should inject it through the sanctioned `:mutate-fn`
      override rather than a separate bespoke map. Relatedly, prefer asserting
      the observable rendered page (output) as the primary signal over the
      recorded mutation call (interaction) — the deeper state-based proof of the
      injection already lives in the core
      `submit-synthetic-prompt-injects-user-message-test`.
      Done: deleted the bespoke `capturing-api`/`result-api` maps. All three
      tests now build the choices handler with the nullable
      `create-nullable-extension-api` api. The two happy-path tests
      (`choices-handler-test`, `choices-map-option-test`) use the default
      nullable (its `submit-synthetic-prompt` handler returns
      `{:psi.extension/prompt-submitted? true}` and records into `:mutations`)
      and assert the rendered page first plus a new
      `submit-prompt-mutations` helper over `(:mutations @state)`. The two
      failure cases in `choices-failed-injection-releases-claim-test` now supply
      the failing/throwing seam via the sanctioned `:mutate-fn` override and
      assert the rendered failure page + the registry `:answered?` claim state
      (state/output signals, not the recorded-call interaction).
- [x] Add end-to-end coverage for the model-callable `dev-present` tool path
      (AC-3 "the agent can call `dev-present` … and receives back a URL that
      renders the content"). The tool's render path is only exercised either as
      a unit with a stubbed `register-content!` seam (`dev-present-tool-test`)
      or by calling `register-content-route!` directly, bypassing the tool, in
      the integration `lifecycle-and-serving-test` AC-3 block. No test drives the
      actual registered tool (`init` wires `tool/dev-present-tool` →
      `register-content-route!`, `dev_http.clj:176`) through to a URL that
      renders over the real server, so a wiring regression (tool bound to the
      wrong register fn) or a tool-shaped-input → real-render break would pass.
      Execute the tool resolved from the nullable state `:tools` against a
      running server and assert the returned URL renders the content.
      Done: added `^:integration dev-present-tool-renders-over-server-test`. It
      `init`s + `start!`s the real server, resolves the tool from
      `(get-in @state [:tools "dev-present"])`, executes it with
      `{"renderer" "markdown" "data" "# Tool Live" …}` + `opts {:session-id …}`,
      parses the URL out of the tool result (`Open: (\S+)`), fetches it over the
      ephemeral-port http-kit server, and asserts `<h1>Tool Live</h1>` renders —
      proving the full `init`→`register-tool`→`register-content-route!`→render
      wiring, not the stubbed seam.
- [x] (Low) Broaden `:file` renderer content-type coverage. `file-renderer-test`
      verifies only `.svg` → `image/svg+xml` plus the missing-file 404; the rest
      of `extension->content-type` (html/png/jpg/pdf/json/css/js/txt) and the
      `application/octet-stream` default are unverified, so an edited or swapped
      mapping would pass — the same regression-guard class as the round-6
      map-option gap. Add a small data-driven assertion over a representative
      subset incl. the octet-stream fallback for an unknown extension.
      Done: added a data-driven `testing` block to `file-renderer-test` asserting
      `renderers/content-type-for` over html/png/jpg/pdf/json/css/js/txt plus the
      `application/octet-stream` fallback for an unknown `.xyz` extension.
