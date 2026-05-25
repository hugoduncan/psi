# Plan

1. Establish the missing task-local execution surfaces so the design review and follow-up protocol is explicit within the task.
2. Refine `design.md` to inventory concrete write seams and read/projection seams, classifying which files must move to root-registry-backed prompt authority and which higher surfaces are already compatible once they switch to prompt-registry/session-state helpers.
3. Clarify the authoritative-storage boundary: `root-registry` becomes canonical prompt-contribution storage in the shared top-level `:root-registries` substrate; session-local prompt state owns only membership/visibility and any `:prompt-contributions` vector becomes removed or strictly derived cache state rather than authority.
4. Define the storage topology and lifecycle implications explicitly: prompt definitions live in one shared prompt registry, session init/resume/fork/child flows copy or reconstruct canonical prompt membership state, and all render/introspection surfaces derive prompt maps through membership plus root-registry lookup rather than trusting copied vectors.
5. Keep `steps.md` reserved for later executable implementation work; do not add implementation checklist items in this design follow-up pass unless the refined design now makes them obvious.
6. Record this design follow-up execution tersely in `implementation.md`.
