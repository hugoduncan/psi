# 229 — Implementation notes

Append-only local memory of in-flight decisions and discoveries.

## Slice 0 — Scaffold + wiring (2026-06-15)

- Extension entry point: ns `extensions.dev-http`, `defonce ^:private state`
  atom `{:api nil :system nil}`, `init` captures the api map and returns `nil`
  (matches work-on/mcp-tasks-run convention).
- **Discovery (beyond plan): launcher catalog registration is required.**
  Extensions are mapped name→init via `psi-owned-extension-catalog` in
  `bases/main/src/psi/launcher/extensions.clj`. Added a `psi/dev-http` entry
  (`:psi/init 'extensions.dev-http/init`, dev/installed `:local/root
  "extensions/dev-http"`, jar `:mvn/version :psi/release-version`). The plan's
  wiring list omitted this; without it the extension would never be discovered.
- **Discovery: new mvn deps must live in root `deps.edn` `:deps`, not only the
  extension-local `deps.edn`.** The monorepo classpath is governed by root
  `deps.edn` (extension src dirs are aggregated as extra-paths; their
  extension-local deps.edn is not merged). Added `metosin/reitit-ring 0.7.2`,
  `http-kit/http-kit 2.8.0`, `hiccup/hiccup 2.0.0-RC3`, `integrant/integrant
  0.13.1` to root `:deps`. The extension-local `deps.edn` keeps the same deps so
  the extension is buildable standalone.
- **Discovery: extension tests run only through the kaocha `:extensions` suite**
  (`tests.edn`), which supplies extension `:source-paths`. The scry core runner
  via the `:test-paths` alias does NOT carry extension src on its classpath, so
  `clojure -M:test-paths -m scry.cli --namespace …` cannot load extension
  namespaces. Focused run that works:
  `clojure -M:test extensions --focus extensions.dev-http-test`.
  Added dev-http `:source-paths`/`:test-paths` to the `:unit`/`:extensions`/
  `:integration` suites in `tests.edn`, and `extensions/dev-http/src` to the
  root `deps.edn` runtime/test extra-path blocks.
- Dep versions pinned: reitit-ring 0.7.2, http-kit 2.8.0, hiccup 2.0.0-RC3,
  integrant 0.13.1. (`malli`, `commonmark` already on classpath per design.)
- Slice 0 verification: deps resolve (`clj -P`), entry-point ns loads, focused
  test green (1 test / 2 assertions), clj-kondo clean, `:psi` and `:test`
  aliases resolve.

## Slice 1 — Platform end-to-end (2026-06-16)

- Namespaces added: `config`, `registry`, `middleware`, `routes` (persisted
  loader), `router`, `system` (integrant), plus the `dev/.../demo.clj` route.
  Entry-point `dev_http.clj` now holds `start!`/`stop!`/`status-text`/
  `route-url`/`register-route!` + the `/dev-http start|status|stop` command.
- **Deviation: `route-url` lives in the entry-point ns, not `config`/`registry`.**
  Plan listed `route-url` under registry. A URL needs the *live server's*
  resolved ephemeral port + token (only known after integrant init), which live
  in the entry-point's `@state` system map — so `route-url` belongs there.
- **Token gating: parse from `:query-string` manually** (no ring params
  middleware dep). `request-token` reads `token=` from the query string or the
  `x-dev-http-token` header. Every dynamic subtree (persisted + `/s/`) sits under
  one reitit `["" {:middleware [[wrap-token token]]} …]` node.
- **http-kit 2.8 API:** `(run-server handler {:ip … :port 0
  :legacy-return-value? false})` returns a server object; `server-port` gives the
  resolved ephemeral port, `server-stop!` halts it (used in `ig/halt-key!`).
- **Persisted-route loader needs the `dev/` path on the test classpath.** The
  kaocha `:extensions`/`:integration` suites and root `:test` alias previously
  carried only `extensions/dev-http/src`. Added `extensions/dev-http/dev` to
  those (tests.edn `:extensions`+`:integration` source-paths; root deps.edn
  `:test` extra-paths) so `load-persisted-routes` can scan + serve the demo route
  in tests (AC-2). The scoped `:dev` extra-path in the extension-local deps.edn
  still governs runtime/standalone; the project-global root `:dev` is untouched.
- **`:allowed-events`: none this slice.** Slice 1 dispatches no core events
  (only api-level `:register-command`/`:log`). R8 (allowed-events for the
  synthetic-prompt path) is deferred to Slice 3 where the core mutation lands.
- **Loader robustness:** `load-persisted-routes` returns `[]` when the `dev/`
  resource is absent or non-file (e.g. jar) — never reaches a published jar.
- Verification: `clojure -M:test extensions --focus extensions.dev-http-test`
  (6 tests / 19 assertions, non-integration) and
  `clojure -M:test integration --focus extensions.dev-http-test`
  (1 test / 16 assertions, real ephemeral-port boot) both green; clj-kondo clean
  across src/dev/test.

## Slice 2 — dev-present tool + renderer set (2026-06-16)

- Namespaces added: `renderers` (pure content-map → ring response) and `tool`
  (`dev-present` factory). Entry-point gained `register-content-route!` and
  registers the tool in `init` via `(:register-tool api)`.
- **Renderer dispatch is a plain `:renderer → fn` map** (`renderers/render`),
  not a multimethod (coding standard: avoid multimethods / global mutable
  dispatch). `renderer-keys` is the supported set; unknown ⇒ 400.
- **`data` accessors read keyword *or* string keys** via a small `kget`. The
  same renderer serves idiomatic REPL data (keyword keys) and JSON-tool data
  (string keys). `:hiccup` additionally coerces JSON string tags → keywords so
  a decoded `["div" …]` renders as elements.
- **Markdown via `org.commonmark.renderer.html.HtmlRenderer`** (already on the
  classpath through the root `commonmark` dep) — distinct from the TUI's AST
  walker; HTML output, not ANSI.
- **Vega/Mermaid client JS vendored** under `resources/dev_http/vendor/`
  (vega 5.30.0, vega-lite 5.21.0, vega-embed 6.26.0, mermaid 10.9.1; ~4 MB
  total). Pages embed `<script src="/assets/…">` + the spec/source. JSON spec
  serialized with cheshire (transitively present).
- **Deviation (beyond plan): assets are served from an UNGATED `/assets/:asset`
  subtree.** A browser `<script src>` request carries no token, so token-gating
  the vendored public JS would break every Vega/Mermaid page. The assets are
  third-party library bytes with no session data, so serving them ungated is
  safe; **every *dynamic* subtree stays token-gated** (AC-8 unaffected). Path
  traversal guarded (reject `..`). `asset-handler` lives in `renderers` and is
  wired as a second top-level route alongside the gated root in `router`.
- **Deviation (beyond plan): `extensions/dev-http/resources` added to the
  classpath** in every block that already carried `…/src` (root `deps.edn`,
  `tests.edn` `:unit`/`:extensions`/`:integration`, extension-local `deps.edn`
  `:paths`) so `io/resource "dev_http/vendor/…"` resolves at runtime and in
  tests.
- **`:format-request`** is required by the tool registry; used
  `call-summary/text-key-format-request "dev-present" "renderer"` (call-summary
  is transitive via `psi/agent-session` → `psi/tool-runtime`).
- Verification: `clojure -M:test extensions --focus extensions.dev-http-test`
  (11 tests / 60 assertions) and `… integration …` (1 test / 20 assertions,
  real boot incl. AC-3 content route + AC-5 ungated wire fetch) both green;
  clj-kondo clean across src/test.

## Slice 3 — Choice interaction loop (2026-06-16)

- **Core mutation (the one sanctioned core touch):**
  `psi.extension/submit-synthetic-prompt` added to
  `components/agent-session/.../mutations/prompts.clj` + `all-mutations`. It
  wraps plain `user-msg` text in a canonical user message record
  (`{:role "user" :content [{:type :text :text …}] :timestamp (Instant/now)
  :source :extension}`) and dispatches `:session/submit-synthetic-user-prompt`
  with `{:origin :mutations}`, returning `{:psi.extension/prompt-submitted? …}`.
  Distinct from `send-prompt` (which uses the `deliver-extension-prompt!` path).
- **R8 resolved — no `:allowed-events` needed.** The permission interceptor
  (`dispatch.clj`) gates only `:origin :extension` dispatches. The extension
  reaches the mutation through the api `:mutate-session` (a Pathom mutation, not
  an extension-origin event), and the mutation's inner dispatch carries
  `:origin :mutations`. So the synthetic-prompt path is not permission-gated and
  the extension needs no event-permission declaration.
- **Core mutation test** drives the *downstream* AI turn through the
  `:execute-prepared-request-fn` ctx seam (testing-without-mocks: inject a
  nullable executor on ctx, not a `with-redefs` of the boundary var) returning a
  canonical stub assistant message — deterministic, no network. Asserts exactly
  one injected `user`/`:extension` message with the submitted text + canonical
  lifecycle events in the event log.
- **`dev_http/choices.clj`:** one method-dispatched ring handler per choices
  route. GET renders a token-gated `<form method=post action="/s/<id>?token=…">`
  (token read per-request via `mw/request-token`); POST parses `choice=` from the
  urlencoded body, runs the single-shot `claim-answer!`, and on first win calls
  `((:mutate-session api) session-id 'psi.extension/submit-synthetic-prompt
  {:user-msg answer})`.
- **Single-shot guard (LOCKED design choice):** the answered flag lives in the
  session-route **registry entry** (`{:answered? true :answer …}`), set
  atomically inside one `swap!` on the registry atom. Second submit → "already
  answered" page, no second injection. (Localhost single-user; R6 accepted.)
- **Origin session-id capture:** the `dev-present` tool reads `(:session-id opts)`
  from its `:execute` opts (confirmed `execute-tool-runtime-in!` threads
  `:session-id` into tool opts) and threads it into the content map; the
  entry-point `content-handler` builds the choices handler closing over it
  (invoking-session-only).
- **`body-string` accepts String *or* InputStream** — `slurp` on a raw String
  treats it as a filename, which broke the unit handler test; the real http-kit
  body is an InputStream. Guarded both.
- **`:choices` is in the tool's supported set but NOT in `renderers/render`** —
  it is interactive and needs per-request context (token/route-id/session-id), so
  it is handled at the route layer (`content-handler` branch), not the pure
  renderer map. `tool/supported-renderers` = `renderers/renderer-keys ∪ {:choices}`.
- Verification: `clojure -M:test extensions --focus extensions.dev-http-test`
  (12 tests / 75 assertions) + `… integration …` (2 tests / 29 assertions, real
  GET-form → POST-choice → captured mutation → single-shot loop) + agent-session
  `submit-synthetic-prompt-mutation-test` (1 test / 6 assertions) all green;
  clj-kondo clean.

## Slice 4 — SSE live-updates (2026-06-16)

- **`dev_http/sse.clj`** uses http-kit `as-channel` (the 2.8 async-channel API;
  `with-channel` is legacy). `make-handler` opens the stream in `:on-open`,
  sends an initial ring-response map (`content-type: text/event-stream`) whose
  body is a `data: open` event — http-kit treats the first `send!` of a response
  map as the header-setting send — then invokes `(emit-fn send! close!)`.
- **Demonstrated feed `/sse/registry`** lives in the token-gated subtree
  (router `gated-root`), emits one `data: routes N` snapshot of the current
  registry count, then closes (so a non-streaming client request completes —
  keeps the integration test deterministic with no sleeps/hangs).
  `register-sse-route!` (entry-point) registers arbitrary live feeds as throwaway
  session routes via the existing `register-route!`.
- **Testing note:** `as-channel` needs the real http-kit server connection, so
  the SSE handler is exercised only via the integration boot (token gating is
  unit-tested because the 403 short-circuits in middleware before `as-channel`).
- Verification: extensions suite 14 tests / 77 assertions; integration suite
  3 tests / 34 assertions (incl. real SSE connect → snapshot read); clj-kondo
  clean.

## Implementation review (round 1) — 2026-06-15

Verified: extensions suite (14/77) + integration (3/34) +
agent-session `submit-synthetic-prompt-mutation-test` (1/6) green; clj-kondo
clean; all AC-1..AC-10 covered. Findings filed as follow-ups in steps.md
(no-op `session_switch` subscription; throw-vs-nil inconsistency across
registration fns; duplicated `kget`/urlencoded-parse helpers; dead `source`
param on the core mutation). All are quality/consistency issues, not behaviour
defects.

## Implementation review follow-ups (round 1) — resolved 2026-06-16

- **No-op subscription removed.** Deleted the `((:on api) "session_switch"
  (fn [_ev] nil))` call from `init` — undocumented, no behaviour, no future hook
  intended.
- **Not-running idiom unified on nil.** `register-route!` now returns nil when
  the server is not running (was `throw ex-info`); `register-sse-route!`
  delegates to it so it is nil too; `register-content-route!` was already nil.
  This matches the `register-content!` seam contract the `dev-present` tool
  depends on (nil ⇒ "server not running" tool error). No test relied on the
  throw.
- **Shared helpers de-duplicated.** New `extensions.dev-http.util/kget` replaces
  the two verbatim copies in `renderers.clj` and `choices.clj`. The urlencoded
  `([^&=]+)=([^&]*)` parse is now `mw/urlencoded-param` (kept in middleware as
  the HTTP-param concern); both `query-token` (query string) and `form-choice`
  (POST body) call it. `form-choice` now also picks up middleware's
  exception-safe URL decode (previously a bare `URLDecoder/decode`).
- **Dead `source` param dropped.** `submit-synthetic-prompt` no longer
  destructures `source`; `:source` is always `:extension` (the only reachable
  value, since `source` was never in `::pco/params` nor supplied by any caller).
- Verification: extensions suite (14/77) + integration (3/34) + agent-session
  `submit-synthetic-prompt-mutation-test` (1/6) green; clj-kondo clean across
  all touched files.

## Implementation review (round 2) — 2026-06-15

Re-verified green: extensions suite (14/77), integration (3/34), agent-session
`submit-synthetic-prompt-mutation-test` (1/6); clj-kondo clean across extension
src/dev/test + touched core mutation. Code matches design and follows the
architecture (reads via api `:query`, writes only via `:mutate-session`; live
integrant system + registry held in the extension's own atom; one sanctioned
core touch = the thin `submit-synthetic-prompt` mutation; every dynamic subtree
token-gated; assets ungated by documented decision). Docs/changelog/README
present. The `:choices`-handled-at-route-layer-not-renderer-map split is a
documented, justified deviation, not a defect.

Three new quality/consistency follow-ups filed in steps.md (round 2): a
swap-side-effect in `claim-answer!`; the `/s/<id>?token=` path shape encoded in
three places; and start-status formatting duplicated between `handle-command`
and `status-text`. All are non-behavioural; no correctness defect found.

## Implementation review (round 2) follow-ups executed — 2026-06-15

All three round-2 follow-ups completed (non-behavioural quality/consistency):

- `claim-answer!` rewritten with `swap-vals!` — update fn now pure; win derived
  from prior state `(not (:answered? (get old route-id)))`, inner `won` atom gone.
- Session-route dispatch path single-sourced in `extensions.dev-http.util`:
  `session-route-prefix` (`"/s"`) + `session-route-path` (`<prefix>/<id>?token=<token>`).
  `route-url` (dev_http), choices `render-form` action, and the `router` reitit
  template (`<prefix>/:route-id`) all derive from it.
- Start/status url/token presentation single-sourced via private `url-token-lines`;
  used by both `status-text` and the `start` command branch.

Verification: extensions suite green (14/77), integration green (3/34);
clj-kondo clean across extension src. No behaviour change; test assertions
(`/s/<id>?token=`, `action="/s/c1?token=tok"`, `dev-http running`/`not running`)
all preserved.

## Implementation review (round 3) — 2026-06-15

Re-verified green: extensions suite (14/77), integration (3/34), agent-session
`submit-synthetic-prompt-mutation-test` (1/6); clj-kondo clean across extension
src/dev + the core mutation. Code matches design and follows the architecture
(reads via api, writes only via `:mutate-session`; live integrant system +
registry in the extension's own atom; one sanctioned core touch; every dynamic
subtree token-gated, assets ungated by documented decision). Verified the core
`submit-synthetic-prompt` message record shape (`:role`/`:content`/`:timestamp`/
`:source`) matches the scheduler's `scheduled-user-message` canonical shape that
drives `:session/submit-synthetic-user-prompt`. Docs/changelog/README/state all
present. No unnecessary abstraction or duplicated-existing-pattern found.

One new actionable robustness follow-up filed (round 3): the choices POST
handler claims the single-shot flag before the `:mutate-session` injection and
ignores its result, unconditionally showing a success page — latent
zero-injection-on-failure / false-success gap touching AC-6. Non-blocking,
dev-only severity; details and mitigation options in steps.md.

## Implementation review follow-ups (round 3) executed — 2026-06-15

Resolved the one round-3 robustness follow-up (choice-submit result/ordering gap):

- **`choices.clj` POST branch is now result-aware.** Replaced the
  fire-and-forget `(:mutate-session …)` + unconditional success page with
  `inject-choice!` (try-wrapped; returns true only for a truthy
  `:psi.extension/prompt-submitted?`, false on throw or falsey result). On
  failure the handler calls the new `release-claim!` (reverts the single-shot
  `:answered?`/`:answer` on the registry entry) and renders `failed-page`
  ("Could not record your choice — please try again.").
- **Ordering trade-off resolved, not merely documented.** Kept claim-first
  ordering (so concurrent in-flight submissions still see "already answered" per
  AC-7) but made it *reversible on failure*: a submission that injects zero user
  messages no longer consumes the single-shot. Net invariants:
  AC-6 — a "Recorded" page now implies a real injection; AC-7 — only a
  *successful* injection latches the answered flag, so at most one user message
  is ever injected per prompt; a failed attempt is retryable.
- **Nullable api now models the real mutation contract.** `default-mutate-fn`
  returned `{}` for unhandled mutations, so a result-aware handler would have
  read `prompt-submitted?` as nil over the real server. Added
  `'psi.extension/submit-synthetic-prompt → {:psi.extension/prompt-submitted? true}`
  to `mutation-handlers` (`components/extension-test-helpers`), so the integration
  loop test exercises the success path accurately (recording into `:mutations`
  is unchanged — it happens before the handler runs).
- **Tests:** new unit `choices-failed-injection-releases-claim-test` covers the
  falsey-result path (failure page + claim released → a subsequent submission
  succeeds and latches) and the throwing-mutation path (failure page + claim
  released). Existing AC-6/AC-7 unit + integration loop unchanged.
- Verification: extensions suite green (15 tests / 88 assertions), integration
  green (3 tests / 34 assertions), nullable-api-test green (3/6); clj-kondo clean
  across `choices.clj`, the test ns, and the nullable api.

## Implementation review (round 4) — 2026-06-15

Re-verified green: extensions suite (15 tests / 88 assertions), integration
(3/34); clj-kondo clean across extension src/dev/test. Code matches design and
follows the architecture (reads via api, writes only via `:mutate-session`;
live integrant system + registry in the extension's own atom; one sanctioned
core touch = the thin `submit-synthetic-prompt` mutation; every dynamic subtree
token-gated, assets ungated by documented decision). Docs/changelog/README/state
present and accurate.

Two new follow-ups filed in steps.md (round 4), both non-behavioural and in the
same dedup/consistency class as the prior rounds: (1) `choices/text-response`
verbatim-duplicates private `renderers/html-response` and is misnamed
(`text-response` → `text/html`), and the plain-text error-response map shape is
hand-built ~6× with no shared builder; (2) low-severity — the demonstrated
`/sse/registry` content feed is the only specific content route hardcoded into
the otherwise platform-only `router/build-handler`, lightly contradicting the
platform/content split (mechanism reason: persisted `dev/` routes can't reach
the live registry atom). No correctness defect found.

## Implementation review follow-ups (round 4) executed — 2026-06-15

Resolved both round-4 dedup/consistency + platform/content follow-ups:

- **HTTP response-map dedup.** Extracted two small builders into
  `extensions.dev-http.util`: `html-response` (200 `text/html`) and
  `text-response` (`status` + `body`, `text/plain`). Deleted the misnamed
  private `choices/text-response` (it returned `text/html`) and the private
  `renderers/html-response`; every HTML-page site now calls `util/html-response`,
  and the ~6 hand-built plain-text 4xx/404 maps across `choices` (400), `router`
  (404), `middleware` (403, now requires `util`), and `renderers` (400 + two
  404s) call `util/text-response`. Each response shape has one source; the
  misname is gone. `sse`'s event-stream response is a distinct streaming-header
  shape and was left as-is.
- **`/sse/registry` reconciled with the platform/content split (option b).**
  Removed the hardcoded `/sse/registry` route + the `sse` require from
  `router/build-handler`; the builder is now pure platform mechanism
  (`/s/:route-id` dispatch + ungated `/assets` only — no specific content route).
  The demonstrated feed is registered as an ordinary session route at `start!`
  via private `register-demo-feeds!` → `register-route! "registry"
  (sse/registry-feed-handler reg)`, reachable at `/s/registry`, token-gated by
  the `/s/` subtree. Consequence: the feed is itself a session route, so its
  `routes N` snapshot now counts itself (integration test updated `1 → 2`).
  Added a forward `(declare register-route!)` since `register-demo-feeds!`
  precedes it in the entry-point ns.
- **Docs/changelog/plan.** `doc/dev-http.md` (SSE feeds are session routes under
  `/s/`, built-in `/s/registry`; security posture no longer lists a `/sse/`
  subtree), CHANGELOG (`/sse/registry` → `/s/registry`), and `plan.md` Slice 4
  (platform/content split note) updated.
- Verification: extensions suite green (15 tests / 88 assertions), integration
  green (3 tests / 34 assertions); clj-kondo clean across extension src/test.
  No behaviour change beyond the feed URL (`/sse/registry` → `/s/registry`) and
  the self-counting snapshot.

## Implementation review (round 5) — 2026-06-15

Re-verified green: extensions suite (15 tests / 88 assertions), integration
(3/34); clj-kondo clean across extension src/dev/test + the core
`submit-synthetic-prompt` mutation. Code matches design and follows the
architecture (reads via api, writes only via `:mutate-session`; live integrant
system + registry in the extension's own atom; one sanctioned core touch; every
dynamic subtree token-gated, assets ungated by documented decision). Verified
the core mutation's user-message record shape matches the scheduler's
`scheduled-user-message` canonical shape (`:role`/`:content`/`:timestamp`/
`:source`). Docs/changelog/README/state present.

One new actionable test-coverage gap filed (round 5): the `dev-present` tool's
`:session-id`-from-`opts` threading — the invoking-session-only mechanism for the
model-callable `:choices` surface (AC-6) — is unverified (tool test passes empty
opts; the choices integration test bypasses the tool via `register-content-route!`).
No correctness/behaviour defect found in the implementation itself.

## Round 5 follow-up executed — 2026-06-15

Closed the round-5 test-coverage gap. Added two `testing` blocks to
`dev-present-tool-test` (`extensions/dev-http/test/extensions/dev_http_test.clj`):

- `opts {:session-id "sess-x"}` + `renderer "choices"` → asserts the captured
  content carries `[:content :renderer] :choices` and `[:content :session-id]
  "sess-x"`. Proves the model-callable surface threads the invoking session into
  the registered content map (the invoking-session-only mechanism for AC-6).
- empty `opts {}` → asserts the content map *contains* the `:session-id` key but
  with value `nil` (no fabricated session-id).

No production code change — the wiring (`:session-id (:session-id opts)` in
`tool.clj`) was already correct; this only adds the missing proof.

Verification: focused kaocha `--focus extensions.dev-http-test/dev-present-tool-test`
green (1 test / 21 assertions); full `extensions.dev-http-test` unit ns green
(18 tests / 128 assertions); clj-kondo clean on the test file. (An unrelated
`psi.rpc-smoke-test/rpc-smoke-handshake-test` handshake timeout appears only when
OR-focusing all `:integration` tests; not related to this change.)
