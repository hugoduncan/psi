Goal: make TUI text wrapping behavior systematic, width-aware, and consistent across all in-scope user-visible TUI text surfaces in the authoritative inventory so narrow terminals do not truncate meaningful content.

Intent:
- Define and converge a single mental model for how text should behave in narrow TUI layouts.
- Turn wrapping from a local implementation detail into an explicit rendering invariant.
- Leave the TUI with a small set of reusable rules and helpers rather than a growing collection of one-off fixes.

Problem statement:
- We have evidence that width handling is inconsistent across the TUI.
- A focused failing unit test now proves that startup banner metadata (`Prompts:` and `Skills:`) can exceed terminal width.
- Observed/reported risk is broader than the startup banner and appears to affect multiple distinct surfaces:
  - startup banner metadata
  - submitted user prompt rendering in the transcript
  - assistant transcript rendering after submit
  - thinking blocks
  - tool output and tool summaries
- Current behavior appears to be surface-specific rather than governed by a shared width policy.
- This makes regressions likely: one surface can be fixed while another still truncates, clips, or wraps with inconsistent continuation shape.
- The primary gap is architectural: width behavior is not yet treated as a first-class rendering contract.

Why this should be a dedicated task:
- The bug report is not just “one banner line is too long”; it points to a class of defects.
- The same terminal width should produce predictable behavior across all TUI text surfaces.
- Without an explicit surface taxonomy and width policy, local fixes will drift and regress.

Scope:
In scope:
- TUI rendering surfaces where human-readable text is expected to remain legible on narrow terminals.
- Identification of the distinct text classes that need different width behavior.
- Convergence of banner/transcript/thinking/tool rendering on explicit wrapping rules.
- Focused proof at both unit level and tmux integration level for representative surfaces.
- Clarifying which surfaces wrap, which truncate, and which intentionally preserve preformatted width.
- Establishing continuation indentation rules where wrapping is expected.

Out of scope:
- Emacs UI wrapping behavior.
- Non-TUI adapter layout work.
- General visual redesign of the TUI.
- Reworking markdown semantics beyond what is needed to make width policy explicit.
- Changing the meaning of preformatted/code content unless required to document current intentional behavior.
- Solving every possible direct-terminal vs tmux variation in this task; the task should improve policy clarity and representative proof, not exhaustively model every terminal emulator.

Acceptance criteria:
- Each surface in the authoritative inventory is classified by text class and assigned an explicit width policy.
- Startup banner metadata wraps correctly on narrow terminals.
- Submitted user prompts render correctly on narrow terminals after submission.
- Thinking blocks render correctly on narrow terminals with explicit prefix-budget and continuation behavior.
- Tool output and any tool header/body split have intentional, proven width behavior.
- Assistant/message transcript rendering remains width-aware and does not regress.
- Surfaces that intentionally truncate or preserve width are explicitly identified and tested as such.
- Compact tool headers may intentionally truncate, but that truncation must be stable, explicit, and tested.
- Tests describe behavior by surface and rule, not by incidental implementation detail.
- Shared rendering helpers or shaping rules are used only where repeated, proven rule shapes justify them.
- Completion leaves behind a concise durable width-policy summary artifact for future TUI changes.

Minimum concepts:
- Text surface: a named rendering surface with a user-facing contract.
- Text class: paragraph-like, label+value summary, transcript line, thinking line, tool header, tool body, preformatted/code.
- Width policy: wrap | truncate | preserve, plus continuation indentation rules.
- Available width: terminal width minus surface-specific prefix/indent costs.
- Continuation shape: how wrapped follow-on lines align visually.
- Prefix budget: visible display width consumed by a line prefix such as `ψ: `, `刀: `, `· `, or tool-status indicators.

Operational definitions:
- wrap: fit visible content within available width using continuation lines; long unbroken tokens hard-wrap when needed unless a surface policy explicitly says otherwise.
- truncate: shorten visible content deterministically to fit available width; truncation shape must be explicit and tested.
- preserve: keep source line structure/preformatted layout intentionally; any overflow behavior must be documented and tested rather than accidental.
- default continuation rule: when a surface wraps normal human-readable content, continuation lines align under the content start after prefix subtraction unless the surface explicitly defines a different continuation shape.

Surface inventory to drive the design:
- The first implementation step must finalize this into the authoritative in-scope inventory for the task.
- Final review and task completion are judged against that authoritative inventory, not an open-ended notion of every possible future surface.

1. Startup banner
- model line
- prompts summary
- skills summary
- extension summary
- controls/help line

2. Transcript
- user messages
- assistant messages
- thinking messages
- plan/state/learning variant
- agent-result variant

3. Tool rendering
- tool header summary
- tool argument/details summary
- tool output body
- collapsed vs expanded tool states
- running vs completed tool states

4. Input/editor-adjacent surfaces
- text input wrapping
- autocomplete surfaces
- dialogs/selectors where line fitting is user-visible

5. Prompt lifecycle distinction
- input editor behavior before submission
- submitted prompt transcript behavior after submission
- these must be treated as separate surfaces rather than assumed equivalent

Width-policy hypotheses by text class:
- Label+value summaries should wrap with aligned continuation indentation under the value region.
- Paragraph-like transcript content should wrap to available width after prefix subtraction.
- Thinking is a distinct text class with transcript-like wrapping plus a `· ` prefix-budget rule.
- First-cut tool policy: compact/collapsed headers may truncate when their job is compact status summarization, human-readable tool body text should wrap, and preformatted or machine-like body content may preserve width where intentional.
- Tool body output should either wrap or preserve according to content class; plain text and human-readable output should not be accidentally clipped.
- Preformatted/code content may preserve width where that is intentional, but this must be explicit and proven rather than accidental.

Acceptance examples:
- A narrow-width startup banner shows all prompt/skill names across wrapped lines rather than clipping the line.
- A submitted user prompt that exceeds width remains readable in the transcript with consistent continuation indentation.
- A long thinking sentence wraps rather than disappearing off the right edge.
- Tool collapsed mode shows a compact intentional summary; expanded plain-text output remains legible.
- If a code/preformatted block preserves width rather than wrapping, tests state that this is the intended policy.

Possible implementation approaches:
1. Patch each affected surface locally
- Fastest short-term path.
- Highest regression risk.
- Does not create a durable policy.

2. Define a shared TUI width-policy layer and migrate surfaces onto it
- Higher leverage.
- Best match for the actual problem: inconsistent local behavior.
- Encourages explicit classification of text classes.

3. Hybrid approach
- First capture failing tests by surface.
- Then extract shared wrapping/truncation helpers while fixing the highest-value surfaces.
- Likely the best fit for a small vertical-slice sequence.

Preferred shape:
- Use the hybrid approach.
- Start from failing proof for startup banner metadata.
- Expand to a surface inventory and classify each surface by text class.
- Extract/centralize the smallest useful shared width-policy helpers.
- Converge remaining surfaces one slice at a time with proof.
- Delay abstraction until at least two surfaces clearly share the same rule shape.

Architecture/pattern alignment:
- Follow the existing separation between rendering helpers and higher-level view composition.
- Keep width behavior explicit at render boundaries instead of implicit in terminal behavior.
- Reuse existing width-aware markdown and ANSI helpers where they fit.
- Preserve intentional exceptions such as preformatted/code surfaces if those are part of the contract.
- Prefer one-way obvious rendering rules over per-surface special cases.
- Keep tests close to the rendering surface that owns the behavior.

Refinement questions this task must answer:
- Which surfaces should wrap, and which should intentionally truncate?
- Where should continuation indentation be defined and shared?
- Which current helpers already embody the desired policy, and which bypass it?
- Is there any surface where terminal-specific behavior, rather than local rendering logic, is the dominant source of truncation?
- What is the smallest shared helper set that makes the policy obvious?

Risks:
- Treating all text the same when some surfaces need different policies.
- Breaking currently-correct transcript wrapping while fixing banner/tool surfaces.
- Confusing clipping caused by terminal/runtime behavior with clipping caused by local render logic.
- Overfitting to tmux while missing direct-terminal differences.
- Introducing abstraction before the surface taxonomy is clear enough.

Suggested decomposition:
1. establish explicit surface inventory + width policy
2. fix startup banner metadata wrapping
3. audit submitted user/assistant transcript surfaces for consistency
4. audit thinking rendering
5. audit tool header/body rendering, especially collapsed vs expanded states
6. add representative tmux proof for real-launcher narrow-width behavior where appropriate
7. converge duplicated width logic into shared helpers only when the common shape is evident

Notes:
- Current failing proof exists for startup banner prompt/skill lines in `psi.tui.app-test/startup-banner-metadata-wraps-at-terminal-width-test`.
- The broader task is not “make everything wrap”; it is “make width behavior explicit, systematic, and proven by surface.”
- A useful completion outcome is a short durable rendering policy that future TUI changes can follow.
- The width-policy summary artifact should live in one canonical developer-facing location chosen during implementation.
- Minimum contents of that artifact: surface, text class, width policy, prefix-budget rule, continuation rule, and proof location.
