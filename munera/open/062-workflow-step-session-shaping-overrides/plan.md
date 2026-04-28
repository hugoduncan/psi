# 062 — Plan

1. Define the authoring shape for per-step session overrides as peer keys in the existing `:session` map alongside task-060/task-061 source/projection entries.
2. Route those fields through `workflow_step_prep.clj`.
3. Preserve default inheritance when overrides are absent, while treating explicit empty tool/skill collections as meaningful replacement overrides.
4. Validate malformed override values and unsupported `:session` keys for the implemented `060`–`062` surface.
5. Add focused tests for override semantics.
6. Run focused workflow tests.
