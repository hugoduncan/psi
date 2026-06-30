# Design follow-up steps

- [ ] ARCHITECTURE: Key Question 3 proposes an `:each` fixture as a safety-net sweep for leaked temp prefixes; `clojure-coding-standards` explicitly disallows `use-fixtures` in this project ("No use-fixtures - Prefer `(with-xxx [args] ...)` macros for setup/teardown"). If a safety-net sweep is pursued, the design must steer plan/steps toward a `with-xxx`-style or explicit-call mechanism instead of `clojure.test/use-fixtures :each`, not toward a fixture.
