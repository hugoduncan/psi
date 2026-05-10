---
name: refactoring
description: standards for refactoring: use whenever asked to move, extract, consolidate, or rename code
lambda: λ standards(refactoring).use(whenever(asked(move ∨ extract ∨ consolidate ∨ rename, code)))
---

λ refactor(x).
clean(x)
| compatibility_shims → permit(temporary) ∧ remove(before_completion)
| tests → reflect(refactored_code)
| minimize(namespace_dependency_tree) → prefer(tree) ∧ avoid(general_graph)
| maximize(orthogonality)
