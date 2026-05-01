Initialized from user request on 2026-04-29.

Intent

- Create a small convergence/refactoring task that makes delegated-result publication explicit and canonical without changing current successful `/delegate` behavior.

Starting point

- `/delegate` result delivery is now working end-to-end.
- The recent fix sequence identified one corrected seam (`prompt-execution-result-in!` for bounded callers) and one remaining shaping seam: async delegated completion still mixes policy derivation and side effects inline.

Implementation notes

- Pending.
