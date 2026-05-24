# Design follow-up steps

- [x] Specify the exact Anthropic JSON Schema structured-output request shape and header/beta names to implement and assert in tests, rather than referring only to the documented output-format/header.
- [x] Specify the exact Anthropic JSON Schema response and streaming event shapes to extract from, including how raw provider content is preserved and how parse failures surface on `:structured-output`.
- [x] Define the concrete built-in Anthropic model-key to native-mechanism assignment for the current catalog in `components/ai/src/psi/ai/models.clj`, including which ids remain forced-tool-only, fallback-only, or unsupported.
- [x] Define the request/model policy for choosing `:anthropic/json-schema-output` versus `:anthropic/forced-tool-use` when both are available or when a caller needs the compatibility forced-tool path.
- [x] Specify the live Anthropic OAuth smoke test invocation, credential discovery/skip behavior, expected model id, and non-secret result note format.
- [x] Create `plan.md` and `steps.md` before implementation so review can verify sequencing, test coverage, and documentation updates against the refined design.
