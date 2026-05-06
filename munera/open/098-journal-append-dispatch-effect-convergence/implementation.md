Task created.

Origin:
- task `097-session-state-component-extraction-from-agent-session` intentionally left `journal-append-in!` as a compatibility seam
- the extracted `session-state` boundary could not depend directly on `agent-session.persistence`
- current implementation delegates through `ctx :journal-append-fn`
- this task exists to converge that seam on the canonical dispatch/effects architecture
