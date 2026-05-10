Approach:
- dependency note: this slice should reuse the shared source/reference/projection substrate from task `088` rather than introducing session-local resolution semantics
- treat workflow session contributions as the authored source of truth for child-session conversation assembly
- reuse canonical prompt/session preparation machinery wherever possible instead of inventing a workflow-only message assembly path
- keep first cut narrow: support `:source` and `:template` contributions well before expanding the contribution vocabulary
- share source-ref resolution and template rendering helpers with other workflow execution forms where practical

Likely steps:
1. identify the workflow child-session creation seam where IR `:session` payload should materialize into child-session conversation state
2. define the internal compiled contribution representation, if any, needed between IR and session state
3. resolve `:source` contributions from workflow input/original and prior step outputs/yields
4. render `:template` contributions from explicit string-keyed vars and resolved source specs
5. preserve authored order when materializing conversation inputs/messages
6. thread the resulting conversation state into the canonical child-session prompt/session preparation path
7. add focused tests for source-only, template-only, and mixed contribution scenarios
8. verify that introspection/prepared prompt surfaces stay coherent with the compiled contribution result

Proof target:
- an IR session step's authored contributions become the actual canonical child-session conversation substrate used for execution

Task-artifact note:
- ambiguity/design review follow-ups live in `design-steps.md`
- review notes, discoveries, and blockers append to `implementation.md`

Risks:
- existing workflow child-session code may still assume compatibility-era prompt-template shortcuts
- contribution materialization may accidentally bypass canonical prompt/session preparation if threaded at the wrong seam
- source rendering and template rendering helpers may drift unless shared deliberately
