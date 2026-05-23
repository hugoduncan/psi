# Implementation

Task created to plan a root-registry migration for `skill-registry`.

Initial design decision was helper-level alignment only, but the user clarified that the desired direction is a real storage move: root-registry should become authoritative for registered session skills.

The user then further clarified that the migration must remove legacy session `:skills` projection storage. Compatibility should be read-time/API projection from root-registry and one-way hydration from legacy/input seeds only, not synchronized duplicate storage.

The user then further clarified the desired ownership boundary:

- registry owns skill definitions
- session owns which skills it includes, but only by reference

The user then clarified that we do not need a legacy hydration path.

The user then clarified a further ownership split for startup:

- bootstrap must hydrate the skill registry directly
- bootstrap must not go through any session-owned membership path

Current target:

- move canonical skill definitions to root-registry
- keep session ownership of membership via `:skill-ids`
- bootstrap/root-runtime initialization loads skill definitions directly into root-registry before sessions exist
- preserve public task `173` behavior: exact lookup, duplicate-ignore/first-write-wins, `:added?` / `:changed?`, and canonical exact skill-name ordering
- remove embedded `:skills` from runtime/persisted session data
- migrate all higher read/projection seams away from raw `:skills` reads onto session `:skill-ids` plus registry lookup
- do not retain a legacy embedded-`:skills` hydration compatibility path in this task

Important design pressure:

Many code paths currently seed, copy, or read embedded `:skills` directly: bootstrap/session defaults, child sessions, scheduler sessions, prompt refresh, prompt request lookup, discovery/session resolvers, commands, TUI, workflow step session config, and tests. The implementation must inventory these seams before changing storage so task `168`'s stale-projection failure pattern does not repeat. Because embedded `:skills` storage is being removed rather than synchronized, every remaining raw `:skills` access must be either eliminated or replaced with `:skill-ids` + registry lookup, and child-session plus related inheritance paths must become `:skill-ids`-driven. Bootstrap skill loading must be audited separately so definition hydration happens before any session creation path consumes skill ids.

2026-05-22 ambiguity review: actionable ambiguity — design/plan say bootstrap hydrates root-registry definitions before sessions exist and that later session creation only writes `:skill-ids`, but they do not yet make the non-bootstrap contract explicit for session-creation paths still supplied concrete skill maps when some definitions are missing from root-registry (same-update registration vs reject vs require prior normalization).

2026-05-22 ambiguity follow-up execution: resolved the remaining non-bootstrap skill-definition normalization contract after re-reading current scheduler, workflow child-session, child-session mutation, bootstrap, lifecycle, and session-state seams. Design/plan now specify that bootstrap should pre-register startup/default skills, but non-bootstrap session-creation and membership-replacement paths that are supplied concrete skill maps must synchronously ensure any missing root-registry definitions inside the same root-state update that persists canonical session `:skill-ids`; they must not reject valid unregistered maps or require a follow-up hydration dispatch/effect. Name-only selection paths remain exact-lookup/parent-membership based rather than inventing placeholder persisted definitions.

2026-05-22 inconsistency review: actionable inconsistency — design/plan require bootstrap to hydrate skill definitions directly into root-registry before sessions exist and to stop routing startup skill loading through session-owned membership APIs, but `steps.md` does not yet contain an explicit follow-up to replace the current `agent_session/bootstrap.clj` `:session/register-skill` path with the direct bootstrap definition-loading path.

2026-05-22 inconsistency follow-up execution: completed the new design-step by re-reading `components/agent-session/src/psi/agent_session/bootstrap.clj`, confirming startup still loops through `:session/register-skill`, and adding an explicit nested `steps.md` implementation item under bootstrap/root-runtime initialization to replace that path with direct root-registry skill-definition hydration before any session membership is created.

2026-05-22 implementation pass: completed the first concrete migration slice. Added `psi.skill-registry.root-storage` as the root-registry-backed skill-definition/session-membership adapter with canonical `:skills` registry id, stable owner `:psi.skill-registry/definitions`, and session-owned `:skill-ids`. Updated session model/init to treat `:skill-ids` as canonical session membership, migrated bootstrap startup skill hydration to direct root-state registration via the adapter, migrated `:session/register-skill` and `:session/set-skills` off embedded session `:skills` writes, and switched session/discovery resolver skill reads onto projected `:skill-ids` + root-registry lookup. Added focused adapter tests covering definition storage, membership replacement, duplicate no-op semantics, and absence of embedded session `:skills`. Further read/lifecycle surfaces still need migration before the task is complete.

2026-05-22 implementation pass: started a broader lifecycle/read-surface migration slice (child-session state, workflow step session config, prompt-refresh rebuild, prompt-request skill expansion, scheduler summaries). Prompt-refresh, prompt-request, workflow step session config, and scheduler summary seams were migrated to root-registry-backed skill resolution; the child-session lifecycle migration exposed wider assumptions that still require coordinated updates across create-child/session tests and higher command/TUI/introspection surfaces, so that part was intentionally backed out in this pass to keep the tree coherent.

2026-05-22 implementation pass: retried the child-session lifecycle slice. `child_session_state` now persists canonical child `:skill-ids` and derives prompt-visible skill vectors from root-registry-backed parent/session definitions while rebuilding prompts. Focused unit expectations were updated for lower `session-state` inheritance and workflow-step test setup to seed root-registry definitions instead of raw embedded session `:skills`. This slice is not yet complete: broader runtime/test seams still assume embedded `:skills` storage (notably command/help surfaces, prompt-request `/skill:` expansion, resolver/introspection skill queries, startup-summary/dispatch expectations, and some scheduler/workflow child-session paths), so the task remains in-progress.

2026-05-23 implementation pass: pushed the migration deeper into child/session test scaffolding and lifecycle expectations. Test support now normalizes test-only seed `:skills` inputs into canonical session `:skill-ids`, child-session state no longer persists a derived `:skills` vector, and several workflow/scheduler/config tests now assert `:skill-ids` ownership plus absence of embedded `:skills`. Focused verification still exposes unresolved seams: some create-child/workflow/scheduler setups seed no root-registry skill definitions before expecting projected skills, commands/help tests still rely on `make-test-ctx` skill seeding that does not yet populate root-registry-backed definitions, and a few prompt refresh / register-skill expectations still assume embedded-session skill storage and pre-migration refresh timing. The tree is improved but this slice is incomplete and not ready to close.

2026-05-23 implementation pass: added root-registry skill-definition seeding to `agent-session` test support's minimal session context so tests that provide session-default `:skills` now create matching canonical root-registry definitions alongside `:skill-ids`. This directly addresses one verified fallout seam for command/resolver/child-session projections. Verification also showed broader unresolved migration fallout remains across prompt-assets skill discovery/introspection tests and several command/prompt/workflow surfaces, so the pass stayed intentionally narrow.

2026-05-23 implementation pass: hardened minimal `agent-session` test support again so tests that seed only canonical `:skill-ids` also synthesize placeholder root-registry definitions. This removes one easy-to-miss failure mode where projected command/resolver/prompt skill reads silently became empty despite canonical membership being present. While validating that slice, I also corrected one outdated prompt-request test to the current `build-prepared-request` API (`:user-message` rather than removed ad hoc keys). Focused verification still shows broader migration fallout remains in discovery/introspection, prompt refresh, workflow child-session selection, and scheduler/session-config surfaces, so the task remains open.

2026-05-23 implementation pass: executed a verification-first lifecycle follow-up rather than another broad code change. Re-read the create-child / scheduler / workflow / resolver / command / TUI seams that were previously called out as likely embedded-`:skills` fallout, then ran the representative focused tests covering child-session mutation/state, scheduler-created top-level sessions, workflow child-session selection, `/skills` and `/help` command ordering, resolver skill projection, prompt-request `/skill:` expansion, and TUI skill banner/autocomplete ordering. Those focused checks passed with canonical `:skill-ids` ownership plus root-registry-backed test seeding, so this pass marks that representative child/scheduler/workflow verification item complete. Remaining open work is the broader cleanup/inventory of any still-unmigrated raw `:skills` runtime or persistence seams and the full task-close verification/update steps.
