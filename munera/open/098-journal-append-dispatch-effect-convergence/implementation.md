Task created.

Origin:
- task `097-session-state-component-extraction-from-agent-session` intentionally left `journal-append-in!` as a compatibility seam
- the extracted `session-state` boundary could not depend directly on `agent-session.persistence`
- current implementation delegates through `ctx :journal-append-fn`
- this task exists to converge that seam on the canonical dispatch/effects architecture

2026-05-06 design review — ambiguities / open questions:
- Initial review found several unresolved design choices around the canonical append surface, minimum caller migration set, persistence proof boundary, typed-vs-generic append effects, and verification scope.
- Those ambiguities have since been resolved in `design.md`, `plan.md`, and `steps.md`.

2026-05-06 refinement status:
- authoritative append surface is now the dispatch-owned generic journal-append effect carrying a canonical journal entry
- lower-level `session-state` owns pure in-memory journal mutation only
- higher-level `agent-session` effect execution owns optional persistence side-effects
- minimum production migration set is now explicit: session-lifecycle initial writes, prompt-runtime assistant append, runtime raw user append helper, and extension `append-entry`
- focused verification expectations are now explicit
- `journal-append-in!` / `ctx :journal-append-fn` compatibility ownership seam must be removed by task end

Current review judgment:
- `design.md`, `plan.md`, and `steps.md` are now aligned
- implementation should follow the refined ownership boundary and remove the temporary seam before closure
