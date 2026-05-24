# Implementation notes

## 2026-05-23 — ambiguity review

Found actionable ambiguity feedback: `plan.md` and `steps.md` are absent, so the implementation approach, sequencing, target files, and verification are not reviewable. The design also leaves the first OpenAI native mechanism/transport unresolved against the current AI API surface (`:openai-completions` and `:openai-codex-responses`, with no explicit public Responses transport), leaves strategy metadata propagation ambiguous for streaming vs non-streaming callers, and does not specify how Anthropic forced structured-output tool use composes with ordinary user tools/tool choice.
