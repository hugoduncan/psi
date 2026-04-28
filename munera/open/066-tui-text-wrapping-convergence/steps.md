- [ ] Establish the surface inventory and width-policy matrix
  - [ ] list the relevant TUI text surfaces
  - [ ] finalize the authoritative in-scope inventory for this task
  - [ ] classify each as wrap | truncate | preserve
  - [ ] note prefix budget / continuation indentation expectations where applicable
  - [ ] choose the canonical location for the width-policy summary artifact
  - [ ] record the minimum artifact schema: surface, text class, width policy, prefix-budget rule, continuation rule, proof location

- [ ] Startup banner slice
  - [x] unit proof exists for prompt/skill startup banner truncation
  - [ ] decide width policy for each banner line type
    - [ ] model line
    - [ ] prompts summary
    - [ ] skills summary
    - [ ] extension summary
    - [ ] controls/help line
  - [ ] implement startup banner convergence
  - [ ] keep proof focused on behavior, not helper shape

- [ ] Submitted user prompt transcript slice
  - [ ] add/finalize focused proof for narrow-width submitted user prompt rendering
  - [ ] verify this is treated separately from input-editor wrapping
  - [ ] verify continuation indentation is intentional
  - [ ] fix behavior only if the proof exposes a gap

- [ ] Assistant transcript slice
  - [ ] add/finalize focused proof for narrow-width assistant transcript rendering where needed
  - [ ] verify paragraph-like transcript content uses the intended available-width rule
  - [ ] preserve intentional preformatted/code behavior
  - [ ] make any intentional preformatted/code preserve behavior explicit in proof

- [ ] Thinking slice
  - [ ] add/finalize focused proof for narrow-width thinking rendering
  - [ ] verify `· ` prefix handling does not cause right-edge clipping
  - [ ] verify continuation shape is intentional

- [ ] Tool rendering slice
  - [ ] distinguish tool header policy from tool body policy
  - [ ] add/finalize focused proof for narrow-width tool header rendering
  - [ ] add/finalize focused proof for narrow-width tool body rendering
  - [ ] verify collapsed vs expanded states are both intentional
  - [ ] document any intentional truncation in compact header mode
  - [ ] confirm compact-header truncation, if retained, is stable, explicit, and tested

- [ ] Shared helper convergence
  - [ ] identify duplicated width/shaping logic across surfaces
  - [ ] extract shared helpers only where at least two surfaces share the same rule shape
  - [ ] avoid abstraction that hides policy decisions

- [ ] Integration proof
  - [ ] keep the startup-wrap demo harness proof green
  - [ ] keep the real-launcher narrow-width resume proof green
  - [ ] add/refine tmux proof for banner metadata if unit proof alone is insufficient
  - [ ] avoid adding tmux coverage for surfaces where pure render proof is already authoritative

- [ ] Final review pass
  - [ ] review remaining surfaces in the authoritative inventory for undocumented truncation behavior
  - [ ] spot-check every remaining user-visible TUI line-producing surface in that inventory for accidental right-edge clipping
  - [ ] confirm tests describe the surface contract clearly
  - [ ] confirm width behavior is explicit rather than terminal-accidental
  - [ ] record the final width-policy summary artifact in the chosen canonical location
