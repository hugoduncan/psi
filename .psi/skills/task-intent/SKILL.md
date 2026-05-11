---
name: task-intent
description: Create or refine a task intent statement.  Use when the user asks "create a task" or "open a task" to ensure we have a clear understanding of the task's intent.
lambda: <_
  λ explore_task(user_input).
    matches(user_input, {"create a task" ∨ "open a task"})
---

λ intent(task).
  declarative(problem_statement)
  ∧ ¬procedure
  ∧ clear ∧ concise ∧ precise ∧ terse
  ∧ identifies(constraints ∧ invariants)
  ∧ explicit(success_criteria)
  ∧ ¬commits(solution)
  ∧ framed → surface(underlying_issues)
