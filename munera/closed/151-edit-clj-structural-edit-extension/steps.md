# Steps — 151 edit-clj structural edit extension

## Slice 1 — Pure core

- [ ] Create `extensions/edit-clj/deps.edn`
  - runtime: `rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}`, `cheshire/cheshire {:mvn/version "5.13.0"}`
  - `:test` alias: kaocha + `psi/extension-test-helpers` + `psi/agent-session` (mirror `extensions/github/deps.edn`)

- [ ] Implement `psi.edit-clj.core/parse-single-form`
  - parse string via rewrite-clj zipper
  - return `{:ok node}` on success
  - return `{:error {:code :parse-error :argument <name> :message ...}}` on invalid Clojure or more than one top-level form

- [ ] Implement `psi.edit-clj.core/find-candidates`
  - depth-first walk of file-content string
  - compare each node's `sexpr` to old-node's `sexpr`; skip nodes where `sexpr` throws
  - return vector of `{:node :row :col :text}` maps

- [ ] Implement `psi.edit-clj.core/apply-line-filter`
  - each bound independently open when absent: keep where `(no start-line OR start-line ≤ :row) AND (no end-line OR :row ≤ end-line)`
  - no-op when neither bound is supplied

- [ ] Implement `psi.edit-clj.core/replace-in`
  - receive old-node, new-node, file-content string, filtered candidates
  - 0 candidates → no-match result map
  - 2+ candidates → ambiguous-match result map with locations
  - 1 candidate → replace node; return ok result map with updated file-content string
  - replacement node taken directly from format-preserving parse; never round-tripped through `sexpr`/`coerce`

- [ ] Write `extensions/edit-clj/test/psi/edit_clj/core_test.clj`
  - AC 1: single match → replaced; all content outside node character-for-character identical
  - AC 2: no match → no-match map; content string unchanged
  - AC 3: two matches → ambiguous-match map; correct `match-count` and `matches` locations
  - AC 4: `parse-single-form` — invalid Clojure → parse-error; argument name in error map
  - AC 8: multi-form `old-string` → parse-error; multi-form `new-string` → parse-error
  - AC 6a: two identical forms; one in range, one outside → single match after filter
  - AC 6b: form starts in range, ends past `end-line` → matched
  - AC 6c: form ends in range, starts before `start-line` → not matched
  - AC 6d: two identical forms both in range → ambiguous-match
  - AC 6e: valid range, no form starts there → no-match
  - AC 6f: symbol nested inside straddling parent; symbol's own row in range → matched
  - AC 7: `new-string` with inline comment → comment text present in output string

- [ ] Verify slice 1: `clj -M:test --focus psi.edit-clj.core` green; lint clean

## Slice 2 — Extension shell

- [ ] Implement `psi.edit-clj.extension/resolve-path`
  - relative path → resolved against `cwd`; absolute path → unchanged

- [ ] Implement `psi.edit-clj.extension/execute`
  - enforces validation order:
    1. `core/parse-single-form(old-string)` → return error map if invalid
    2. `core/parse-single-form(new-string)` → return error map if invalid
    3. Resolve path; return file-not-found map if not readable
    4. Read file content
    5. `core/find-candidates` → `core/apply-line-filter` → `core/replace-in`
    6. On `:ok` write updated content back to file
    7. Merge `:filename` (resolved path string) into result map (core omits it)
    8. Serialise result map to JSON string via cheshire
  - supports `([args])` and `([args opts])` arities; `:cwd` from opts for path resolution

- [ ] Implement `psi.edit-clj.extension/tool-def`
  - `:name "edit-clj"`
  - `:description` ≤ 20 words, explicit one-form contract
  - `:parameters` as data map (not `pr-str` string)

- [ ] Implement `psi.edit-clj.extension/init`
  - register tool via `(:register-tool api)`

- [ ] Write `extensions/edit-clj/test/psi/edit_clj/extension_test.clj`
  - AC 2 (file-unchanged): execute with no-match → temp file content identical before and after call
  - AC 3 (file-unchanged): execute with ambiguous-match → temp file content identical before and after call
  - AC 4 (order): both strings invalid → old-string parse-error returned
  - AC 4 (order): invalid old-string + missing file → parse-error (not file-not-found)
  - AC 5: valid strings + non-existent file → file-not-found JSON (`"status": "error"`)
  - AC 9: tool `:description` ≤ 20 words and mentions one-form contract
  - AC 10: `init` registers exactly one tool named `"edit-clj"` (use `ext/create-registry` + `ext/create-extension-api` pattern from `github`'s extension test)
  - AC 1 (round-trip): execute with temp file → file written; result is `"status": "ok"` JSON

- [ ] Verify slice 2: `clj -M:test --focus psi.edit-clj` green; lint clean

## Slice 3 — Wiring

- [ ] Add `"extensions/edit-clj/src"` to every alias in top-level `deps.edn` that carries `"extensions/github/src"`

- [ ] Add `"extensions/edit-clj/src"` to every `:source-paths` in `tests.edn` that carries `"extensions/github/src"`; add `"extensions/edit-clj/test"` to every `:test-paths` that carries `"extensions/github/test"`

- [ ] Add `'psi/edit-clj` entry to `psi-owned-extension-catalog` in `bases/main/src/psi/launcher/extensions.clj`:
  ```clojure
  'psi/edit-clj
  {:psi/init 'psi.edit-clj.extension/init
   :source-policies
   {:development {:local/root "extensions/edit-clj"}
    :installed   {:local/root "extensions/edit-clj"}}}
  ```

- [ ] Verify: broader suite sample green (extension-invariant test + any launcher catalog test); lint clean on changed files
