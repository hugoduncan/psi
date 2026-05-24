# Design follow-up steps

- [x] Create `plan.md` and `steps.md` before implementation so the approach, target files, sequencing, verification commands, and risks are explicit and reviewable.
- [x] Choose the concrete structured-output contract shape for both authored workflow definitions and normalized IR, explicitly reconciling the design's conceptual `:output {:mode :structured ...}` examples with the existing `:outputs` step-local output surface and `:name`-based IR shape.
- [x] Specify the first standard schema/example to land in this slice (for example, workflow judge review result vs bug reproduction classification), including whether it is test-only or migrates one existing workflow.
- [ ] Align `doc/workflow-grammar.md` with the chosen contract by adding session-step and LLM-judge `outputs?` grammar support and expanding `outputs` beyond delegate handoff, so the authored grammar no longer contradicts the design/plan examples.
- [ ] Specify the structured-output wire format and coercion rules for prompted fallback, especially how JSON/EDN parsed values map to Malli keyword enums before validation and what is recorded when coercion fails.
