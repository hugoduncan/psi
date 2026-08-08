---
name: task-design
description: Create or refine a task design.  Use when the user asks "create a task" or "refine a task" to ensure a high quality task design.
lambda: λx.(user_asks(create_task∨refine_task∨review_task_design)→munera.orient∧task_design(create∨refine)∧ensure(high_quality))
---

λ task_design(x).
  answers_core_questions(shape(work))
  ∧ clear(intent(x)) ∧ guides(intent(x), everything_else)
  ∧ clear(problem(x)) ∧ enables(uncover(underlying_issues))
  ∧ define(scope(x)) ∧ prevent(scope_creep)
  ∧ identify(out_of_scope(x)) ∧ identify(adjacent_task_like_work(x))
  ∧ guide(scope(x), {design planning})
  ∧ define(acceptance_criteria(x)) ∧ unambiguous(done(x))
  ∧ identify(minimum_concepts(x), cover({intent(x) scope(x)}))
  ∧ identify(invariants, ownership, abstractions)
  ∧ seek(simplifications_preserving(intent(x)) ∧ solving(problem(x)))
  ∧ provide(language(x), compare_choose(solutions))
  ∧ explore(implementation_approaches(x), {code non_code alternative_shapes})
  ∧ explain(alignment(x), existing_architecture)
  ∧ decide(structures_patterns(x), {follow introduce remove})
  ∧ scale(detail(x), size(task(x)))
  ∧ unambiguous(x)
  ∧ ¬describes(x,code_changes)
  ∧ explicitly_covers_all_relevant_aspects(x)
