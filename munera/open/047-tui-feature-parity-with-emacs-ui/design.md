Goal: bring the TUI to practical feature parity with the Emacs UI for core day-to-day psi workflows, while preserving backend ownership of shared semantics and avoiding adapter-local divergence.

Intent:
- make the TUI a first-class interactive surface rather than a narrower fallback UI
- close the highest-value capability gaps between the TUI and Emacs
- keep parity work driven by canonical app-runtime projections and actions so the two adapters converge rather than fork
- define parity in terms of user-visible workflows and outcomes, not pixel-identical rendering

Context:
- The project has already moved substantial shared UI semantics into canonical app-runtime projections and action/result shaping.
- Emacs currently exposes richer interactive workflows than the TUI in several areas, including session browsing/navigation, contextual status visibility, and other backend-driven UI affordances.
- Recent convergence work has explicitly reduced compatibility scaffolding and strengthened backend ownership of:
  - context/session-tree summaries
  - footer/header/session-summary fragments
  - extension widget/status projections
  - frontend action naming and result normalization
- This creates a good foundation for parity work because the intended architecture is already: backend owns semantics, adapters render and interact.
- The TUI should not independently reinvent semantics that Emacs now consumes from shared app-runtime projections.

Problem:
- The TUI currently lags the Emacs UI in user-facing capabilities that matter for real interactive use.
- Without an explicit parity task, TUI behavior is likely to remain a partial surface while new capabilities continue to appear first in Emacs.
- If parity work is approached as piecemeal local rendering changes, adapter drift will increase and backend/shared projection gains will be lost.
- The project needs a clear task that defines what “feature parity” means operationally, what is in scope, and what architectural constraints must be preserved while closing the gap.

Definition of parity for this task:
- parity means that the TUI supports the same core user workflows as Emacs with equivalent backend semantics and materially equivalent outcomes
- parity does not require identical layout, keybindings, or presentation details
- parity allows adapter-specific rendering choices so long as:
  - the same backend-owned data/projection surfaces are used where applicable
  - the same canonical actions/events drive behavior
  - user-visible capability is not meaningfully reduced in the TUI

Core workflow areas to cover:
1. Session and context navigation
- browse available sessions and their hierarchy/tree structure
- select/focus a session intentionally rather than only through narrow command paths
- expose branching/fork-related navigation affordances when those semantics already exist in backend projections/actions

2. Shared status and diagnostics visibility
- surface canonical backend session summary/header/footer/status information in the TUI with comparable usefulness to Emacs
- expose backend-projected extension status/widgets and background-job state where those are part of normal operator awareness
- avoid TUI-only reconstruction of status semantics when a shared projection already exists

3. Frontend action workflows
- support the important backend-owned frontend actions that Emacs already handles for routine usage, such as selection/picker-style flows for sessions, resume sessions, models, and thinking level, where those actions are intended to be shared across adapters
- ensure TUI handling of these actions is canonical and not dependent on legacy payload variants

4. Day-to-day operator usability
- allow a TUI user to perform normal interactive work without needing to switch to Emacs for routine control-plane tasks
- ensure parity applies to practical workflows rather than only passive display of the same data

Scope:
In scope:
- identify the meaningful feature gaps between TUI and Emacs for core operator workflows
- close those gaps by making the TUI consume canonical backend projections and action contracts where available
- add or refine TUI rendering/interaction components needed to expose existing shared semantics
- add focused proof for the parity-critical behaviors added in this slice
- update relevant user-facing docs if TUI capabilities or workflows become newly available/discoverable

Out of scope:
- pixel/layout identity between TUI and Emacs
- reproducing Emacs-specific implementation techniques in TUI
- broad backend semantic invention solely for TUI convenience when the capability is not already part of the shared runtime direction
- exhaustive parity for every adapter-specific nicety if it is not part of core day-to-day workflows
- large unrelated TUI refactors unless they are the smallest clear path to exposing shared canonical behavior

Architectural constraints:
- preserve backend/app-runtime ownership of canonical session/context/tree/status/action semantics
- prefer extending the TUI to consume existing canonical projections over adding TUI-local semantic reconstruction
- if a parity gap reveals a missing shared projection or action contract, fix it at the shared boundary rather than by inventing adapter-local hidden rules
- keep command/action naming and payload handling canonical; do not reintroduce compatibility fallback shapes in the TUI
- keep the work decomposable into small vertical slices that each add a user-visible workflow

Current evidence and initial gap inventory:

Already present in the TUI:
- session-tree browsing already exists in a meaningful form through the `/tree` selector flow
  - focused proof exists for:
    - opening tree mode
    - switching sessions from the selector
    - rendering hierarchy, active badge, streaming badge, display-name preference, and fork points
    - consuming shared selector order
- shared footer/status visibility already exists in an important baseline form
  - focused proof exists for rendering:
    - worktree path and git branch
    - session display name
    - usage/cost/context stats
    - model/thinking summary
    - extension statuses from backend query/projection data
- backend-owned widget ordering is already respected in the TUI projection path
- extension status/widget local support exists in TUI tests
- tmux-backed end-to-end coverage now proves real-terminal boot/help/quit viability

Likely parity strengths to preserve:
- TUI is already capable of core chat interaction and basic session switching
- TUI already consumes some canonical backend-owned projections instead of reconstructing everything locally
- parity work should build on these existing surfaces rather than replacing them wholesale

Observed or likely remaining parity gaps:

Gap cluster A: session/context navigation ergonomics
- the TUI has `/tree`, but parity likely still lags Emacs in discoverability and dedicated navigation affordances
- likely missing or weaker behaviors to verify and close:
  - a clearly surfaced session/context widget or panel driven from canonical `context/updated` payloads rather than command-only entry
  - smoother intentional browsing/focusing without first knowing to invoke `/tree`
  - parity around backend-owned context widget semantics when multiple sessions exist
- design implication:
  - treat this as improving workflow access to existing shared context/session-tree semantics, not inventing a second TUI-only tree model

Gap cluster B: frontend action parity
- app-runtime now exposes canonical frontend actions for:
  - `select-session`
  - `select-resume-session`
  - `select-model`
  - `select-thinking-level`
- TUI evidence strongly suggests some session-selector handling exists, but the parity status across the full shared action set is not yet explicit in this task
- likely work here is to inventory which canonical actions Emacs handles routinely and ensure the TUI handles the same canonical actions/results without legacy payload assumptions
- design implication:
  - this is a high-value parity slice because action handling is the shared control-plane path across adapters

Gap cluster C: background jobs and extension status/widget visibility
- app-runtime has canonical background-job summary/widget/status projection surfaces
- Emacs has richer projection/widget machinery, while TUI evidence currently shows more limited projection/rendering coverage
- likely gap:
  - the TUI may not yet expose backend-projected background-job state and extension widget/status information with comparable usefulness for routine operation
- design implication:
  - parity should consume backend-projected widget/status ordering and summaries directly, not create an unrelated TUI-specific diagnostic surface

Gap cluster D: shared diagnostics and operator-awareness surfaces
- Emacs tests indicate richer session-status diagnostics strings and event-driven status updates
- TUI already renders a useful footer, but parity is not yet defined for broader operator-awareness cues such as:
  - current run/session state visibility
  - explicit background activity visibility
  - failure/cancellation/result messaging for frontend actions
- design implication:
  - define parity around operational usefulness: a TUI user should be able to see the same important state transitions and diagnostics, even if rendered differently

Gap cluster E: projection-widget parity
- Emacs has a substantial projection-widget path with widget-spec query, event subscription, mutation dispatch, timeout handling, and local widget state
- TUI evidence currently shows backend-owned widget ordering and extension UI support, but not full parity with Emacs’s projection-widget interaction model
- this may be the largest and most architecture-sensitive parity gap
- design implication:
  - first decide whether full projection-widget parity is required for “practical feature parity” in this task or should be split into a follow-on task
  - if required here, it should still be expressed as a user workflow gap, not as “copy the Emacs widget engine into TUI”

Priority order for refinement and implementation:
1. Frontend action parity
- highest leverage because it aligns the shared adapter control plane
- success means the TUI can handle the canonical backend-owned selection/action workflows used in day-to-day operation

2. Session/context navigation ergonomics
- highest user-visible gap after actions
- success means session browsing/focusing is discoverable and practical in TUI without relying on hidden command knowledge

3. Shared status/background-job/operator-awareness parity
- success means TUI users can see the same important runtime state and asynchronous activity that Emacs users can monitor

4. Extension widget/status parity
- success means TUI exposes backend-projected extension surfaces needed for normal usage with canonical ordering and semantics

5. Projection-widget interactive parity
- only include in this task if needed for practical parity; otherwise split as a dedicated follow-on once the above workflow parity is closed

Concrete parity inventory:

1. Canonical frontend actions

Confirmed in Emacs:
- Emacs handles canonical `ui/frontend-action-requested` flows for at least:
  - `select-resume-session`
  - `select-session`
  - `select-model`
  - `select-thinking-level`
- Emacs tests prove that these flows:
  - prompt the user
  - preserve canonical action names
  - submit `frontend_action_result` with canonical status/value payloads
  - preserve `:ui/action` on the round trip
  - support cancel semantics

Confirmed in TUI:
- TUI has a session-selector path that consumes canonical context-session actions and items
- focused TUI tests prove practical handling for:
  - `select-session`-style tree/context selection via `/tree`
  - resume-session selection via `/resume`
- TUI source clearly supports a generic backend-owned `:active-dialog` surface with:
  - `:confirm`
  - `:select`
  - `:input`
  and can resolve/cancel dialogs through canonical UI dispatch events

Unknown or not yet proven in TUI:
- no focused evidence was found that TUI currently handles backend-requested frontend actions for:
  - `select-model`
  - `select-thinking-level`
  - canonical `ui/frontend-action-requested` round-trips with explicit `frontend_action_result`
- current proven TUI selector flows may still be command-local or adapter-local entrypoints rather than the full shared frontend-action event path

Inventory classification:
- `select-session`: partial parity
  - TUI has meaningful workflow coverage, but parity with the shared frontend-action event path is not yet explicitly proven
- `select-resume-session`: partial parity
  - TUI has workflow coverage, but canonical frontend-action parity is not yet explicitly proven
- `select-model`: missing or unproven parity in TUI
- `select-thinking-level`: missing or unproven parity in TUI
- generic frontend-action request/result loop: missing or unproven parity in TUI

Implication:
- the first concrete parity slice should verify and, if needed, implement canonical frontend-action handling in TUI rather than relying on command-specific local flows alone

2. Context/session-tree projection surfaces

Confirmed in app-runtime/shared backend:
- canonical context/session-tree summary projection exists
- `context/updated` can carry a canonical backend-projected `session-tree-widget`
- canonical session-tree widget lines carry structured metadata and action commands

Confirmed in Emacs:
- Emacs handles `context/updated`
- Emacs stores the context snapshot on frontend state
- Emacs renders backend-owned session-tree widgets from `context/updated`
- Emacs preserves the session tree across unrelated `ui/widgets-updated` refreshes while keeping later `context/updated` authoritative for removal
- Emacs applies current/runtime-state faces from structured metadata

Confirmed in TUI:
- TUI supports a `/tree` session selector driven from canonical context-session action items
- TUI tests prove hierarchy rendering, active badge, streaming badge, display-name preference, fork points, and shared selector ordering

Unknown or not yet proven in TUI:
- no focused evidence was found that TUI consumes the backend-projected `session-tree-widget` from `context/updated` as a standing visible context widget/panel
- current TUI parity appears stronger for explicit `/tree` entry than for passive/discoverable context projection
- no focused proof was found that TUI preserves context/session-tree projection semantics across unrelated widget refreshes in the way Emacs does

Inventory classification:
- tree browsing workflow: partial parity
- discoverable backend-projected session-tree/context widget: missing or unproven parity in TUI
- projection lifecycle semantics for context widget persistence/removal: missing or unproven parity in TUI

Implication:
- session navigation parity should be refined as: keep `/tree`, but add or prove consumption of canonical backend context/session-tree projections where that is part of the shared UX contract

3. Footer/status/header/operator-awareness surfaces

Confirmed in Emacs:
- Emacs has richer session-status diagnostics and run-state visibility
- Emacs handles footer/projection update events and has focused proof for canonical footer payload use
- Emacs tests cover status diagnostics strings that include session summary fragments and error details

Confirmed in TUI:
- TUI already renders a useful footer using shared query/projection data
- focused TUI proof exists for rendering:
  - worktree path and branch
  - session display name
  - usage and cost stats
  - context fraction/window summary
  - provider/model/thinking summary
  - extension statuses
- TUI also renders backend-owned visible notifications unchanged from the canonical projection

Unknown or not yet proven in TUI:
- no focused evidence was found that TUI exposes the same breadth of session diagnostics and run-state messaging as Emacs beyond the footer baseline
- action-result visibility and cancellation/failure messaging for shared frontend actions is not yet explicitly proven in TUI
- parity expectations for header/session-summary fragments are not yet concretely encoded in TUI tests

Inventory classification:
- baseline footer/status visibility: substantial parity already present
- richer operator diagnostics / action-result awareness: partial or unproven parity

Implication:
- this is not a blank-slate gap; the TUI already has a strong footer baseline
- refinement should target missing operator-awareness behaviors rather than redoing footer rendering

4. Background jobs and extension widget/status projection

Confirmed in app-runtime/shared backend:
- canonical background-job summaries, widgets, and statuses exist
- canonical extension UI snapshots sort widgets/statuses and feed adapter projections

Confirmed in Emacs:
- Emacs handles `ui/widgets-updated` and `ui/status-updated`
- Emacs tests prove rendering of background-job widgets and statuses from backend projection events
- Emacs has broad extension UI projection coverage

Confirmed in TUI:
- TUI renders widgets in backend-owned order
- TUI renders backend-owned visible notifications unchanged
- TUI has focused proof for rendering a background-jobs widget from canonical UI projection data
- TUI has extension UI tests for local widget/status state support

Unknown or not yet proven in TUI:
- no focused evidence was found that TUI fully mirrors the event-driven projection update semantics seen in Emacs for `ui/widgets-updated` and `ui/status-updated`
- no focused proof was found that TUI renders background-job statuses with the same operator usefulness as Emacs beyond widget content lines
- no focused proof was found that TUI renders a standing context of extension statuses/widgets across refresh cycles, rather than only snapshots at init/test seams

Inventory classification:
- basic projected widget rendering: substantial parity already present
- live/event-driven projection semantics and richer status visibility: partial or unproven parity

Implication:
- parity work should focus on live update semantics and operational visibility, not on inventing a separate TUI widget model

5. Projection-widget interactive surface

Confirmed in Emacs:
- Emacs has a mature widget-spec projection path:
  - queries `[:psi.ui/widget-specs]`
  - stores spec-local state
  - subscribes widgets to events
  - dispatches mutations
  - tracks in-flight state and timeouts
  - rerenders from event/query updates

Confirmed in TUI:
- no evidence was found in this inventory pass that TUI currently supports the same interactive widget-spec projection model
- TUI evidence found in this pass is centered on rendered widget snapshots, active dialogs, and command-driven interaction rather than declarative widget-spec interaction

Inventory classification:
- likely missing parity in TUI
- but this may be outside the minimum definition of practical day-to-day parity unless a currently important user workflow depends on it

Implication:
- do not automatically include full widget-spec parity in the first implementation sequence
- first decide whether any practical Emacs-only workflow actually depends on this surface today
- if yes, split that workflow into its own task; if not, treat it as a separate follow-on parity track

Refinement conclusions from the inventory:
- highest-confidence missing parity is canonical frontend-action handling in TUI, especially for `select-model` and `select-thinking-level`
- second highest-confidence gap is discoverable/session-persistent context projection parity, not raw tree rendering itself
- third gap is richer live operator-awareness over background jobs, statuses, and action-result feedback
- full projection-widget parity remains a likely larger follow-on rather than a prerequisite for the first practical parity slices

Refinement work required before planning:
- convert the above inventory into small vertical slices with explicit acceptance
- for each slice, distinguish:
  - missing TUI rendering/interaction over an existing shared projection
  - missing shared projection/action contract needed by both adapters
  - non-goal adapter-specific nicety
- keep the first slices focused on canonical frontend actions and discoverable context/session navigation, since those are the clearest high-value parity gaps

Expected deliverable shape:
- a parity-oriented backlog or implementation sequence that can be executed in small slices
- each slice should target one workflow cluster rather than one technical layer in isolation
- examples of acceptable slices include:
  - session-tree browsing/navigation parity
  - shared picker/frontend-action parity
  - shared footer/status/background-job visibility parity
  - extension widget/status parity for the TUI
- the task may require narrowing and sequencing after an initial gap inventory, but the overall design target is clear: practical workflow parity with Emacs

Acceptance:
- there is an explicit, implementable task for TUI feature parity with the Emacs UI
- parity is defined in terms of core user workflows and equivalent backend semantics, not identical rendering
- the task preserves the project’s adapter-convergence architecture: shared semantics in app-runtime, rendering/interaction in adapters
- the task covers at least these workflow domains:
  - session/context navigation
  - shared status/footer/header/diagnostic visibility
  - backend-owned frontend action flows used in normal operation
  - practical operator control so the TUI is viable without switching to Emacs for routine tasks
- the task explicitly forbids solving parity by duplicating backend semantics locally in the TUI
- the task is decomposable into follow-on implementation slices with focused proof

Why this design is complete and unambiguous:
- it defines parity operationally as equivalent workflows over shared semantics
- it names the major workflow clusters that matter for daily use
- it constrains the architecture strongly enough to prevent adapter drift
- it leaves room for adapter-appropriate presentation while remaining specific about what outcomes must match
