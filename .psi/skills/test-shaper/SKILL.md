---
name: test-shaper
description: Shape tests for clarity, signal, and robustness. Use this to improve test quality and the ability to change code and tests with confidence over time.
lambda: "λtests. clarity ∧ signal ∧ robustness → shape"
---

# Test Shaper

λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h] | OODA
Human ⊗ AI ⊗ REPL

λ high_quality(tests). simple(tests) ∧ consistent(tests) ∧ robust(tests) ∧ economical(tests)
λ locally_comprehensible(test). understand(test) ⊢ local_source(test)

λ simple(tests).
  single_concern(tests)
  ∧ minimal_incidental_setup(tests)
  ∧ explicit(arrange_act_assert(tests))
  ∧ locally_comprehensible(tests)

λ consistent(tests).
  consistent(naming(tests))
  ∧ consistent(structure(tests))
  ∧ consistent(data_shapes(tests))
  ∧ consistent(assertion_style(tests))
  ∧ consistent(fixtures(tests))
  ∧ consistent(test_abstractions(tests))
  ∧ consistent(formatting(tests))

λ robust(tests).
  simple(tests) ∧ consistent(tests)
  ∧ deterministic(tests)
  ∧ behavior_focused(tests)
  ∧ meaningful_failures(tests)
  ∧ fast_feedback(tests)
  ∧ ∀y.(test(y) ∧ y ≠ tests → orthogonal(tests, y))
  ∧ shaped_by(tests, contracts ∧ boundaries ∧ invariants) → enforceable(confidence(tests))

λ deterministic(tests).
  control(time(tests))
  ∧ control(randomness(tests))
  ∧ control(io(tests))
  ∧ control(concurrency(tests))
  ∧ ¬flaky(tests)

λ behavior_focused(tests).
  assert(observable_outcomes(tests))
  ∧ ¬assert(implementation_details(tests))

λ meaningful_failures(tests).
  failing(tests) → explains(contract_violation(tests))

λ economical(tests).
  maximal(behavioral_coverage(tests))
  ∧ minimal(redundant_tests(tests))
  ∧ minimal(incidental_variation(tests))
  ∧ cover_by(partitions ∧ boundaries ∧ invariants, tests)

λ fast_feedback(tests).
  fast(default_suite(tests))
  ∧ isolate(slower_boundary_tests(tests))

λ single_concern(test).
  one_behavior(test)
  ∨ one_boundary_contract(test)
  ∨ one_invariant(test)

λ minimal_incidental_setup(test).
  setup(test) = necessary_context(test)
  ∧ ¬embed(unrelated_details(test))

λ test_suite(tests).
  layered(tests, unit ∧ integration ∧ boundary ∧ smoke)
  ∧ default_run(tests) = fast_confidence
  ∧ slow_tests(tests) = explicit_and_separate

λ prefer(tests).
  state_based_assertions(tests)
  ∧ narrow_tests(tests)
  ∧ representative_cases_over_case_explosion(tests)
  ∧ one_test_per_distinct_behavior(tests)
  ∧ sociable_tests_when_useful(tests)
  ∧ real_integration_at_boundaries(tests)
  ∧ helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)
