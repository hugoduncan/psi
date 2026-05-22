# Design follow-up steps

- [ ] Make the review/follow-up surface explicit in the task artifacts: update `design.md` and/or `plan.md` so `implementation.md` is the append-only review/decision log and `design-steps.md` is the actionable ambiguity follow-up surface.
- [ ] Define the canonical skill-name ordering precisely enough for implementation and tests, including comparator choice, case sensitivity, locale dependence, and how names that differ only by case should appear.
- [ ] Clarify whether canonical ordering applies only to registry query/read helpers (`all-skills`, `skill-names`, prompt/discovery projections) or also to the stored/session `:skills` vector and `register-skill` result `:skills` after each registration.
- [ ] Enumerate the prompt/display/introspection surfaces that must inherit canonical ordering, because several current callers consume raw `:skills` vectors rather than `skill-registry/all-skills`.
- [ ] Specify the expected task `164` update scope: which current conclusion rows/text should change versus which historical audit notes should remain as prior evidence.
- [ ] Add an explicit keep-order branch to `plan.md` / `steps.md`: if the audit finds a real insertion-order dependency, specify that no ordering-removal code change is made, the dependency is documented/test-backed, and task `164` is updated only to record the confirmed requirement.
