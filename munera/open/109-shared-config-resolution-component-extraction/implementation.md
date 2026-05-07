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
