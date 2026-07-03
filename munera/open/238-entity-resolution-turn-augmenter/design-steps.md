# Design steps — architectural fit follow-ups

- [x] `psi.ai.model-selection` / the model registry (`components/ai/src/psi/ai/model_selection.clj`,
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

- [x] Ambiguous: the design does not specify (a) how the `entity-resolution`
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

- [x] Inconsistent: the Goal section frames both reused mechanisms as
  "already-shipped," specifically citing "the local-model helper-session
  pattern from `extensions/auto-session-name` (model selection ...,
  `create-child-session` + `run-agent-loop-in-session`, and
  `helper-session-ids` recursion avoidance)." But the pattern this task
  actually needs — a helper session that calls a read-only search toolset to
  gather evidence — is not what's shipped: `auto-session-name`'s helper
  session runs with `:tool-ids []` (no tools at all; a toolless single-shot
  title-inference completion). The "minimal read-only search toolset (file
  read + directory list + content grep)" required by Required behaviour
  item 3 / Resolved decision 2 / Constraints is new work — today only a
  single-file `read` tool exists without `bash`
  (`make-read-only-tools-with-cwd` in
  `components/agent-session/src/psi/agent_session/tools.clj`), with no
  directory-list or grep tool. Reconcile the Goal's "already-shipped" framing
  with the fact that the tool-enabled evidence-gathering half of the pattern
  is new, so a later reader/implementer doesn't under-scope the toolset work
  based on the Goal section alone.

- [x] Inconsistent: the "no-op" requirement list and the "Tests" list in
  Acceptance criteria don't match. The no-op requirement says: "The augmenter
  returns a well-formed `:no-op` (no operations) for: tracked helper
  sessions, blank effective-cwd, prompts with no detectable referring
  expression, no confident mapping, and **failed/empty helper runs**." The verbatim
  "Tests (Scry-first) cover" list immediately below enumerates: confident
  single mapping → success block; no referring expression → no-op;
  helper-session recursion no-op; blank cwd no-op; no-local-model → no-op;
  ambiguous reference dropped; and replay reuse — with **no test for
  failed/empty helper runs → no-op**, even though that scenario is a
  distinct code path (helper-session-run failure/empty result handling, not
  mapping-confidence filtering) called out one paragraph earlier. Add a
  failed/empty-helper-run → no-op test to the Tests list, or state why it's
  not needed.

- [ ] Ambiguous: "embeds the `entity-resolution` skill's method directly ...
  (verbatim `.psi/skills/entity-resolution/SKILL.md` content, included as
  part of the helper system/user prompt the augmenter builds)" (Resolved
  decision 6) does not say whether the *whole file* is embedded verbatim or
  only its "Method" steps (1–5). This matters because the skill file's own
  "Output Shape" section prescribes a markdown table plus prose "final
  response" framing ("Interpreting 'that workflow' as ... because..."), and
  its "Act or ask" step (6) explicitly instructs: "If multiple candidates
  remain plausible, ask a focused clarification question... If no candidate
  is evidenced, say what was searched and ask for the missing identifier" —
  both directly conflicting with the augmenter's required non-interactive,
  parse-only-a-fixed-line-format output contract (also decision 6), and with
  237's "no interactive pre-turn prompts" exclusion. Two different
  interpretations of "embeds ... directly" lead to different prompt
  construction: (a) embed the full file verbatim and rely on the model
  reconciling the conflicting output instructions unaided (any "question" or
  table output the model produces is silently discarded as
  non-matching-format commentary, per decision 6's "discards everything
  else"), or (b) embed only the reasoning-method portion and have the
  augmenter's own prompt separately state the required output contract,
  omitting/overriding the skill's own Output Shape and Act-or-ask framing.
  Pin down which is intended (or state that (a) is acceptable because
  mismatched output is already a no-op via decision 6's zero-parsed-lines
  rule) so the helper-prompt-construction step isn't improvised.

- [ ] Ambiguous: "Remaining v1 policies" says the eligibility pre-filter
  should "skip slash-command-only prompts, mirroring `auto-session-name`'s
  guards, before spending a helper run." `auto-session-name`'s actual guard
  (`slash-command-text?` in `extensions/auto_session_name.clj`) only filters
  individual conversation *lines* out of its rename-inference excerpt; it
  does not skip its own checkpoint/helper run when the *current* turn is a
  slash command (checkpoint firing is gated only by `checkpoint-due?`,
  independent of message content). So "mirroring auto-session-name's guards"
  does not name an existing whole-run-skip mechanism to copy, leaving the
  exact detection rule for a *turn-level* slash-command-only skip
  unspecified (e.g., is it "trimmed user text starts with `/`", matching
  `slash-command-text?`'s definition, applied at a different granularity than
  its current use?). This eligibility condition is also absent from Required
  behaviour item 5's no-op enumeration, the Acceptance criteria no-op list,
  and the Tests list, so it's unclear whether it needs its own diagnosable
  no-op reason/test or is expected to collapse into an existing one. Specify
  the exact slash-command-only detection rule and where it fits among the
  enumerated no-op reasons/tests.
