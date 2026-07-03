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

- [ ] Ambiguous: the design does not specify (a) how the `entity-resolution`
  skill's method is delivered to the helper session's prompt, or (b) the
  output contract the helper model must produce and how the augmenter derives
  `:success`/`:no-op` and the rendered `:content` from it. On (a): psi's
  existing skill-invocation path (`psi.prompt-assets.skills/invoke-skill`,
  wired through `prompt_request.clj`) expands a skill only on
  matching/explicit invocation text in the *user's own message* — it is not
  "the model reads `SKILL.md` because it judged it relevant," and the
  original user text driving this turn was never authored to invoke
  `entity-resolution`. So `create-child-session`'s `:skill-names` option
  cannot be relied on to deterministically apply the skill's method to the
  helper session; the augmenter's own constructed helper system/user prompt
  must carry the method directly (verbatim skill content, a generated
  excerpt, or a hand-written paraphrase — undecided). This choice affects
  drift risk (a hand-written paraphrase can silently diverge from
  `.psi/skills/entity-resolution/SKILL.md`) and is exactly the kind of
  per-turn skill-application problem this task's Why section says it exists
  to remove, so it should not be left to implementation-time improvisation.
  On (b): the design says the helper model produces a `surface → canonical →
  evidence → confidence` mapping "restricted to sufficiently-unambiguous
  entries," and that the augmenter returns `:success` "when at least one
  confident mapping exists" — but not whether the model must emit a
  structured/parseable format the augmenter validates, or whether the
  model's raw text response becomes `:append-context-block`'s `:content`
  verbatim whenever non-empty/non-sentinel. Pin down the prompt-delivery
  mechanism and the expected helper-model output contract before/while
  implementing, since both affect testability of the confident-mapping vs.
  ambiguous-dropped vs. no-referring-expression acceptance-criteria cases.
