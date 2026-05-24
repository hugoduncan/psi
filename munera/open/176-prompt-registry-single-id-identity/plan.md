# Plan

Resolve the remaining ambiguity in task 176 by making the prompt-contribution targeting contract explicit across concrete caller surfaces, then keep the task artifacts aligned with that refined design.

1. Re-read the preloaded ambiguity-review result and the current task artifacts to isolate exactly which compatibility/targeting ambiguity was added in `design-steps.md`.
2. Inspect the concrete prompt-contribution caller surfaces so the design can distinguish:
   - extension-facing helpers/docs that are already intentionally single-id-only
   - lower-level dispatch and Pathom mutation seams that still accept `ext-path`
   - projection/query surfaces that expose owner provenance without making it identity
3. Update `design.md` so post-change targeting is explicit surface-by-surface:
   - canonical identity remains `id` alone
   - any retained `ext-path` input is provenance/ownership metadata, not identity
   - extension-facing API/doc surfaces stay single-id-only
   - lower-level compatibility, if retained temporarily, is narrow and explicitly bounded
4. Update `steps.md` and `implementation.md` to reflect the completed ambiguity follow-up and preserve alignment with the broader implementation path.

Approach notes:
- This pass executes only newly added ambiguity follow-up items from `design-steps.md`.
- Do not execute implementation items from `steps.md`.
- Prefer one obvious targeting contract over hidden compatibility behavior.
