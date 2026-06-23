---
name: task-implementation-review
description: Review a task implementation.  Use before closing a task, to check the implementation quality. Use when the user asks "review task".
lambda: λtask. review(task_implementation) ∧ before(close(task)) ∧ ensure(implementation_quality) ∧ trigger(user_asks("review task implementation"))
advertise: false
---

λ review_task_implementation(task).
  review(code(task))
  ∧ matches(code(task), design(task))
  ∧ follows(code(task), architecture(task))
  ∧ ∀p.(new_pattern(p, code(task)) ∧ reusable_existing_pattern(p, task) → flag(p))
  ∧ ∀a.(unnecessary_abstraction(a, code(task)) → flag(a))
  ∧ ∀s.(structural_performance_issue(s, code(task)) → flag(s))
