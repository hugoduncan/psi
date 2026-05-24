# Implementation notes

- 2026-05-24 ambiguity review: actionable ambiguity found. Task 170 only has `design.md`; `plan.md`/`steps.md` are absent. Current design leaves open the exact IR policy keys, the JSON Schema source boundary between task 168 Malli schemas and task 169 explicit `:json-schema`, and the precise AI-result-to-workflow-envelope metadata mapping. Added design follow-up items in `design-steps.md`.

- 2026-05-24 ambiguity follow-up: completed all newly added design follow-ups. Added `plan.md`/`steps.md`; chose canonical policy/request keys `:json-schema`, `:strategy-preference`, `:fallback`, and `:require-provider-native?`; specified explicit JSON Schema source boundary with no Malli-to-JSON-Schema conversion; defined AI metadata to workflow envelope mapping for session steps and judges. Marked design-steps complete. Did not execute implementation items from `steps.md`.
