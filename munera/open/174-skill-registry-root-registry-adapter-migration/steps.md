# Steps

- [ ] Audit current direct `:skills` write seams and classify each as authoritative write to migrate, one-way hydration seed, or legacy projection to remove.
- [ ] Audit current direct `:skills` read seams and classify each as authoritative read to migrate, exact-lookup consumer, display/projection consumer, or pre-hydration legacy seed read.
- [ ] Decide the persistent root-registry skill storage shape: registry id, lower entry id, owner/extension id, and session scoping rules.
- [ ] Implement root-registry-backed skill storage helpers for declaring storage, entry conversion, list-by-session projection, exact lookup, register/insert, replace whole session skill set, hydration from legacy seeds, and optional clear-by-session if needed.
- [ ] Migrate `:session/register-skill` to write root-registry-backed storage, preserve public add/duplicate/change result semantics, and avoid storing session `:skills`.
- [ ] Migrate `:session/set-skills` to replace the session's root-registry-backed skill set and avoid storing session `:skills`.
- [ ] Migrate session creation/defaults, child-session creation, scheduler-created sessions, and resume/hydration paths so input or legacy `:skills` vectors hydrate into root-registry storage and are removed from session data.
- [ ] Migrate resolvers, prompt refresh/build paths, command surfaces, TUI projection/autocomplete, prompt request lookup, and workflow child-session selection away from raw `:skills` reads.
- [ ] Remove any remaining runtime/persistence legacy `:skills` projection writes that are not one-way pre-hydration seed handling.
- [ ] Add focused adapter/storage tests for add, duplicate/no-change, set/replace, unsorted input canonicalization, legacy duplicate names, exact lookup, name listing, count, root-registry ownership/coherence, and absence of session `:skills` projection storage after operations.
- [ ] Add dispatch/hydration tests proving prompt refresh remains gated by semantic `:changed?`, duplicate/no-change does not refresh prompts, legacy session `:skills` hydrate into root-registry storage, and hydrated session data has `:skills` removed.
- [ ] Re-run or update representative task `173` higher-surface tests for prompt/display/TUI/command/workflow canonical ordering.
- [ ] Update `munera/closed/164-registry-semantics-unification-audit/` to record the new `skill-registry` root-registry-backed session-scoped storage classification, removal of legacy session `:skills` projection storage, and migration lesson.
- [ ] Run full `bb test` before close.
