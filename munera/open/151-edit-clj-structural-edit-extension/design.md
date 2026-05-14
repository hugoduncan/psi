# 151 — edit-clj structural edit extension

## Intent

Add a psi extension that exposes an `edit-clj` tool to the AI agent. The tool performs structural (S-expression) replacement of a single Clojure form inside a source file using `rewrite-clj`, preserving all original formatting outside the replaced node.

The tool is the structural counterpart to the existing text-based `edit` tool: where `edit` matches by exact string, `edit-clj` matches by S-expression equality, ignoring whitespace and formatting.

## Problem

The text-based `edit` tool requires exact whitespace matching and breaks when formatting changes. Structural editing of Clojure files — renaming a binding, swapping a function call, replacing a literal — is a common AI agent operation that deserves a format-preserving, semantics-based matching strategy.

## Scope

**In scope:**
- New extension directory `extensions/edit-clj/`
- Single tool `edit-clj` registered via `psi.extension/register-tool`
- Core logic namespace `psi.edit-clj.core` (pure: zipper walk, match, replace)
- Extension entry point `psi.edit-clj.extension` (calls `register-tool`)
- Unit tests covering all result shapes (ok, file-not-found, parse-error, no-match, ambiguous-match)
- `deps.edn` with `rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}` and `cheshire/cheshire {:mvn/version "5.13.0"}` as runtime deps
- Wire into `extensions/deps.edn` and `extensions/tests.edn`
- Wire into top-level `deps.edn` source paths and top-level `tests.edn` test/source paths
- Add entry to `bases/main/src/psi/launcher/extensions.clj` psi-owned-extension-catalog with init symbol `psi.edit-clj.extension/init`

**Out of scope:**
- Multi-form replacement in a single call
- Scope-aware matching (lexical context disambiguation)
- Dry-run / preview mode
- Integration with nREPL or live evaluation
- EDN-only mode or separate EDN tool
- Formatting / pretty-printing of the replaced node's surrounding context

## Overall Functionality

### Tool: `edit-clj`

**Tool description** (rendered into the agent prompt — terse, one-form contract explicit):

> Replace one Clojure form in a file by S-expression equality; `old-string` and `new-string` must each be one complete, parseable form.

**Parameters** (JSON Schema):
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `filename` | string | yes | Path to the Clojure source file. Relative paths resolve against `:cwd` from opts (session worktree). |
| `old-string` | string | yes | Exactly one parseable Clojure form. Matched by `sexpr` equality. |
| `new-string` | string | yes | Exactly one parseable Clojure form. Replaces the matched node verbatim. |
| `start-line` | integer | no | 1-indexed first line of the match window (inclusive). |
| `end-line` | integer | no | 1-indexed last line of the match window (inclusive). |

**Validation order (explicit contract):** `old-string` is validated first, then `new-string`, then the file is opened. The first error encountered is returned and no further validation is performed. If `old-string` is invalid and `new-string` is also invalid, the `old-string` parse error is returned. If both strings are valid but the file does not exist, `file-not-found` is returned.

**Matching:**
1. Parse `old-string` via `rewrite-clj` zipper; call `.sexpr` to get the target value. Error if the string yields more than one form or is unparseable.
2. Parse `new-string` the same way. Error if unparseable or multi-form.
3. Open the file. Error if not found/unreadable.
4. Parse the **whole file** into a format-preserving zipper with position tracking. The line range is never used to limit the parse input: a file slice may not contain complete forms, and rewrite-clj assigns positions relative to the start of whatever it was given — feeding it a slice would produce wrong positions throughout.
5. Walk depth-first. At each node, skip if `sexpr` is not available (comments, whitespace, uneval nodes). Otherwise compare `sexpr` to target.
6. Optionally filter candidates by line range using the node's **start row** only: a node is in-range when `start-line ≤ node-start-row ≤ end-line`. The node's end row is irrelevant — a form that begins within the range but extends past `end-line` is still considered in-range. A form that ends within the range but begins before `start-line` is excluded.
7. Collect all matches before replacement.
8. Zero matches → `no-match` error; file unchanged.
9. Two or more matches → `ambiguous-match` error with location list; file unchanged.
10. Exactly one match → replace node with parsed `new-string` node; write file.

**Line-range semantics rationale:** Filtering by start row only is the most useful behaviour for the primary disambiguation use case — "there are two identical forms, I want the one near line N." Requiring the entire form to be within the range would silently fail when a large form starts at the target line but extends further, producing a confusing `no-match` even though the right node was identified.

**Output:** JSON object — see result shapes below.

### Result shapes

```
ok              → {status, filename, location {line, column}, old, new}
file-not-found  → {status, code, filename, message}
parse-error     → {status, code, argument, message}
no-match        → {status, code, filename, message, hint}
ambiguous-match → {status, code, filename, match-count, matches [{line, column, text}], message, hint}
```

**Field semantics:**

- `ok.old` — the actual matched node text as it appears in the file (from `rewrite-clj.zip/string` on the matched node), not the `old-string` argument. This reflects the real file content that was replaced, which may differ from the argument in whitespace and formatting.
- `ok.new` — the `new-string` argument string verbatim.
- `ok.location` — the matched node's start position **before** replacement (line/column in the original file). This is the file position the agent used to identify the target; the post-write position is irrelevant for confirmation.
- `no-match.hint` — "Try adding or widening the `start-line`/`end-line` range, or verify that `old-string` appears in the file."
- `ambiguous-match.hint` — "Narrow the `start-line`/`end-line` range to isolate the intended occurrence."

## Architecture alignment

- Extension lives under `extensions/edit-clj/`, following the pattern of `github`, `work-on`, `hello-ext`.
- Core logic in `psi.edit-clj.core` is a pure namespace: takes strings and file content, returns a result map. No I/O — I/O is isolated in `psi.edit-clj.extension` (file read/write).
- The tool's `:execute` fn must support both `([args])` and `([args opts])` arities (consistent with the `work-on` pattern), where `opts` includes `:cwd` (session worktree path). Relative filenames are resolved against `:cwd`. When called with one argument, `:cwd` is treated as absent (relative paths resolve against the process working directory).
- Tool registered via `(:register-tool api)` in `init`, matching the `work-on` pattern.
- No state atom required — tool is stateless.
- Output is a JSON string (cheshire) so it renders cleanly in the agent conversation.

## Structures and patterns

- Follow `github` for `deps.edn` structure and `work-on` for tool registration shape.
- Core logic: `rewrite-clj.zip` for format-preserving zipper; `rewrite-clj.zip/sexpr` for equality; `rewrite-clj.zip/replace` for substitution; `rewrite-clj.zip/root-string` for serializing back to text. The replacement node must be the root node of the format-preserving parse of `new-string` — never reconstructed via `sexpr`/`coerce`, which would silently drop comments and formatting.
- Line-range filtering: use `rewrite-clj.zip/node` → `rewrite-clj.node/start-row` for candidate filtering; end-row is not consulted. rewrite-clj nodes carry `:row`/`:col` position metadata accessible via `rewrite-clj.node/meta`.
- Error short-circuit with early returns; no exceptions for expected error paths.
- Parameters stored as data map (not `pr-str` string) — normalized by `defs/normalize-tool-def`.
- Result serialized to JSON string via `cheshire.core/generate-string` before returning from `:execute`.

## Acceptance criteria

1. `edit-clj` replaces exactly one matching S-expression in a file and writes it back; all whitespace outside the replaced node is byte-for-byte identical.
2. When `old-string` matches zero nodes (or zero nodes in the line range), the file is unchanged and the result is `{:status "error" :code "no-match" ...}`.
3. When `old-string` matches two or more nodes, the file is unchanged and the result is `{:status "error" :code "ambiguous-match" :matches [...] ...}`.
4. Invalid Clojure in `old-string` or `new-string` returns `{:status "error" :code "parse-error" :argument "old-string"|"new-string" ...}`.
5. Non-existent file returns `{:status "error" :code "file-not-found" ...}`.
6. `start-line`/`end-line` constrains matching by node start row. Specific cases:
   - a. Two identical forms in the file; one starts inside the range, the other outside → single match (disambiguation succeeds).
   - b. A form whose start row is within the range but whose end row extends past `end-line` → matched (start-row only rule).
   - c. A form whose end row is within the range but whose start row is before `start-line` → not matched (start-row before range).
   - d. Two identical forms both starting within the range → `ambiguous-match` (range alone cannot disambiguate; `old-string` must be made more specific).
   - e. A valid range that contains no node starts matching `old-string` → `no-match`; file unchanged.
   - f. A small form (e.g. a symbol) nested inside a larger form that straddles the boundary (parent starts before `start-line`) → matched when the symbol's own start row is within the range, because the whole file is parsed and position metadata is file-relative throughout.
7. Comments inside `new-string` are preserved verbatim in the written file. The replacement node is taken directly from the format-preserving parse of `new-string`; it must never be round-tripped through `sexpr`/`coerce`, which would drop comment nodes.
8. Multi-form `old-string` or `new-string` returns a `parse-error`.
9. The tool `:description` is ≤ 20 words and explicitly states that `old-string` and `new-string` must each be exactly one complete Clojure form.
10. The extension `init` registers exactly one tool named `"edit-clj"`.
11. All unit tests pass; lint clean.
