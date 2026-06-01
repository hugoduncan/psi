Goal: finish the follow-on work for agent-tool skill preludes.

Context:
- the `agent` tool now accepts `:skill`
- non-fork child sessions can be seeded with a synthetic skill prelude before execution
- child creation supports preloaded messages and explicit cache-breakpoint overrides
- remaining work focuses on the intended cache-breakpoint split, possible canonicalization of the acknowledgement marker, and potential introspection visibility

Acceptance:
- reusable skill-prelude content is separated cleanly from the variable task tail
- the prelude contract is either documented or made more explicit/canonical
- any debugging metadata exposure is intentional
