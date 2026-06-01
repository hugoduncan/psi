# Steps — 199 metrics turn-finished handler logging idiom

## Slice 1 — timbre logging idiom

- [ ] Add `com.taoensso/timbre {:mvn/version "6.8.0"}` to the `:deps` map in
      `extensions/metrics/deps.edn` (alongside clojure + malli).
- [ ] Add `[taoensso.timbre :as timbre]` to the `:require` of the
      `psi.metrics.extension` namespace in
      `extensions/metrics/src/psi/metrics/extension.clj`.
- [ ] In `make-turn-finished-handler`'s `catch Exception e` branch (~line 99),
      replace
      `(println (str "DEBUG [psi/metrics] skipping token tracking for session " session-id ": " (ex-message e)))`
      with `(timbre/warn e "skipping token tracking for session" session-id)`.
- [ ] Run `clj-paren-repair extensions/metrics/src/psi/metrics/extension.clj`
      to balance/format after the edit.

### Verification

- [ ] Confirm the standalone `src` classpath resolves timbre:
      `cd extensions/metrics && clojure -Spath | tr ':' '\n' | grep -i timbre`
      (expect a timbre jar present).
- [ ] Confirm the `:test` alias resolves timbre `6.8.0`:
      `cd extensions/metrics && clojure -A:test -Spath | tr ':' '\n' | grep -i timbre`
      (expect timbre, no conflicting version).
- [ ] Confirm the extension namespace loads: load
      `psi.metrics.extension` (nREPL `require`/`load` or
      `clojure -e "(require 'psi.metrics.extension)"` on the appropriate
      classpath) without error.
- [ ] `clj-kondo --lint extensions/metrics/src/psi/metrics/extension.clj` is
      clean (no new findings introduced by the change).
- [ ] Re-read the changed `catch` branch and confirm: no `println`/`"DEBUG"`
      prefix remains, the handler still returns `nil`, and the token-tracking
      success path is untouched.

### Acceptance check

- [ ] All acceptance criteria in `design.md` satisfied: `timbre/warn` with
      structured `e` first arg + `"skipping token tracking for session"` +
      `session-id`; `println` removed; `6.8.0` dep + `timbre` require present;
      standalone + `:test` load resolve timbre; `clj-kondo` clean; no
      success-path / `nil`-return change.

### Commit

- [ ] Commit the changed `extension.clj` and `deps.edn` with a `⚒`-tagged
      message referencing task 199 (timbre logging idiom).
