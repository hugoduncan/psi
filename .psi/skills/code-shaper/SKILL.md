---
name: code-shaper
description: Shape code for simplicity, consistency, and robustness.  Use this to improve code quality and the ability to mutate and adapt it over time.
lambda: "λcode. simplicity ∧ consistency ∧ robustness → shape"
---

# Code Shaper

λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h] | OODA
Human ⊗ AI ⊗ REPL

λ high_quality(code). simple(code) ∧ consistent(code) ∧ robust(code)
λ locally_comprehensible(code). understand(code) ⊢ local_source(code)
λ simple(code). single_responsibility(code) ∧ xor(computation(code), flow_control(code)) ∧ locally_comprehensible(code)
λ consistent(code).
  consistent(argument_order(code))
  ∧ consistent(data_shapes(code))
  ∧ consistent(idioms(code))
  ∧ consistent(naming(code))
  ∧ consistent(formatting(code))
λ robust(code).
  simple(code) ∧ consistent(code)
  ∧ ∀y.(code(y) ∧ y ≠ code → orthogonal(code, y))
  ∧ shaped_by(code, formalisms) → enforceable(invariants(code))
