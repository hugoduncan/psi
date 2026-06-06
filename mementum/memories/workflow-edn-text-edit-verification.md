💡 Editing a workflow's emitted prompt means editing a large string value (`:text`)
inside `.psi/workflows/*.edn` — NOT structural Clojure. When the edit replaces a
contiguous span *inside* that string, `clj-paren-repair` is the wrong gate (it repairs
delimiters, but the delimiters were never touched). The correct well-formedness gate is
an `edn/read-string` round-trip of the file (or the `:text` value): if it reads back,
the EDN is intact.

Seen in task 215 (`reduce-incidental-complexity.edn` `select-and-create` step, a
31765-char `:text`): substituted `bb edn/read-string` round-trip for the steps' literal
"run clj-paren-repair" — the actual EDN-well-formedness check the plan's risk bullet
wanted.

🔁 Rule of thumb: paren-repair for structural code edits; `edn/read-string` round-trip
for in-string `:text` span edits in workflow/config EDN.
