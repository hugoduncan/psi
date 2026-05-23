# Steps

- [ ] Audit current direct `:skills` write seams and classify each as authoritative write to remove or normalize into canonical `:skill-ids`-based writes.
- [ ] Audit current direct `:skills` read seams and classify each as authoritative read to migrate, exact-lookup consumer, display/projection consumer, or dead legacy read to remove.
- [ ] Audit child-session inheritance and any other membership-copy seams that should become `:skill-ids`-based.
- [ ] Decide the persistent storage split: root-registry definition registry id, lower owner/extension id convention, and canonical session membership field shape (`:skill-ids` or justified equivalent).
- [ ] Implement root-registry-backed definition storage helpers plus session-membership helpers for declaring storage, definition conversion, exact lookup, register/insert, projected list-by-session, `skill-ids` access, and replace whole session membership.
- [ ] Update session schema/model to add canonical `:skill-ids` and remove embedded `:skills` from canonical runtime/persisted session data.
- [ ] Migrate `:session/register-skill` to ensure root-registry definitions, update session `:skill-ids`, preserve public add/duplicate/change result semantics, and avoid storing session `:skills`.
- [ ] Migrate `:session/set-skills` to replace the session's `:skill-ids` membership from supplied skill maps and avoid storing session `:skills`.
- [ ] Migrate session creation/defaults, child-session creation, scheduler-created sessions, and resume paths to use canonical `:skill-ids` directly or normalize supplied skill maps immediately into definitions plus `:skill-ids`.
- [ ] Migrate resolvers, prompt refresh/build paths, command surfaces, TUI projection/autocomplete, prompt request lookup, and workflow child-session selection away from raw `:skills` reads and onto `:skill-ids` + registry lookup.
- [ ] Remove any remaining runtime/persistence embedded `:skills` writes and reads.
- [ ] Add focused adapter/storage tests for definition add/duplicate, membership add/duplicate/set, exact lookup, `skill-ids` access, projected name listing/count, root-registry ownership/coherence, and absence of embedded session `:skills` after operations.
- [ ] Add dispatch tests proving prompt refresh remains gated by semantic membership `:changed?`, duplicate/no-change does not refresh prompts, and canonical session data uses `:skill-ids` rather than embedded `:skills`.
- [ ] Re-run or update representative task `173` higher-surface tests for prompt/display/TUI/command/workflow canonical ordering and child-session inheritance via `:skill-ids`.
- [ ] Update `munera/closed/164-registry-semantics-unification-audit/` to record the new `skill-registry` classification, session-owned `:skill-ids`, and removal of embedded session `:skills` storage without a legacy hydration path.
- [ ] Run full `bb test` before close.
