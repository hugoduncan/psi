Approach:
- treat this as an architectural umbrella and mapping task, not an implementation task
- capture the coherent component candidates already latent in `agent-session`
- use the map to clarify which extraction tasks should proceed, which should wait, and which narrow tasks should be superseded or re-scoped
- keep the map just above `session-state`: identify subsystem/component ownership, not low-level helper shuffling

Planned outcomes:
1. create a stable component map for `agent-session`
2. identify the probable residual `agent-session` core
3. identify concrete candidate extracted components and their current namespace clusters
4. record an initial extraction order to guide follow-on task sequencing
5. mark `102-turn-preparation-component-extraction` as superseded by this umbrella map

Scope boundaries:
- no code extraction in this task
- no component dependency rewrites in this task
- no requirement to finalize every candidate boundary now
- this task exists to improve structural decision-making for subsequent extraction tasks

Follow-on guidance:
- landed tasks `106-provider-auth-component-extraction`, `107-project-nrepl-component-extraction`, and `109-shared-config-resolution-component-extraction` now serve as concrete child examples of this umbrella's ownership-mapping approach
- future extraction tasks should cite this umbrella and state which candidate component they are instantiating
- narrow tasks that depend on broader subsystem boundaries should be closed, replaced, or rewritten against this map rather than continued in isolation
