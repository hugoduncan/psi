- add skill learning, c.f  https://hermes-agent.nousresearch.com/
- add extension learning
- improve commit detection - head change isn't reliable (rebase, etc) - maybe use reflog as well
- Add a lambda that error messages must be detailed and actionable

- update lambda-fixpoint workflow so that on failure, it summarises the
  idenitifiers and/or structure that is not converging.
  Also reduce the iteration limit to 5
