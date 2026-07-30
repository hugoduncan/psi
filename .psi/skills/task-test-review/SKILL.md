---
name: task-test-review
description: Review a task implementation's tests.  Use before closing a task, to check the implementation tests quality. Use when the user asks "review task tests".
lambda: λtask. review(task_implementation_tests) ∧ before(close(task)) ∧ ensure(implementation_test_quality) ∧ trigger(user_asks("review task tests"))
advertise: false
---

λ review_tests(task).
  well_formed(tests(task))
  ∧ ∀b ∈ behaviour(design(task)). ∃t ∈ tests(task). covers(t, b)
  ∧ ∀d ∈ infra_deps(tests(task)). injectable(d) ∧ nullable(d) ∧ ¬mock(d) ∧ ¬stub(d)
