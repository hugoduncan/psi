2026-05-07

Task created after reviewing the current shared config dependency picture.

Initial findings motivating the extraction:
- current downward tree under `psi.agent-session.config-resolution` is already small and component-like:
  - `psi.agent-session.config-resolution`
    - `psi.agent-session.project-preferences`
    - `psi.agent-session.user-config`
- the stronger architectural signal is upward/sideways duplication rather than dependency depth
- `psi.project-nrepl.config` currently duplicates:
  - user config file lookup
  - project shared/local config file lookup
  - EDN best-effort read policy
  - layered merge behavior
  - malformed-file warning/fallback behavior
- that duplication exists because the extracted `project-nrepl` component correctly avoided depending upward on `agent-session`
- `psi.app-runtime` is the current main direct consumer of `psi.agent-session.config-resolution`
- `psi.agent-session.dispatch-effects` currently writes user/project config directly through `psi.agent-session.user-config` and `psi.agent-session.project-preferences`

Architectural conclusion:
- shared config file mechanics are owned too high today
- this is a good lower-component extraction candidate
- the extracted component should own generic config file reading/writing/layering, while project-nREPL-specific validation and agent-session runtime override policy stay with their owning domains

Refinement pass completed to remove initial ambiguities:
- first cut should preserve current file paths and persisted key layout exactly
- first cut should preserve `app-runtime`'s current typed accessor contract via the extracted resolution namespace rather than forcing a consumer redesign
- first cut should keep `psi.project-nrepl.config` as the project-nREPL-owned interpretation/validation surface and only remove its duplicated lower-level read/merge mechanics
- current malformed-project-file warning behavior from `agent-session.project-preferences` is treated as the authoritative policy to consolidate around
- the task does not attempt to invent a fully generic arbitrary-subtree config framework; it should instead extract the shared mechanics needed by the current real consumers cleanly
