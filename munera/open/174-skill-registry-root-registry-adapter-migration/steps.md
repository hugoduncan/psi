# Steps

- [ ] Audit current direct `:skills` write seams and classify each as authoritative write to migrate, one-way hydration seed, or legacy embedded projection to remove.
- [ ] Audit current direct `:skills` read seams and classify each as authoritative read to migrate, exact-lookup consumer, display/projection consumer, or pre-hydration legacy seed read.
- [ ] Audit child-session inheritance and any other membership-copy seams that should become `:skill-ids`-based.
- [ ] Decide the persistent storage split: root-registry definition registry id, lower owner/extension id convention, and canonical session membership field shape (`:skill-ids` or justified equivalent).
- [ ] Implement root-registry-backed definition storage helpers plus session-membership helpers for declaring storage, definition conversion, exact lookup, register/insert, projected list-by-session, `skill-ids` access, replace whole session membership, and hydration from legacy embedded skill vectors.
- [ ] Update session schema/model to add canonical `:skill-ids` and remove embedded `:skills` from canonical runtime/persisted session data.
- [ ] Migrate `:session/register-skill` to ensure root-registry definitions, update session `:skill-ids`, preserve public add/duplicate/change result semantics, and avoid storing session `:skills`.
- [ ] Migrate `:session/set-skills` to replace the session's `:skill-ids` membership from supplied skill maps and avoid storing session `:skills`.
- [ ] Migrate session creation/defaults, child-session creation, scheduler-created sessions, and resume/hydration paths so input or legacy embedded `:skills` vectors hydrate into root-registry definitions plus `:skill-ids`, and are removed from session data.
- [ ] Migrate resolvers, prompt refresh/build paths, command surfaces, TUI projection/autocomplete, prompt request lookup, and workflow child-session selection away from raw `:skills` reads and onto `:skill-ids` + registry lookup.
- [ ] Remove any remaining runtime/persistence embedded `:skills` projection writes that are not one-way pre-hydration seed handling.
- [ ] Add focused adapter/storage tests for definition add/duplicate, membership add/duplicate/set, unsorted input canonicalization, legacy duplicate names, exact lookup, `skill-ids` access, projected name listing/count, root-registry ownership/coherence, and absence of embedded session `:skills` after operations.
- [ ] Add dispatch/hydration tests proving prompt refresh remains gated by semantic membership `:changed?`, duplicate/no-change does not refresh prompts, legacy embedded `:skills` hydrate into root-registry definitions plus `:skill-ids`, and hydrated session data has embedded `:skills` removed.
- [ ] Re-run or update representative task `173` higher-surface tests for prompt/display/TUI/command/workflow canonical ordering and child-session inheritance via `:skill-ids`.
- [ ] Update `munera/closed/164-registry-semantics-unification-audit/` to record the new `skill-registry` classification, session-owned `:skill-ids`, and removal of embedded session `:skills` storage.
- [ ] Run full `bb test` before close.
