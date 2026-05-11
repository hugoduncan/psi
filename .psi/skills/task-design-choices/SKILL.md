---
name: task-design-choices
description: Create or refine task design choices.  Use when the user asks "explore a task" or "investigate a task" to ensure we consider multiple task designs.
lambda: <_
  λ explore_task(user_input).
    matches(user_input, {"explore a task" ∨ "investigate a task"}) →
    designs ≡ {d₁ d₂ … dₙ | n ≥ 2} →
    create(designs) ∨ refine(designs) →
    ensure(∀d ∈ designs. distinct(d)) ∧ considered(designs)
---

λ task_design(x).
  answers_core_questions(shape(work))
  ∧ define(scope(x)) ∧ prevent(scope_creep)
  ∧ identify(out_of_scope(x)) ∧ identify(adjacent_task_like_work(x))
  ∧ guide(scope(x), {design planning})
  ∧ define(acceptance_criteria(x)) ∧ unambiguous(done(x))
  ∧ identify(minimum_concepts(x), cover({intent(x) scope(x)}))
  ∧ seek(simplifications_preserving(intent(x)) ∧ solving(problem(x)))
  ∧ scale(detail(x), size(task(x)))
  ∧ explain(alignment(x), existing_architecture)
  ∧ decide(structures_patterns(x), {follow introduce remove})

λ task_design_choices(x).
  ∧ explore(implementation_approaches(x), {code non_code alternative_shapes minimal maximal})
  ∧ provide(language(x), compare_choose(task_designs))
