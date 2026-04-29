- [x] Establish the surface inventory and width-policy matrix
  - [x] list the relevant TUI text surfaces
  - [x] finalize the authoritative in-scope inventory for this task
  - [x] classify each as wrap | truncate | preserve
  - [x] note prefix budget / continuation indentation expectations where applicable
  - [x] choose the canonical location for the width-policy summary artifact
  - [x] record the minimum artifact schema: surface, text class, width policy, prefix-budget rule, continuation rule, proof location

- [x] Startup banner slice
  - [x] unit proof exists for prompt/skill startup banner truncation
  - [x] decide width policy for each banner line type
    - [x] model line
    - [x] prompts summary
    - [x] skills summary
    - [x] extension summary
    - [x] controls/help line
  - [x] implement startup banner convergence
  - [x] keep proof focused on behavior, not helper shape

- [x] Submitted user prompt transcript slice
  - [x] add/finalize focused proof for narrow-width submitted user prompt rendering
  - [x] verify this is treated separately from input-editor wrapping
  - [x] verify continuation indentation is intentional
  - [x] fix behavior only if the proof exposes a gap

- [x] Assistant transcript slice
  - [x] add/finalize focused proof for narrow-width assistant transcript rendering where needed
  - [x] verify paragraph-like transcript content uses the intended available-width rule
  - [x] preserve intentional preformatted/code behavior
  - [x] make any intentional preformatted/code preserve behavior explicit in proof

- [x] Thinking slice
  - [x] add/finalize focused proof for narrow-width thinking rendering
  - [x] verify `· ` prefix handling does not cause right-edge clipping
  - [x] verify continuation shape is intentional

- [x] Tool rendering slice
  - [x] distinguish tool header policy from tool body policy
  - [x] add/finalize focused proof for narrow-width tool header rendering
  - [x] add/finalize focused proof for narrow-width tool body rendering
  - [x] verify collapsed vs expanded states are both intentional
  - [x] document any intentional truncation in compact header mode
  - [x] confirm compact-header truncation, if retained, is stable, explicit, and tested

- [x] Shared helper convergence
  - [x] identify duplicated width/shaping logic across surfaces
  - [x] extract shared helpers only where at least two surfaces share the same rule shape
  - [x] avoid abstraction that hides policy decisions

- [x] Integration proof
  - [x] keep the startup-wrap demo harness proof green
  - [x] de-scope the real-launcher narrow-width resume proof because it tested resume-selection semantics rather than the width policy itself
  - [x] add/refine tmux proof for banner metadata if unit proof alone is insufficient
  - [x] avoid adding tmux coverage for surfaces where pure render proof is already authoritative
  - Focused integration verification is now green on the stable startup-wrap path after removing the unrelated launcher absolute-path fixture dependency.

- [x] Final review pass
  - [x] review remaining surfaces in the authoritative inventory for undocumented truncation behavior
  - [x] spot-check every remaining user-visible TUI line-producing surface in that inventory for accidental right-edge clipping
  - [x] confirm tests describe the surface contract clearly
  - [x] confirm width behavior is explicit rather than terminal-accidental
  - [x] record the final width-policy summary artifact in the chosen canonical location
  - [x] add terse task review artifact

- [x] Optional shaping follow-on
  - [x] data-drive `render-banner` to reduce repetition while preserving current width behavior
  - [x] tighten proof references in `doc/tui-text-width-policy.md` for secondary rows (`Exts:`, controls/help, preformatted tool-body preserve behavior)
  - [x] keep assistant transcript on the markdown-renderer path unless real duplication grows; do not force false convergence with `prefixed-wrap-lines`
  - [x] add a tiny style-aware wrapped-line helper only if another surface repeats the exact same shape as user/thinking/banner wrapping
