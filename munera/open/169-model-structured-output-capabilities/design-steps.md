# Design follow-up steps

- [ ] Create `plan.md` and `steps.md` before implementation so the approach, target files, sequencing, verification commands, and risks are explicit and reviewable.
- [ ] Choose the concrete OpenAI provider-native mechanism and transport surface for this slice, explicitly reconciling the design's public `/v1/responses` preference with the current `:openai-completions` and `:openai-codex-responses` adapters and documenting whether a new platform Responses transport/API enum is introduced or chat-completions JSON Schema response format is used first.
- [ ] Specify the exact observable strategy metadata surface for both streaming and non-streaming provider calls, including where `:provider-native`, `:prompted-json`, `:repair-parse`, or `:unsupported` is emitted/stored and how callers read it without guessing from request shape.
- [ ] Define Anthropic forced structured-output tool composition with ordinary tools: deterministic synthetic tool naming/collision handling, `tool_choice` behavior when user tools are also present, and how extracted synthetic tool input is separated from normal assistant tool calls.
