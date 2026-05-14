# Plan — 151 edit-clj structural edit extension

## Approach

Build thin-but-complete vertical slices: pure core first (independently testable), then
extension shell (tool registration + file I/O), then wiring into the top-level project.
Each slice is self-contained and verifiable before the next begins.

---

## Steps

### 1 — Scaffold `extensions/edit-clj/deps.edn`

Create `extensions/edit-clj/deps.edn` with:
- runtime deps: `rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}`,
  `cheshire/cheshire {:mvn/version "5.13.0"}`
- `:test` alias: kaocha + `psi/extension-test-helpers` + `psi/agent-session`
  (same shape as `extensions/github/deps.edn`)

### 2 — Implement `psi.edit-clj.core` (pure)

`extensions/edit-clj/src/psi/edit_clj/core.clj` — no file I/O, takes/returns plain values.

Key functions:
- `parse-single-form` — parse a string via rewrite-clj; return `{:ok node}` or
  `{:error {:code :parse-error :argument <arg-name> :message ...}}`. Errors on
  unparseable input or more than one top-level form.
- `find-candidates` — depth-first zipper walk over a file-content string; collect all
  nodes whose `sexpr` equals the target `sexpr`. Skip nodes where `sexpr` throws
  (comments, whitespace, uneval). Return a vector of `{:node :row :col :text}` maps.
- `apply-line-filter` — given candidates and optional `start-line`/`end-line`, each
  bound independently open when absent: keep candidates where
  `(no start-line OR start-line ≤ node-row) AND (no end-line OR node-row ≤ end-line)`.
  No-op when neither bound is supplied.
- `replace-in` — given already-parsed old-node, new-node, file-content string, and
  filtered candidates, perform the replacement and return the updated file-content
  string. Branches on candidate count: zero → no-match map, many → ambiguous-match map,
  one → replace and return ok map. Never touches the filesystem.

Result maps use keywords and match the design's output shapes **except** that core never
includes `:filename` — core receives file content, not a path. The extension merges
`:filename` (the resolved path string) into every result map before JSON serialisation.
JSON serialisation happens in the extension layer.

Note: `core` exposes composable helpers; it does **not** orchestrate the full
validation order. The extension owns that sequence (see step 4).

### 3 — Tests for `psi.edit-clj.core`

`extensions/edit-clj/test/psi/edit_clj/core_test.clj` — all assertions against
in-memory strings, no temp files.

Cover every AC:
- AC 1 — single match replaced; content outside node unchanged character-for-character
- AC 2 — no-match result; input string returned unchanged
- AC 3 — ambiguous-match result with correct match count and locations
- AC 4 (partial) — `parse-single-form` returns parse-error for invalid Clojure and for
  multi-form input; argument name is preserved in the error map
- AC 6a–f — line-range cases (duplicate forms, straddling forms, nested symbol in
  straddling parent, range with no matching starts)
- AC 7 — `new-string` containing an inline comment; comment text present in output string
- AC 8 — multi-form `old-string` → parse-error; multi-form `new-string` → parse-error

### 4 — Implement `psi.edit-clj.extension`

`extensions/edit-clj/src/psi/edit_clj/extension.clj` — file I/O + tool wiring.

- `resolve-path` — resolve `filename` against `cwd` when relative.
- `execute` — owns the validation order per the design contract:
  1. `core/parse-single-form(old-string, "old-string")` → return error map if invalid
  2. `core/parse-single-form(new-string, "new-string")` → return error map if invalid
  3. Resolve path against `:cwd`; return `file-not-found` map if not readable
  4. Read file content
  5. `core/find-candidates(old-node, file-content)` → `core/apply-line-filter` →
     `core/replace-in(old-node, new-node, file-content, candidates)`
  6. On `:ok` write updated content back to the file
  7. Merge `:filename` (resolved path string) into the result map
  8. Serialise result map to JSON string via cheshire
- `tool-def` — tool map with `:name`, `:description` (≤ 20 words, one-form contract
  explicit), `:parameters` as data map, `:execute` fn supporting both
  `([args])` and `([args opts])` arities.
- `init` — register single tool via `(:register-tool api)`.

### 5 — Tests for `psi.edit-clj.extension`

`extensions/edit-clj/test/psi/edit_clj/extension_test.clj`

- AC 4 (validation order) — both `old-string` and `new-string` invalid → old-string
  parse-error returned; invalid `old-string` with missing file → parse-error returned
  (not file-not-found), confirming the extension checks strings before the file
- AC 5 — non-existent file (both strings valid) → `file-not-found` result (JSON string,
  `"status": "error"`)
- AC 9 — tool `:description` is ≤ 20 words and mentions the one-form contract
- AC 10 — `init` registers exactly one tool named `"edit-clj"` (using
  `ext/create-registry` + `ext/create-extension-api` pattern from `github`'s
  extension test)
- AC 1 (round-trip) — execute writes the modified file and returns `"status": "ok"` JSON

Use a temp file (`java.io.File/createTempFile`) for the round-trip and file-not-found
cases; delete on exit.

### 6 — Wire into top-level `deps.edn`

Add `"extensions/edit-clj/src"` alongside `"extensions/github/src"` in every alias
that already carries it (currently lines ~80, ~118, ~155, ~328 — the `:dev`, `:nrepl`,
`:test`, and other relevant aliases).

### 7 — Wire into top-level `tests.edn`

Add `"extensions/edit-clj/src"` to every `:source-paths` that includes
`"extensions/github/src"`, and `"extensions/edit-clj/test"` to every `:test-paths` that
includes `"extensions/github/test"`.

### 8 — Add to launcher catalog

In `bases/main/src/psi/launcher/extensions.clj`, add to `psi-owned-extension-catalog`:

```clojure
'psi/edit-clj
{:psi/init 'psi.edit-clj.extension/init
 :source-policies
 {:development {:local/root "extensions/edit-clj"}
  :installed   {:local/root "extensions/edit-clj"}}}
```

No `:jar` policy — follows the `github` pattern.

### 9 — Verify

- `clj -M:test --focus psi.edit-clj` — core + extension tests green
- Broader suite sample (launcher catalog test if present, extension-invariant test)
- `clj-kondo --lint extensions/edit-clj/src extensions/edit-clj/test` — lint clean

---

## Risks

- **rewrite-clj `sexpr` on uneval / reader-macro nodes** — some nodes throw rather than
  returning a value. The walk must guard with `try`/`catch` and skip, not propagate.
  Covered by the `skip` branch in `find-candidates`.
- **Multi-form `old-string` detection** — rewrite-clj parses a string as a single-root
  document; detecting more than one top-level form requires checking that the zipper has
  no `right` sibling after the first form at the root level.
- **Comment-in-`new-string` preservation** — easy to accidentally call `coerce` instead
  of using the raw parsed node; AC 7 + the explicit design constraint guard this.
