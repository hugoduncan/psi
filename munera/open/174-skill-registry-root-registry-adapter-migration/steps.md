# Steps

- [ ] Audit current direct `:skills` write seams and classify each as authoritative write, hydration seed, or compatibility projection.
- [ ] Audit current direct `:skills` read seams and classify each as authoritative read to migrate, exact-lookup consumer, display/projection consumer, or legacy compatibility seed.
- [ ] Decide the persistent root-registry skill storage shape: registry id, lower entry id, owner/extension id, and session scoping rules.
- [ ] Implement root-registry-backed skill storage helpers for declaring storage, entry conversion, list-by-session projection, exact lookup, register/insert, replace whole session skill set, and optional clear-by-session if needed.
- [ ] Migrate `:session/register-skill` to write root-registry-backed storage and preserve public add/duplicate/change result semantics.
- [ ] Migrate `:session/set-skills` to replace the session's root-registry-backed skill set and define whether/how compatibility `:skills` projection is written.
- [ ] Migrate session creation/defaults, child-session creation, scheduler-created sessions, and resume/hydration paths that seed `:skills` into the root-registry-backed storage.
- [ ] Migrate resolvers, prompt refresh/build paths, command surfaces, TUI projection/autocomplete, prompt request lookup, and workflow child-session selection away from authoritative raw `:skills` reads.
- [ ] Add focused adapter/storage tests for add, duplicate/no-change, set/replace, unsorted input canonicalization, legacy duplicate names, exact lookup, name listing, count, and root-registry ownership/coherence.
- [ ] Add dispatch/hydration tests proving prompt refresh remains gated by semantic `:changed?`, duplicate/no-change canonicalization does not refresh prompts, and legacy session `:skills` hydrate into root-registry storage.
- [ ] Re-run or update representative task `173` higher-surface tests for prompt/display/TUI/command/workflow canonical ordering.
- [ ] Update `munera/closed/164-registry-semantics-unification-audit/` to record the new `skill-registry` root-registry-backed session-scoped storage classification and migration lesson.
- [ ] Run full `bb test` before close.
