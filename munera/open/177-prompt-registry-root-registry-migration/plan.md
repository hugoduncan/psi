# Plan

1. Establish the missing task-local execution surfaces so the design review and follow-up protocol is explicit within the task.
2. Refine `design.md` to inventory concrete write seams and read/projection seams, classifying which files must move to root-registry-backed prompt authority and which higher surfaces are already compatible once they switch to prompt-registry/session-state helpers.
3. Clarify the authoritative-storage boundary: `root-registry` becomes canonical per-session prompt-contribution storage; session `:prompt-contributions` vectors stop being independently authoritative and become either removed or strictly derived caches owned by the prompt-registry/session-state adapter during migration.
4. Keep `steps.md` reserved for later executable implementation work; do not add implementation checklist items in this ambiguity follow-up pass unless the refined design now makes them obvious.
5. Record this ambiguity follow-up execution tersely in `implementation.md`.
