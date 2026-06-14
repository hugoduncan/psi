# Design follow-up steps

- [ ] A1: Specify the merged `design-review` step's shared session config explicitly as the union required by the three current review prompt frontmatters (`read`, `bash`, `edit`, `write`; `work-independently`, `review-task-architecture`, `task-design`). Prompt-group `:prompt-workflow` imports only markdown bodies under 226, so relying on the existing per-prompt frontmatter would drop the review skills/tools when the three phases move under one multi-prompt step.
