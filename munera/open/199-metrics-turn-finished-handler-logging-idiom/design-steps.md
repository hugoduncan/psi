# Design follow-up steps

## Ambiguity review (2026-06-01)

- [x] Resolve the sibling-site contradiction: `persistence.clj` swallowed-error
      sites use raw `println "WARN [psi/metrics] ..."`, not timbre. State
      explicitly whether they are (a) the reference idiom (→ argues against
      timbre), (b) an excluded counter-example, or (c) also in-scope to convert.
      Make the intent's `consistent(idioms)` unambiguous (project-standard vs
      local-file consistency).
- [x] Specify whether `com.taoensso/timbre` must be added to
      `extensions/metrics/deps.edn` (it is currently absent; only the
      app-runtime classpath provides it transitively). Confirm the extension's
      `:test` alias and standalone load resolve timbre after the change.
- [x] Make the log-level choice determinable: since in-extension siblings carry
      no timbre level, give a concrete rule (pin the level outright, or name an
      out-of-extension reference site whose level governs).
- [x] Specify the replacement call's content contract: which fields (session-id,
      ex-message) are preserved, and whether the exception `e` is passed as a
      structured timbre arg vs interpolated into the message string.
