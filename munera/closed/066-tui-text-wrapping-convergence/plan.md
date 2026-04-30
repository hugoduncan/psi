Approach:
- Treat this as a convergence task, not a single local bug fix.
- Begin with the now-failing startup-banner proof, then use that as the first slice in a broader width-policy pass over TUI text surfaces.
- Keep slices vertical and behavior-led: one surface class at a time, each with focused proof and an explicit local width-policy decision.
- Delay shared-helper extraction until the surface taxonomy is clear enough that the common rule shape is obvious.

Execution shape:
1. define the surface inventory and width-policy matrix
2. use the startup-banner defect as the first concrete convergence slice
3. move through transcript, thinking, and tool surfaces in that order unless new proof suggests reprioritization
4. only after two or more slices share the same rule shape, extract shared helpers
5. end with a review pass that confirms width behavior is explicit across remaining user-visible surfaces

Slice strategy:
- For each slice:
  1. identify the surface and its text class
  2. decide wrap | truncate | preserve
  3. define prefix-budget and continuation-indentation expectations
  4. add or sharpen focused proof
  5. implement the minimal change that satisfies the proof
  6. only then consider whether the rule should be shared

Planned slice order:
1. Surface inventory + width-policy matrix
- enumerate the relevant TUI text surfaces
- finalize the authoritative in-scope inventory for this task
- classify each by text class and intended width behavior
- define the canonical location and minimum schema of the width-policy summary artifact
- identify any existing helpers already implementing the desired policy

2. Startup banner
- treat current failing proof as the entry point
- decide policy per banner line type rather than treating the banner as one undifferentiated block
- explicitly classify which banner lines wrap, truncate, or preserve
- likely converge label+value summaries on wrapped continuation alignment

3. Submitted user prompt transcript
- verify whether submitted user prompts already satisfy the desired contract
- if not, fix with a focused proof and explicit continuation rule

4. Assistant transcript
- verify paragraph-like assistant content under narrow width
- preserve any intentional distinction for preformatted/code content

5. Thinking
- verify that thinking lines respect width after the `· ` prefix budget is applied
- make continuation shape explicit rather than incidental

6. Tool rendering
- treat tool header and tool body as separate policy decisions
- clarify compact/collapsed behavior vs expanded/body behavior
- start from the first-cut assumption that compact headers may truncate, human-readable body text should wrap, and preformatted/machine-like body content may preserve width unless proof drives a different decision
- document any intentional truncation in compact header mode

7. Integration proof and review
- keep representative tmux proof green for startup-oriented and real-launcher paths
- add more integration proof only when a unit-tested policy still depends materially on terminal behavior
- do not chase tmux coverage for every surface when pure render tests are authoritative
- finish with an explicit review of remaining user-visible surfaces for undocumented clipping/truncation
- leave behind a concise width-policy summary artifact for future TUI changes

Decision constraints:
- Keep preformatted/code behavior intentional rather than accidentally wrapped.
- Preserve existing correct transcript behavior while fixing inconsistent surfaces.
- Make continuation indentation explicit for each surface class.
- Avoid broad visual redesign; this task is about width semantics and proof.
- Prefer explicit render-time rules over relying on terminal clipping/reflow behavior.

Non-goals during execution:
- do not redesign the TUI layout wholesale
- do not generalize helpers before at least two surfaces need the same abstraction
- do not assume all surfaces should wrap; some may intentionally truncate or preserve width

Risks:
- fixing one surface while missing another with the same structural defect
- introducing helper abstraction too early before the surface taxonomy is clear
- making tmux proof pass while direct render unit tests still leave gaps
- conflating terminal-specific display artifacts with local rendering-policy defects

Verification shape:
- unit tests for pure render/view behavior by surface
- tmux integration for at least one startup-oriented narrow-width path and one real-launcher narrow-width path
- use tmux where terminal-boundary behavior materially affects the contract, such as launcher/resume paths, resize behavior, or visible clipping not represented in pure render tests
- failing tests should lead each slice; implementation follows proof rather than preceding it
- successful completion should leave each relevant surface in the authoritative inventory with an intelligible, test-backed width contract
