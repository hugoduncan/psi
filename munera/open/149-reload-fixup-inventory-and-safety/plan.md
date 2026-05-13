# Plan

## Approach

1. Inspect the current `reload-code` implementation and document the existing post-reload fixup path, including all current namespace- or surface-specific repair logic.
2. Build an inventory of long-lived runtime in-memory surfaces that can retain stale references across namespace reload, classifying each by owner, symptom, and severity.
3. Reproduce or reason through the reload-breaking cases to identify the minimum set of missing mandatory fixups.
4. Implement focused fixups through canonical owners for every case classified `breaks-psi`.
5. Add focused proof for the mandatory safety cases and update concise developer guidance for future reload-sensitive surfaces.

## Decisions to make during implementation

- whether the current imperative fixup path remains the clearest shape once the inventory is complete
- whether a narrow data-driven fixup table improves clarity without obscuring critical repair behavior
- what inventory artifact belongs in task-local docs vs code comments vs tests

## Risks

- some stale-reference failures may only manifest through live runtime composition and be hard to reproduce in narrow tests
- the true owner of a stale assembled value may be higher-level than the namespace being reloaded, requiring careful rebuild boundaries
- over-generalizing too early could make the reload path harder to review than explicit targeted fixups

## Verification

- focused tests around the reload fixup path and known mandatory cases
- targeted live reload checks for at least the namespaces/surfaces that were previously reload-breaking
- lint / relevant focused test suites for touched namespaces
