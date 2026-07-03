# Design steps — architectural fit follow-ups

- [ ] `psi.ai.model-selection` / the model registry (`components/ai/src/psi/ai/model_selection.clj`,
  `psi.ai.models` / `psi.ai.user_models`) exposes only `:supports-text`,
  `:supports-images`, `:supports-reasoning`, `:locality`, `:context-window`,
  `:max-tokens`, and cost/latency-tier facts — there is no tool-calling /
  function-calling capability fact or selection criterion. This design's
  helper session is fundamentally **tool-using** (it must call the read-only
  search toolset to gather filesystem/git evidence), unlike
  `auto-session-name`'s toolless single-shot completion, which is the pattern
  the design cites for model selection ("exactly like `auto-session-name`").
  Reusing that pattern verbatim does not guarantee the selected local model
  can actually invoke tools, so the augmenter could silently select a
  non-tool-calling local model and always fall through to a confidence-gated
  `:no-op` without any diagnostic distinguishing "no local model available"
  from "local model available but cannot use tools." Add a tool-calling
  capability fact/criterion to `psi.ai.model-selection`/the model registry (or
  otherwise ensure helper-model selection filters on tool-calling support)
  before or as part of implementing this task.
