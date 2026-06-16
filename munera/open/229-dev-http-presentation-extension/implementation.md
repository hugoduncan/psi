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

## Implementation review (round 6) — 2026-06-15

Re-verified green: extensions suite (15 tests / 94 assertions), integration
(3/34), agent-session `submit-synthetic-prompt-mutation-test` green; clj-kondo
clean across extension src/dev/test + the core mutation. Code matches design and
follows the architecture (reads via api, writes only via `:mutate-session`; live
integrant system + registry in the extension's own atom; one sanctioned core
touch; every dynamic subtree token-gated, assets ungated by documented
decision). Docs/changelog/README present and coherent.

One new actionable test-coverage gap filed (round 6): the `:choices` map-option
label≠value path (`choices/normalize-option`) is unverified — all choices tests
use scalar options where label==value, so the label-displayed / value-submitted
distinction (and which one is injected) has no regression guard. No
correctness/behaviour defect found in the implementation itself.

## Implementation review (round 6) follow-up — 2026-06-15

Filled the round-6 test-coverage gap: added `choices-map-option-test`
(`extensions/dev-http/test/extensions/dev_http_test.clj`) covering the
`choices/normalize-option` map-option (label≠value) path for both keyword-keyed
(`{:label … :value …}`) and string-keyed (JSON-tool, `{"label" … "value" …}`)
options. The test proves the **label** is rendered as button text while the
**value** is the submitted/posted value and the value (not the label) is the
user message injected into the origin session — closing the regression hole
where a label/value swap would have passed all prior scalar-only choices tests.

No production code change — `normalize-option`/`render-form`/`make-handler`
already implement the behaviour; this only adds the missing proof.

Verification: focused kaocha `--focus extensions.dev-http-test/choices-map-option-test`
green (1 test / 16 assertions); all three choices unit tests
(`choices-handler-test`, `choices-failed-injection-releases-claim-test`,
`choices-map-option-test`) green (3 tests / 42 assertions); clj-kondo clean on
the test file.

## Implementation review (round 7) — 2026-06-15

Re-verified green: extensions suite (16 tests / 110 assertions), integration
(3 tests / 34 assertions); clj-kondo clean across extension src/dev/test + the
core `submit-synthetic-prompt` mutation. Independent full-source pass (every
`extensions/dev-http` ns + the core mutation + tests + docs/changelog/README/
state): code matches design and follows the architecture (extension-owned
integrant system/registry atom — no core state, no managed-service surface; the
one sanctioned core touch is the thin `submit-synthetic-prompt` mutation whose
user-message record shape matches the scheduler's `scheduled-user-message`
canonical shape; reads never touch core, the only write is `:mutate-session`;
every dynamic subtree token-gated, `/assets` ungated by documented decision;
router is pure platform mechanism). No new abstraction, no
duplicated-existing-pattern, no structural performance issue. All ACs covered by
unit + integration tests. The remaining open characteristics (`:file` arbitrary
disk read, non-constant-time token compare) are explicit documented
dev-only/localhost/token-gated design decisions, not defects.

No new actionable findings — the six prior review rounds' dedup/consistency/
robustness/coverage follow-ups are all resolved. Review complete.

## Test review (round 1) — 2026-06-15

Test-quality review (task-test-review). Three actionable findings filed in
steps.md ("Test review follow-ups (round 1)"). Non-compliance worth flagging:
the round-7 implementation-review claim "all ACs covered by unit + integration
tests" is overstated — the model-callable `dev-present` tool path (AC-3's core
claim) is never exercised end-to-end (unit stubs the register seam; integration
bypasses the tool via `register-content-route!`), and the unique unit-only
`:choices` coverage relies on the spy/stub api pattern the project's own
nullable-api ns says to avoid (testing-without-mocks / task-test-review
`¬mock ∧ ¬stub`). Findings are test-methodology/coverage gaps, not behaviour
defects.

## Test review follow-ups (round 1) executed — 2026-06-15

Resolved all three round-1 test-review follow-ups (test-only; no production
change):

- **Choices unit tests de-mocked.** Deleted the bespoke `capturing-api`/
  `result-api` spy maps. `choices-handler-test` and `choices-map-option-test`
  build the handler with the default `nullable/create-nullable-extension-api`
  (its `submit-synthetic-prompt` handler returns
  `{:psi.extension/prompt-submitted? true}` and records into `:mutations`) and
  assert the rendered page first, then a new private `submit-prompt-mutations`
  helper over `(:mutations @state)`. `choices-failed-injection-releases-claim-test`
  supplies the falsey/throwing seam via the sanctioned `:mutate-fn` override and
  asserts the rendered failure page + the registry `:answered?` claim state
  (state/output, not interaction count). Switched the choices session-id to
  `"nullable-session"` to match the nullable's session.
- **Model-callable `dev-present` tool covered end-to-end.** Added
  `^:integration dev-present-tool-renders-over-server-test`: `init` + `start!`,
  resolve the tool from `(:tools @state)`, execute it, parse the URL from the
  tool result (`Open: (\S+)`), fetch over the real server, assert
  `<h1>Tool Live</h1>`. Exercises the full `init`→`register-tool`→
  `register-content-route!`→render wiring the unit (stubbed seam) and the direct
  `register-content-route!` integration path both bypassed.
- **`:file` content-type coverage broadened.** Added a data-driven `testing`
  block to `file-renderer-test` asserting `renderers/content-type-for` across
  html/png/jpg/pdf/json/css/js/txt plus the `application/octet-stream` fallback
  for an unknown `.xyz` extension.

Verification: extensions suite green (16 tests / 116 assertions), integration
green (4 tests / 39 assertions); clj-kondo clean on the test file. No production
code touched.

## Test review (round 2) — 2026-06-15

Two actionable findings filed (steps.md "Test review follow-ups (round 2)"), both
the untested-branch regression-guard class. Non-compliance worth flagging: the
round-7 implementation review and round-1 test review claim "all ACs covered" /
"All AC-1..AC-10 covered" overstates AC-1 — the restart test labelled "AC-1:
restart leaves no orphaned server" asserts only that the *new* server serves, not
that the prior server was halted, so the no-orphan behaviour (the whole point of
AC-1's reload clause and R2) has no regression guard. Both findings are
test-coverage gaps, not behaviour defects.

## Test review (round 2) follow-ups executed — 2026-06-15

Both round-2 findings resolved.

- **AC-1 no-orphan claim now has a real regression guard.** Strengthened the
  `lifecycle-and-serving-test` "AC-1: restart leaves no orphaned server" block:
  it captures `s1`'s URL and, after the second `start!`, asserts the disjunction
  — either the same ephemeral port was re-bound (proof the prior server released
  it) or, when the ports differ, `s1`'s old URL no longer serves 200. For the
  differing-port branch to be deterministic the prior listening socket must be
  closed *before* the assertion runs. The async fire-and-forget halt made that
  flaky, so `:dev-http/server` `halt-key!` now derefs the `server-stop!` promise
  (http-kit 2.8.0: "a Promise delivered once server thread actually completes").
  This makes halt synchronous — a restart cannot leave the prior server bound —
  which genuinely strengthens the AC-1 guarantee, not merely the test. The same
  synchronous halt also removes any restart race in `start!` (which calls
  `stop!` first).
- **`load-persisted-routes` jar-safety branch now tested.** Extracted the
  resource → routes decision into pure public `routes/routes-from-resource`
  (takes the resolved `URL`/nil); `load-persisted-routes` keeps the real
  `io/resource` lookup and delegates. New `routes-from-resource-jar-safety-test`
  asserts `[]` for `nil` and for a `jar:file:…` URL (non-`file` protocol),
  guarding the documented "never scans inside a jar" behaviour.

Verification: integration suite green (4 tests / 40 assertions), extensions/unit
suite green (17 tests / 118 assertions); clj-kondo clean across the touched
src + test files.

## Test review (round 3) — 2026-06-15

One actionable coverage gap filed (steps.md "Test review follow-ups (round 3)"),
untested-surface regression-guard class. Non-compliance worth flagging: the
round-7 implementation review's "All AC-1..AC-10 covered" and round-1/round-2
test-review "all ACs covered" claims overstate AC-1's command surface — the
`/dev-http start|status|stop` command handler (`handle-command`) is never
invoked by any test (tests call `start!`/`stop!`/`status-text` directly), so its
subcommand dispatch and usage fallback have no regression guard; relatedly the
running-status url/token lines are asserted nowhere. Test-only coverage gap, not
a behaviour defect. Tests are otherwise well-formed and use the sanctioned
nullable extension API (no mocks/stubs of infra deps).

## Test review (round 3) follow-ups executed — 2026-06-15

Closed the round-3 command-surface coverage gap (test-only; no production
change). Added `^:integration dev-http-command-handler-test`
(`extensions/dev-http/test/extensions/dev_http_test.clj`):

- Resolves the *registered* handler from the nullable state
  `(get-in @state [:commands "dev-http" :handler])` and drives it through
  `status` (pre-start ⇒ `dev-http not running`), `start`, `status`, `stop`
  (⇒ `dev-http stopped`), and an unknown subcommand `wat`
  (⇒ `usage: /dev-http start | status | stop`), asserting on the drained log
  lines (`nullable/drain-log!`) and the running/stopped server state. This is
  the first test to exercise `handle-command`'s arg-parse + `case` subcommand
  routing and the usage fallback (other tests call `start!`/`status-text`/
  `stop!` directly).
- Folds in the low round-3 item: the post-`start` `status` (and the `start`)
  log output is asserted to contain the base URL (`http://127.0.0.1:<port>`) and
  the live token (extracted from `sut/route-url` via a small `live-token`
  helper), guarding the round-2-dedup `url-token-lines` presentation.

Added `[clojure.string :as str]` to the test ns (for `str/includes?` on the
url-safe base64 token, which avoids regex-special-char issues). Verification:
focused `dev-http-command-handler-test` green (1 test / 14 assertions);
extension integration suite green (5 tests / 54 assertions); unit suite green
(17 tests / 118 assertions); clj-kondo clean on the test file.

## Test review (round 4) — 2026-06-15

One actionable coverage/dead-setup finding filed (steps.md "Test review
follow-ups (round 4)"). Tests are otherwise well-formed and use the sanctioned
nullable extension API + ctx seams throughout (no mocks/stubs of infra deps).
Non-compliance worth flagging: the round-7 implementation review's "All
AC-1..AC-10 covered" and the round-1/2/3 test-review "all ACs covered" claims
overstate AC-6 — its "drives the agent's next turn immediately" clause is
asserted nowhere. The one real-ctx test
(`submit-synthetic-prompt-injects-user-message-test`) wires the
`:execute-prepared-request-fn` "ack" seam to complete a turn but asserts only
the injected user message + submit-event log entries, never the resulting
assistant turn; the integration loop uses the nullable, which records the
mutate call without driving a turn. Test-coverage gap, not a behaviour defect.

## Test review (round 4) follow-ups executed — 2026-06-15

Closed the round-4 turn-drive coverage/dead-setup gap (test-only; no production
change). Extended `submit-synthetic-prompt-injects-user-message-test`
(`components/agent-session/test/psi/agent_session/submit_synthetic_prompt_mutation_test.clj`)
with a third `testing` block asserting AC-6's "drives the agent's next turn
immediately" clause: after the synthetic prompt is injected, the journal now
contains exactly one `"assistant"` message whose `[:content 0 :text]` is the
`:execute-prepared-request-fn` seam's stub `"ack"`. Previously the seam's `"ack"`
return was dead setup — the test wired it to "complete a turn deterministically"
but never asserted the resulting assistant turn, leaving the turn-drive half of
AC-6 unguarded. Now a regression where the synthetic prompt failed to drive the
downstream turn would fail this test. Verification: focused Scry green
(8 assertions, up from 6); clj-kondo clean on the test file.

## Test review (round 5) — 2026-06-15

One actionable coverage gap filed (steps.md "Test review follow-ups (round 5)"),
untested-public-surface regression-guard class. The public Slice 4
`register-sse-route!` REPL fn (design §SSE; plan/steps Slice 4) is invoked by no
test — `sse-live-feed-test` and the auto `register-demo-feeds!` both use
`register-route!`, bypassing it — so its `sse/make-handler` wrap + delegation
have no regression guard. Tests are otherwise well-formed and use the sanctioned
nullable extension API + ctx seams throughout (no infra-dep mocks/stubs).

Non-compliance worth flagging: the round-7 implementation review's "All
AC-1..AC-10 covered" and the round-1..4 test-review "all ACs covered" framing
again overstates coverage at the surface granularity — `register-sse-route!` is
a planned, documented public dev surface left wholly unexercised, the same class
of gap the prior four test-review rounds each found and closed. Coverage gap,
not a behaviour defect.

## Test review (round 5) follow-up executed — 2026-06-15

Closed the round-5 untested-public-surface gap (test-only; no production
change). Added `^:integration register-sse-route!-test`
(`extensions/dev-http/test/extensions/dev_http_test.clj`):

- Before `start!`: asserts `(sut/register-sse-route! …)` returns `nil` (the
  documented not-running contract shared with `register-route!`).
- After `init`+`start!`: registers a feed via
  `(sut/register-sse-route! "feed" (fn [send! close!] (send! "tick") (close!)))`,
  asserts the returned URL is non-nil, that `/s/feed` is 403 without the token
  (token-gated like any session route, AC-8), and that
  `/s/feed?token=…` over the real ephemeral-port server returns 200
  `text/event-stream` carrying `data: open` + `data: tick`. This is the first
  test to drive `register-sse-route!`'s `sse/make-handler` wrap + delegation to
  `register-route!` — `sse-live-feed-test` and the auto `register-demo-feeds!`
  both use `register-route!` directly, bypassing it. A regression (raw emit-fn
  bound without the wrap, swapped arg order, or lost delegation) now fails.

No production code touched — the wiring was already correct; this adds the
missing proof.

Verification: focused `register-sse-route!-test` green (1 test / 7 assertions);
full dev-http unit suite green (17 tests / 118 assertions); dev-http integration
tests green (6 tests / 61 assertions); clj-kondo clean on the test file. The
unrelated `psi.rpc-smoke-test/rpc-smoke-handshake-test` handshake timeout
reappears only when OR-focusing all `:integration` tests (consistent with prior
rounds), not related to this change.

## Test review (round 6) — 2026-06-15

One actionable finding filed (steps.md "Test review follow-ups (round 6)").
Non-compliance worth flagging: the round-1 test-review's de-mock cleanup was
incomplete — it converted the choices handler tests' bespoke spy maps to the
nullable api and to state/output assertions, but left `dev-present-tool-test`
built on a bespoke spy `register!` seam with interaction assertions on a
`captured` atom (`[:content :renderer]`/`[:content :data]`/`:route-id`/
`(= :unchanged @captured)`); round-5 then *added* the `[:content :session-id]`
interaction assertions onto that same spy. So a unit test contradicting both
task-test-review (`¬mock ∧ ¬stub`) and the project's `¬assert(interactions)`
guideline survived all five prior test-review rounds. The seam registers into an
in-memory registry (logic, not infra), so it is fixable to a real,
state-observable register fn — not an unavoidable boundary fake. Test-quality /
methodology issue, not a behaviour defect; all other tests remain well-formed
and use the sanctioned nullable api + ctx seams.

## Test review (round 6) follow-up — 2026-06-15

De-mocked `dev-present-tool-test` (the lone surviving spy/interaction unit
test). Replaced the bespoke `register!`/`captured` spy with a real
`registry-register-fn` seam registering normalized content into a real
`registry/create-registry` (`{:content content}`, mirroring the production
`register-content-route!` seam) and returning a deterministic
`util/session-route-path` URL. Assertions now read registry state
(`registry/get-entry` / `registry/entries`) for renderer/data/route-id,
generated `^r-` id, threaded `:session-id` (and nil), and no-registration on
unknown renderer (`empty? entries`, replacing `(= :unchanged @captured)`).
Tool-result output assertions unchanged. No production change. Focused
1/21, dev-http unit ns 17/118, `:extensions` suite 245/906 green; clj-kondo
clean. Closes the round-6 finding; no remaining open follow-ups.

## Test review (round 7) — 2026-06-15

Two actionable coverage gaps filed (steps.md "Test review follow-ups
(round 7)"); both are untested-behaviour regression-guard gaps, no behaviour
defect. (1) The `mw/urlencoded-param` `URLDecoder/decode` path is unverified —
every choices/token test uses unencoded values, so the decode the submitted-
choice→injected-user-message round-trip depends on (a `{:label :value}` value
with a space/special char) has no guard. (2) The idiomatic keyword-tag branch of
`renderers/coerce-hiccup` (REPL/`register-route!` `:hiccup` path) is untested;
only the JSON string-tag branch is. No non-compliance with the review
instructions to note; all existing tests are well-formed and use the sanctioned
nullable api + ctx seams (the round-6 de-mock closed the last spy/interaction
test).

## Test review follow-ups (round 7) executed — 2026-06-15

Both round-7 coverage gaps closed, test-only, no production change.

(1) Added handler-level `choices-urlencoded-decode-test` — `doseq` over
`["plus-space" "a b" "choice=a+b"]` and `["percent-slash" "a/b" "choice=a%2Fb"]`
through a real `choices/make-handler` over a `{:label "Opt" :value <value>}`
option + nullable api/state: GET renders the raw decoded `value="…"`, POST of the
URL-encoded body injects the **decoded** value as the single
`submit-synthetic-prompt` `:user-msg`. Guards `mw/urlencoded-param`'s
`URLDecoder/decode` path (`+`→space, `%2F`→`/`).

(2) Added an idiomatic keyword-tag `:hiccup` assertion inside `renderers-test`
(`[:div {} [:h1 "X"]]` → `<div><h1>X</h1></div>`), exercising the `(vector?
form)` passthrough branch of `renderers/coerce-hiccup` distinct from the
existing JSON string-tag coercion case.

Verification: focused `extensions` suite green (18 tests / 129 assertions);
clj-kondo clean on the test file. No remaining open round-7 follow-ups.

## Test review (round 8) — 2026-06-15

One actionable coverage gap filed (steps.md "Test review follow-ups (round 8)"),
the same untested-branch regression-guard class as the round-2/6/7 gaps: the
headerless `:table` branch (`render-table`'s `(when (seq headers) …)`) is
unguarded — both `:table` assertions supply `:headers`, leaving the optional
rows-only / no-`<thead>` path with no test. Low severity, no behaviour defect.

Non-compliance worth flagging: the round-7 implementation review's "All
AC-1..AC-10 covered" framing again slightly overstates AC-5 ("each renderer
produces the expected response for representative input") — the `:table`
renderer's documented optional-headers variation is one representative input
left unexercised, the eighth such surface/branch gap the prior test-review
rounds have each surfaced. Tests are otherwise well-formed and uniformly use the
sanctioned nullable extension API + ctx seams (no infra-dep mocks/stubs; the
round-6 de-mock closed the last spy/interaction test).

## Test review (round 8) follow-up — 2026-06-15

Executed the single round-8 actionable item: added a headerless `:table`
regression guard. New `testing` block in `renderers-test`
(`extensions/dev-http/test/extensions/dev_http_test.clj`) renders
`{:renderer :table :data {:rows [[1 2]]}}` (rows-only, no `:headers`) and asserts
the body carries the `<td>1</td><td>2</td>` cells but contains neither `<thead>`
nor `<th>` — covering the `(seq headers)` false branch of `render-table` that
both prior `:table` assertions (both supply `:headers`) left unguarded.

Verification: full `extensions` suite green — 246 tests / 920 assertions,
`:pass? true`. The non-zero scry exit is a pre-existing harness artifact (a
single `nil`-var "unknown-result" classification, not a failure/error; all
assertions pass). clj-kondo clean on the touched test file. No remaining
unchecked round-8 follow-ups.

## Test review (round 9) — 2026-06-15

One actionable coverage gap + one low sub-item filed (steps.md "Test review
follow-ups (round 9)"), the same untested-behaviour regression-guard class as
rounds 6-8. The `:mermaid` renderer test is named "…and the source" and feeds a
diagram source, but asserts only the vendored JS + `class="mermaid"` shell,
never that the diagram source itself is embedded — `render-mermaid`'s core job
(`[:pre {:class "mermaid"} (str data)]`) has no regression guard, and a
source-dropping regression would pass while the test name claims otherwise.
(`:vega` already asserts its spec source, so this is `:mermaid`-only.) Low
sub-item: `:vega` omits the base `vega.min.js` script + the `vegaEmbed(…)`
invocation.

Non-compliance worth flagging: the round-7 implementation review's "All
AC-1..AC-10 covered" / "each renderer produces the expected response" framing
overstates AC-5 for `:mermaid` — the renderer's source-embed (the expected
response's substance) is unverified despite the test name asserting it. Tests
are otherwise well-formed and uniformly use the sanctioned nullable extension
API + ctx seams (no infra-dep mocks/stubs).

## Test review follow-ups (round 9) executed — 2026-06-15

Executed both round-9 items (one actionable + one low) in `renderers-test`
(`extensions/dev-http/test/extensions/dev_http_test.clj`).

- `:mermaid` source-embed guard: added
  `(is (re-find #"graph TD; A--&gt;B" (:body resp)))`. hiccup2 HTML-escapes the
  `>` in the `[:pre {:class "mermaid"} (str data)]` body, so the embedded
  diagram source renders as `graph TD; A--&gt;B` (verified empirically via the
  focused run, not assumed). The assertion now guards `render-mermaid`'s core
  job — embedding `(str data)` — so a regression rendering an empty
  `<pre class="mermaid"></pre>` fails, and the test name ("…and the source") is
  no longer a lie.
- `:vega` completeness: added `(is (re-find #"/assets/vega\.min\.js" …))` (the
  base vega.min.js script — a required dependency of vega-embed, previously
  unasserted) and `(is (re-find #"vegaEmbed\('#vega-view'" …))` (the embed
  invocation). A regression dropping the base script or the embed call now
  fails.

Verification: focused kaocha `--focus extensions.dev-http-test/renderers-test`
green — 1 test / 22 assertions / 0 failures (was 18 assertions). clj-kondo
clean on the touched test file. No remaining unchecked follow-ups in steps.md.

## Test review (round 10) — 2026-06-15

One (Low) coverage gap filed (steps.md "Test review follow-ups (round 10)").
Tests are well-formed, green (extensions 246/923, dev-http integration green;
the lone `rpc-smoke-handshake-test` :integration failure is the documented
pre-existing unrelated flake), and uniformly back infra deps with the sanctioned
nullable extension API + ctx seams (no mocks/stubs/spies — rounds 1/6 already
de-mocked the choices/dev-present unit tests).

Non-compliance worth flagging: the round-7 implementation review's
"each renderer produces the expected response" / AC-5 framing slightly overstates
`:file` completeness — `:file` is the only map-keyed renderer whose string-keyed
(JSON-tool) representative input, the live AC-3 `dev-present` surface, is
unverified, while `:table` and `:choices` map-options both cover their string-key
(JSON-tool) variant per the renderers-ns "accessors read both" contract. Parallel
to the round-9 `:mermaid` AC-5 overstatement.

## Test review follow-up (round 10) — executed — 2026-06-16

Closed the lone round-10 (Low) gap: the `:file` renderer's string-keyed
(JSON-tool) `{"path" …}` data variant was unverified — `file-renderer-test`
exercised only the keyword `{:path …}` variant, leaving `render-file`'s
`(kget data :path "path")` string-key fallback (the live AC-3 `dev-present`
JSON-tool `:file` surface) without a `:file`-specific regression guard.

Added a `:file reads the string-keyed (JSON-tool) {"path" …} variant` `testing`
block to `file-renderer-test`
(`extensions/dev-http/test/extensions/dev_http_test.clj`): writes a temp `.svg`,
renders `{:renderer :file :data {"path" <abs-path>}}`, asserts 200 +
`image/svg+xml` content-type + file body, mirroring the keyword-keyed case. A
regression swapping `(kget data :path "path")` for `(:path data)` now fails the
JSON-tool `:file` path. Closed the round-7-implementation-review/round-9
`:file` AC-5 overstatement noted above.

Verification: focused kaocha `--focus extensions.dev-http-test/file-renderer-test`
green — 1 test / 16 assertions / 0 failures; clj-kondo clean on the touched test
file. No remaining unchecked follow-ups in steps.md.

## Implementation review pass — 2026-06-16 — REVIEW_COMPLETE

Full implementation-review pass over the dev-http extension src
(`dev_http.clj` + the 11 platform nss + `dev/demo.clj`), the one sanctioned core
touch (`psi.extension/submit-synthetic-prompt` in
`components/agent-session/.../mutations/prompts.clj`), docs (`doc/dev-http.md`,
README, CHANGELOG), and the test suites.

Verified: design/AC fit; extension-isolation posture (integrant system + server
+ registry held only in the extension atom; HTTP handlers touch core state only
via `:mutate-session`; no `:query` core reads needed); the
`submit-synthetic-prompt` message shape (`:role "user"` / `:content` /
`:timestamp` / `:source :extension`) mirrors the scheduler's
`scheduled-user-message` and dispatches the same
`:session/submit-synthetic-user-prompt` path with `:origin :mutations`; renderer
/ router / registry / middleware / sse / util / tool / config code quality and
single-source dedups (`util` response builders, `session-route-path`,
`url-token-lines`); single-shot robustness (claim→inject→release ordering,
synchronous `halt-key!` deref); the deliberate `:file` arbitrary-path vs
`/assets` `..`-rejecting asymmetry (intended escape hatch per design); the
platform-thin router (no content route hardcoded; `/s/registry` registered as a
session route at `start!`).

Outcome: extensions unit suite 18 tests / 138 assertions / 0 failures;
integration 6 / 61 / 0; clj-kondo clean. No new actionable implementation-level
issues found — the prior 6 implementation-review and 10 test-review rounds have
resolved the dedup, robustness, and coverage classes. No non-compliance with the
review instructions to report. No new steps.md follow-ups added.

## Test review (round 11) — 2026-06-15

Full test review pass (task-test-review) over the dev-http extension suite
(`extensions/dev-http/test/extensions/dev_http_test.clj`, 18 unit + 6
integration) and the one sanctioned core mutation test
(`submit_synthetic_prompt_mutation_test.clj`).

Verified: tests well-formed and green (extensions suite 246/926, 0 fail/0 err;
dev-http integration 150 assertions green — the lone `rpc-smoke-handshake-test`
:integration failure is the documented pre-existing unrelated flake); every
design behaviour AC-1..AC-10 has a covering test; all infra deps backed by the
sanctioned nullable extension API + ctx seams (no mocks/stubs/spies remain after
rounds 1/6). The renderer-branch / JSON-tool-input / untested-public-surface
coverage class chased across rounds 2–10 is exhausted down to Low items.

No new actionable test issues found. The one remaining untested branch
(`choices/normalize-option`'s single-key `{:label}`-only / `{:value}`-only map
fallback) is incidental defensive code, not a documented design behaviour
(design specifies scalar or `{:label :value}` options), and sits below the
severity floor of the prior Low coverage items — not filed. No non-compliance to
report. No new steps.md follow-ups added.

## Test review (round 12) — test-shaper — 2026-06-15 — ACTIONABLE_FEEDBACK

test-shaper pass (clarity/consistency/economy/meaningful-failures) over
`extensions/dev-http/test/extensions/dev_http_test.clj`. Rounds 1–11 closed the
*coverage* class; this pass targets *shaping*. Five follow-ups filed in steps.md
round 12: (1) integration `choices-interaction-loop-test` re-inlines the
`submit-prompt-mutations` predicate instead of the existing helper; (2) the five
`:choices` unit tests repeat the same nullable-api+registry+content+handler+
register setup and magic strings — extract a `make-choices-handler` helper; (3
low) `renderers-test` shadows `clojure.core/str` with a local; (4 low) the AC-1
same-port restart branch asserts the `if` condition itself (tautology, no
signal); (5 low) base-URL assembly duplicated across four integration tests.

Note (instruction non-compliance): the requested task slug
`229-dev-http-lifecycle` does not exist; the only matching task is
`229-dev-http-presentation-extension`, which was reviewed (its design §Lifecycle
covers the dev-http lifecycle surface).

## Round 12 follow-ups executed (test-shaper)

All five round-12 test-shaping follow-ups in
`extensions/dev-http/test/extensions/dev_http_test.clj` completed:

- (1) `choices-interaction-loop-test` AC-6/AC-7 now reuse
  `(submit-prompt-mutations state)` — one source for the recorded-mutation shape.
- (2) Added a `make-choices-handler` helper (`{:options :prompt :api :reg
  :route-id :session-id}` → `{:handler :state :reg :api}`) building the
  `:choices` content, handler, and registered entry. Adopted by
  `choices-handler-test`, both `choices-failed-injection-releases-claim-test`
  branches, `choices-map-option-test`, `choices-urlencoded-decode-test`; added a
  `nullable-session` const for the duplicated magic string. The helper exposed
  two now-redundant nested `let`s in the failure-injection test (the helper does
  the registration that previously separated them) — merged them to keep
  clj-kondo clean.
- (3 low) Renamed `renderers-test` `:table` block local `str` → `str-resp`
  (drops the `clojure.core/str` shadow).
- (4 low) `lifecycle-and-serving-test` restart: replaced the tautological
  same-port `if` branch with a `when (not= (:port s1) (:port s2))` guard +
  comment noting the same-port evidence is the earlier s2 serve-200 assertion.
- (5 low) Added a `(server-base server)` helper used by the four integration
  tests; skipped the optional `token-url` companion (low marginal value).

Verification: focused extensions suite green (18 tests / 138 assertions),
integration suite green (6 tests / 61 assertions) — both identical to the
pre-refactor baseline (behaviour preserved); clj-kondo clean (0/0).

## Test review (round 13) — test-shaper — 2026-06-15 — ACTIONABLE_FEEDBACK

test-shaper pass over `extensions/dev-http/test/extensions/dev_http_test.clj`
targeting shaping gaps round 12 left open. Four follow-ups filed (round 13): a
running-server arrange helper for the four init→start!→try/finally-stop!
integration tests (round 12 only extracted `server-base`, not this wrapper); the
`{:path "/test/dev_http.clj"}` literal duplicated across 10 sites; the lingering
`"nullable-session"` string literals; and `init-captures-api-test`'s
implementation-coupled `@@#'sut/state` capture assertion.

Non-compliance noted: the round-12 implementation.md/steps note claims the loop
test's `"nullable-session"` literal "was replaced with the `nullable-session`
const", but only the *assertion* was changed — the `register-content-route!`
*registration* literal (and the `dev-present` render test's `opts` literal)
still hardcode the string. The round-12 done-claim overstated the change.

## Round 13 follow-ups executed (test-shaper) — 2026-06-15

All four round-13 test-shaping follow-ups in
`extensions/dev-http/test/extensions/dev_http_test.clj` completed:

- Added a `with-running-server` **function** helper (callback-style:
  `(with-running-server (fn [{:keys [server state api]}] …))`). It `init`s +
  `start!`s the extension against a real ephemeral-port server, invokes `f` with
  `{:server :state :api}`, and `stop!`s in a `finally`. Adopted by the four
  init→start→try/finally-stop integration tests
  (`dev-present-tool-renders-over-server-test`, `choices-interaction-loop-test`,
  `sse-live-feed-test`, `register-sse-route!-test`). Chose a fn over a macro so
  clj-kondo fully analyzes the destructured callback binding (a macro with a
  single destructured binding form + body has no matching `:lint-as` and would
  need a hook — kondo flagged the macro's `{:keys [state]}`/`{:keys [server]}`
  as unresolved). `register-sse-route!-test` keeps its pre-start not-running
  contract assertion as a separate `init`-only block (the helper always starts).
  Excluded `lifecycle-and-serving-test` and `dev-http-command-handler-test` per
  the follow-up (they drive the lifecycle surface directly).
- Added a `test-ext-path` const + `nullable-api` (`[]`/`[opts]`) helper; all
  `create-nullable-extension-api` sites now flow through it (the fixture path has
  one source; failure-seam sites pass `{:mutate-fn …}` as the override).
- Both lingering `"nullable-session"` registration literals now use the
  `nullable-session` const (tool `opts` in the render test; the
  `register-content-route!` `:session-id` in the loop test) — the round-12
  done-claim overstated; this closes it.
- `init-captures-api-test`: dropped the implementation-coupled
  `(is (identical? api (:api @@#'sut/state)))` internal-atom assertion in favour
  of the adjacent observable `/dev-http`-command-registration signal; renamed the
  top testing label accordingly.

Verification: extensions (unit) suite green (18 tests / 137 assertions — one
fewer than round 12's 138, the removed internal-atom `identical?` assertion);
integration suite green (6 tests / 61 assertions, identical to the round-12
baseline — behaviour preserved); clj-kondo clean (0/0); cljfmt clean.

## Test review (round 14) — test-shaper — 2026-06-15 — ACTIONABLE_FEEDBACK

test-shaper pass over `extensions/dev-http/test/extensions/dev_http_test.clj`
(coverage class closed in rounds 1–11; shaping in 12–13). Two remaining shaping
follow-ups filed (round 14): (1) router-ring-handler construction has two
encodings — the `build-test-handler` helper vs an inline `router/build-handler
{… :persisted-routes []}` in two tests (`consistent(test_abstractions)`); (2 low)
`init-captures-api-test`'s name no longer matches its round-13-reshaped
behaviour (`consistent(naming)`).

Non-compliance noted: the requested task slug `229-dev-http-lifecycle` does not
exist; the only matching task is `229-dev-http-presentation-extension` (reviewed).

## Round 14 follow-ups executed (test-shaper) — 2026-06-15

Both round-14 test-shaping follow-ups in
`extensions/dev-http/test/extensions/dev_http_test.clj` completed:

- Generalized `build-test-handler` to a 2/3-arity fn: `([reg token]
  (build-test-handler reg token (routes/load-persisted-routes)))` /
  `([reg token persisted] (router/build-handler {… :persisted-routes
  persisted}))`. The two inline `(router/build-handler {… :persisted-routes
  []})` sites (`asset-route-is-ungated-test`, `sse-route-is-token-gated-test`)
  now call `(build-test-handler reg "tok" [])`. All four router-handler
  constructions flow through one helper; a router-shape change is now single-source.
- Renamed `init-captures-api-test` → `init-wires-api-test` to match its
  round-13-reshaped observable `/dev-http`-command-registration assertion
  (the removed `identical?` internal-atom capture no longer named). The inner
  `testing` label was already reshaped in round 13.

Verification: full extensions suite green via `bb clojure:test:extensions`
(925 assertions, 0 fail, 0 error; `:pass? true`). The lone scry-reported
"unknown" is `psi.metrics.extension-test/summary-data-conforms-to-schema-after-events-test`
— unrelated to dev-http and pre-existing. All four affected dev-http tests
(`init-wires-api-test`, `asset-route-is-ungated-test`,
`sse-route-is-token-gated-test`, `token-gating-test`) ran green. clj-kondo
clean (0/0); cljfmt clean.

Note (instruction non-compliance, unchanged): the requested task slug
`229-dev-http-lifecycle` does not exist; the only matching task is
`229-dev-http-presentation-extension`, executed here.
