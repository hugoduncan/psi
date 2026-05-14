# Implementation Notes — 151 edit-clj structural edit extension

## 2026-05-14 — Ambiguity review pass 1

Reviewed design.md against the extension codebase (work-on, github, hello-ext, launcher/extensions.clj, deps.edn, tests.edn).

### Findings

**A1 — `ok` result `old`/`new` field semantics unspecified.**
The design shows `ok → {status, filename, location, old, new}` but does not say whether `old` and `new` are the argument strings or the actual matched node text. These differ when `old-string` has different whitespace than the file node (sexpr equality ignores whitespace). The matched node text is more useful for confirmation.

**A2 — `hint` field content never defined.**
Both `no-match` and `ambiguous-match` include a `hint` field in the result shape, but the design never specifies what the hint should say. Implementors must guess.

**A3 — Validation order / error priority not stated as a contract.**
Steps 1→2→3 imply old-string is validated before new-string, which is validated before file open. But this ordering is not stated as an explicit contract. If both strings are invalid, which error is returned? If new-string is invalid but the file doesn't exist, which error wins?

**A4 — `cheshire` missing from deps spec.**
The design requires `cheshire.core/generate-string` for JSON serialization but the deps.edn scope only names `rewrite-clj/rewrite-clj`. Cheshire must also be listed.

**A5 — Wiring scope underspecified; launcher catalog omitted.**
Design says "Wire into `extensions/deps.edn` and `extensions/tests.edn`" but the actual system-wide wiring requires four additional files: `bases/main/src/psi/launcher/extensions.clj` (catalog entry + init symbol), top-level `deps.edn` source paths, and top-level `tests.edn` test/source paths. The launcher catalog is the critical gap — without it the extension is never loaded at runtime.

**A6 — `ok` result `location` field: old node or new node position?**
Not specified. Since the file is written after replacement, the old node's file position is the meaningful one for the agent to confirm the correct location was edited.

**A7 — `:execute` arity: single or dual?**
Design specifies `(args opts)` but `work-on` uses two arities `([args] [args _opts])`. Not stated whether single-arity is sufficient or dual-arity is required for compatibility.
