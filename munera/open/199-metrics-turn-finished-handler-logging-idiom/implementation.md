# Implementation notes

## Design review — ambiguity pass (2026-06-01)

Reviewed `design.md` only (not plan/steps). Actionable ambiguities found:

- **Sibling-site reference contradicts timbre framing.** Acceptance pins the log
  level to be "consistent with sibling swallowed-error log sites", but the
  nearest siblings — same `psi/metrics` extension, `persistence.clj:47,51` —
  use raw `println` with hand-written `"WARN [psi/metrics]"` prefixes, *not*
  timbre. So the intent's `consistent(idioms)` is itself ambiguous: consistent
  with the project (→ timbre) or with the local file (→ println). Design does
  not say whether persistence.clj's println sites are the reference, an
  excluded counter-example, or also in-scope to convert.
- **timbre dependency not declared.** `extensions/metrics/deps.edn` declares
  only clojure + malli; it does NOT depend on `com.taoensso/timbre` (nor
  statecharts, the transitive source). The only timbre-using extension
  (`mcp-tasks-run`) declares timbre explicitly in its own deps.edn. Design says
  "add the require" but is silent on adding the dep, so the extension's `:test`
  alias / standalone load would lack timbre.
- **Level non-determinable as written.** Design says "choose debug or warn to
  match sibling sites", but the in-extension siblings carry no timbre level
  (they're println), so there is no in-extension timbre precedent to match —
  the disambiguation rule resolves to nothing.
- **Message/exception shape unspecified.** Whether to preserve the
  session-id + ex-message content and whether to pass the exception `e` as a
  structured timbre arg (e.g. `(timbre/warn e ...)`) vs string interpolation is
  not stated.
