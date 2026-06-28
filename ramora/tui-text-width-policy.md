# TUI text width policy

This document is the canonical developer-facing width-policy summary for user-visible TUI text surfaces.

## Policy matrix

| Surface | Text class | Width policy | Prefix-budget rule | Continuation rule | Proof location |
| --- | --- | --- | --- | --- | --- |
| Startup banner `Model:` | label+value summary | wrap | subtract visible width of `"  Model: "` | continuation aligns under value start | `psi.tui.app-test/startup-banner-metadata-wraps-at-terminal-width-test` |
| Startup banner `Prompts:` | label+value summary | wrap | subtract visible width of `"  Prompts: "` | continuation aligns under value start | `psi.tui.app-test/startup-banner-metadata-wraps-at-terminal-width-test` |
| Startup banner `Skills:` | label+value summary | wrap | subtract visible width of `"  Skills: "` | continuation aligns under value start | `psi.tui.app-test/startup-banner-metadata-wraps-at-terminal-width-test` |
| Startup banner `Exts:` | label+value summary | wrap | subtract visible width of `"  Exts: "` | continuation aligns under value start | `psi.tui.app-test/startup-banner-metadata-wraps-at-terminal-width-test` |
| Startup banner controls/help | controls summary | wrap | subtract visible width of `"  "` | continuation aligns under content start after the base indent | `psi.tui.app-test/startup-banner-metadata-wraps-at-terminal-width-test` |
| Transcript user message | transcript line | wrap | subtract visible width of `"刀: "` | continuation aligns under content start | focused unit test in `psi.tui.app-view-runtime-test` |
| Transcript assistant message | paragraph-like transcript / markdown | wrap for paragraphs, preserve for code blocks | subtract visible width of `"ψ: "` from paragraph width | continuation aligns under content start; implemented through width-aware markdown paragraph rendering, while markdown code blocks preserve width intentionally | focused unit test in `psi.tui.app-view-runtime-test` |
| Transcript thinking message | thinking transcript line | wrap | subtract visible width of `"· "` | continuation aligns under content start | focused unit test in `psi.tui.app-view-runtime-test` |
| Rich transcript variants (`agent-result`, `plan-state-learning`) | heading + indented body | mixed | headings are short labels; body uses the local body indent budget | continuation aligns under the variant body indent | existing variant render tests plus focused narrow-width tests where added |
| Tool header summary, collapsed | compact tool header | truncate | subtract visible width of `"  <status> "` | none; single-line compact summary | focused unit test in `psi.tui.app-view-runtime-test` |
| Tool body, expanded, plain text | tool body | wrap | subtract visible width of four-space body indent | continuation aligns under body text indent | focused unit test in `psi.tui.app-view-runtime-test` |
| Tool body, expanded, warning lines | tool body warning | wrap | subtract visible width of four-space body indent | continuation aligns under body text indent | focused unit test in `psi.tui.app-view-runtime-test` |
| Tool body, expanded, preformatted/code-like output | preformatted body | preserve unless promoted to plain-text rendering by an explicit renderer | explicit indent only; no semantic reflow | preserve source line structure intentionally | documented policy in this file; current focused proof is the negative scope boundary around plain-text wrapping in `psi.tui.app-view-runtime-test/expanded-tool-body-wraps-at-narrow-width-test` |
| Input editor before submit | editor input | wrap | subtract visible width of prompt prefix | continuation aligns under content start | existing `wrap-text-input-*` tests |
| Prompt autocomplete rows | selector row | truncate | full row width is the available width | none; compact row summary | existing autocomplete tests |
| Dialog/selectors | selector/input rows | truncate for compact option rows, preserve simple structural layout | full row width is the available width | no paragraph continuation rule today | existing dialog/selector tests |
| Footer path/model/status lines | footer summaries | mixed truncate/layout-fit | surface-specific line budget | no paragraph continuation; footer is a compact status surface | existing footer tests |

## Rules

- `wrap`: fit visible content within available width using continuation lines.
- `truncate`: shorten deterministically to fit available width.
- `preserve`: keep source line structure intentionally; overflow behavior is not accidental.
- Default continuation rule for wrapped human-readable text: continuation lines align under the content start after prefix subtraction.

## Current shared rule shapes

1. Prefixed wrapped line
   - Used directly for user transcript and thinking transcript.
   - The assistant paragraph transcript follows the same visible-width/continuation contract, but is implemented through the markdown renderer rather than this helper shape.
   - Budget: `available-width = terminal-width - visible-width(prefix)`.
   - Continuation: prefix-width spaces.

2. Label+value wrapped summary
   - Used for startup banner metadata.
   - Budget: `available-width = terminal-width - visible-width(label-prefix)`.
   - Continuation: spaces matching the full label prefix width so wrapped values align under the value region.

3. Compact status/header truncation
   - Used for collapsed tool headers and several selector/footer rows.
   - Budget: full available row width after fixed leading status/padding.
   - Continuation: none.

## Inventory boundaries

In scope for task 066:
- startup banner metadata
- submitted transcript rendering for user / assistant / thinking
- tool header/body rendering
- editor-adjacent TUI text surfaces where width behavior is user-visible

Out of scope for task 066:
- Emacs UI
- non-TUI adapters
- broad visual redesign
