# Design follow-up steps

- [x] Specify the exact Anthropic JSON Schema structured-output request shape and header/beta names to implement and assert in tests, rather than referring only to the documented output-format/header.
- [x] Specify the exact Anthropic JSON Schema response and streaming event shapes to extract from, including how raw provider content is preserved and how parse failures surface on `:structured-output`.
- [x] Define the concrete built-in Anthropic model-key to native-mechanism assignment for the current catalog in `components/ai/src/psi/ai/models.clj`, including which ids remain forced-tool-only, fallback-only, or unsupported.
- [x] Define the request/model policy for choosing `:anthropic/json-schema-output` versus `:anthropic/forced-tool-use` when both are available or when a caller needs the compatibility forced-tool path.
- [x] Specify the live Anthropic OAuth smoke test invocation, credential discovery/skip behavior, expected model id, and non-secret result note format.
- [x] Create `plan.md` and `steps.md` before implementation so review can verify sequencing, test coverage, and documentation updates against the refined design.
- [x] Name the exact documentation files/sections task 171 must update, and the exact task 170 dependency wording to change, so the documentation step is verifiable without guessing what counts as "AI documentation" or "task 170 dependency text".
- [x] Specify the exact live-smoke credential seam and invocation: whether the opt-in test calls the Anthropic provider with `ANTHROPIC_API_KEY`/`:api-key`, exercises an existing OAuth resolver path, or records OAuth as unavailable when no concrete test seam exists.
- [x] Align acceptance criterion 11 with the refined live-smoke credential seam: require guarded Anthropic JSON Schema native smoke verification through provider `:api-key`/`ANTHROPIC_API_KEY`, with OAuth only when supplied through that same seam, rather than naming OAuth smoke as the sole acceptance requirement.
