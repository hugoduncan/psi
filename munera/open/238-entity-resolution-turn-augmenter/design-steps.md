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

- [x] Ambiguous: "embeds the `entity-resolution` skill's method directly ...
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

- [x] Ambiguous: "Remaining v1 policies" says the eligibility pre-filter
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

- [x] Inconsistent: design.md states the composition of the final rendered
  `:append-context-block` `:content` differently in three places, and these
  were not reconciled when Resolved decision 6 was added. Acceptance
  criteria says the block carries "the `surface → canonical` mapping" (2
  fields: no evidence, no confidence). "Remaining v1 policies" ("Confidence
  gate & output shape") says rendered `:content` "is a compact `surface →
  canonical` list with brief evidence" (3 fields: adds evidence, still no
  confidence). Resolved decision 6 defines the *parsed per-line* format as
  `surface → canonical (evidence; confidence)` (4 fields, including
  confidence) and says `:content` "is re-rendered from the parsed confident
  mappings" — without stating whether the rendered output keeps all 4
  parsed fields or drops confidence (since confidence's only stated role
  elsewhere is as the accept/reject gate, not display). A reader cannot tell
  from design.md alone whether the shipped `:content` shows 2, 3, or 4
  fields per mapping. Pick one composition and align Acceptance criteria,
  "Remaining v1 policies," and Resolved decision 6 to state it the same way.

- [x] Ambiguous: Resolved decision 6 embeds the `entity-resolution` skill's
  Method section (steps 1–5) **verbatim** into the helper prompt, but those
  verbatim steps explicitly instruct evidence-gathering via commands/capabilities
  that the helper's toolset (Resolved decision 4) does not provide. Step 1
  says to check "current git status"; step 3 says to "Search authoritative
  project surfaces when not obvious: `git ls-files` / `find` for paths.
  `git grep` for terms, vars, namespaces, workflow ids, commands, and docs.
  Psi graph introspection for runtime/session entities when applicable."
  The helper session's actual toolset per decision 4 is only file read +
  directory list + content grep — no bash/git-command execution tool and no
  EQL/psi-graph introspection tool. Design.md does not say whether (a) the
  Method text is embedded exactly as written, leaving the model to figure
  out on its own that it must substitute its available read/list/grep tools
  for the named git/find/graph-introspection commands (risking failed
  tool-call attempts against nonexistent tools, burning part of the
  blocking, no-deadline pre-turn budget from Resolved decision 3), or (b)
  the augmenter's prompt-construction step must adapt/annotate the embedded
  Method text so its evidence-gathering instructions name only the tools
  actually available to the helper session. This affects both correct
  prompt construction and the testability of the "confident mapping via
  gathered evidence" acceptance scenario. Pin down whether the embedded
  Method text is used exactly as authored in `SKILL.md` or is adapted to
  reference only the helper's actually-available read-only tool names.

- [x] Ambiguous: Resolved decision 6's adaptation policy says the
  augmenter's prompt-construction step "rewords those references to name
  only the helper's actually-available read-only capabilities (read a file,
  list a directory, grep file contents)" for every Method sub-step that
  names a tool the helper toolset lacks. That rewording works cleanly for
  two of the four flagged references — `git ls-files`/`find` (step 3) has a
  natural directory-list substitute, and `git grep` (step 3) has a natural
  content-grep substitute — but not for the other two: step 1's "current
  git status" and step 3's "Psi graph introspection for runtime/session
  entities" name capabilities with **no** read/list/grep equivalent at all
  (working-tree change state and runtime/session-graph queries are not
  obtainable by reading a file, listing a directory, or grepping content,
  no matter how the instruction is worded). Design.md doesn't say what
  "adapted" means for this unmappable subset: (a) drop that specific
  sub-instruction from the embedded text entirely (since no available tool
  can fulfill it), (b) reword it anyway to point at read/list/grep even
  though doing so cannot actually satisfy the original instruction's intent
  (e.g., telling the model to "list the directory" in place of "check git
  status" doesn't tell it anything about uncommitted changes), or (c) leave
  it worded as a capability gap the model should treat as unavailable
  evidence and reason around. This matters because step 1's git-status
  reference is explicitly tied to "path or task references," which are
  squarely within this augmenter's in-scope entity types (per the Goal
  section's "paths, tasks, ..." list) — so the gap isn't hypothetical, it's
  a real evidence source the Method text currently implies is available
  when it structurally is not, given decision 4's frozen toolset. Pin down
  which of (a)/(b)/(c) the adaptation intends for the git-status and
  graph-introspection sub-references specifically, not just for the two
  substitutable ones.

- [x] Ambiguous: Resolved decision 6's capability-gap statement (added by
  `b37363b71` to resolve the previous unmappable-sub-reference item) tells
  the model it "must reason about path/task/**session** references using
  only file contents it can read, list, or grep" — but "session" does not
  appear anywhere else in design.md as a resolvable entity type. The Goal
  section's explicit entity-type list is "paths, tasks, workflows, skills,
  extensions, namespaces, vars, commands, docs, vocabulary symbols" — no
  sessions — and Required behaviour, Constraints, and Acceptance criteria
  never mention sessions as something this augmenter maps or emits mappings
  for. The word appears to be carried over from the `entity-resolution`
  skill's own step 3 wording ("Psi graph introspection for runtime/session
  entities") without checking whether "session" belongs in this augmenter's
  narrower, tool-constrained entity-type scope. This leaves it unclear
  whether (a) sessions are actually an in-scope entity type this augmenter
  should attempt to resolve via file-based evidence when a user references
  one (in which case the Goal section's entity-type list is incomplete), or
  (b) "session" should not appear in the capability-gap prompt text at all
  since this augmenter never resolves session references (in which case the
  capability-gap wording overclaims what the model is being asked to
  attempt). Pin down whether sessions are in or out of this augmenter's
  entity-type scope and align the Goal section's entity-type list and
  Resolved decision 6's capability-gap wording to agree.

- [x] Inconsistent: the Goal section and Required behaviour item 3 both
  describe the Method-text adaptation as uniformly "naming only the
  helper's actually-available read-only tools" in place of the skill's
  original git/graph-introspection references — Goal: "adapted so
  evidence-gathering wording names only the helper's actually-available
  read-only tools rather than the skill's original git/find/graph-
  introspection references"; Required behaviour item 3: "embeds the
  `entity-resolution` method, adapted to name only that toolset." Both
  passages were written by `d1db1d86e` when Resolved decision 6's
  adaptation policy was a single uniform reword-to-available-tool-name
  rule, and neither was updated when `b37363b71` later split that policy
  into two cases: the two *substitutable* references (`git ls-files`/`find`,
  `git grep`) are reworded to name the available tool directly, matching
  Goal/item 3's description, but the two *unmappable* references ("current
  git status," "Psi graph introspection") are explicitly **not** reworded
  to name an available tool — decision 6 says doing so "would misleadingly
  imply those tools can answer a question they can't," and instead
  substitutes a qualitatively different capability-gap disclosure
  ("the prompt tells the model it cannot check git status ... or query the
  runtime/session graph, and must reason ... using only file contents").
  A reader who reads only the Goal section or Required behaviour item 3
  (without cross-checking decision 6's amended detail) would form the
  inaccurate model that every original tool reference is 1:1 substituted
  with an available-tool name, missing that two of the four references
  instead get an explicit "you cannot do this" disclosure. Update Goal and
  Required behaviour item 3's summary wording to reflect decision 6's
  two-case split (or explicitly note the summary is intentionally
  simplified and defer full detail to decision 6), so the three passages
  agree on what "adapted" means.

- [ ] Ambiguous: Required behaviour item 1 says the augmenter "reads the
  bounded turn projection (user text + history tail + effective-cwd)," and
  the Context section confirms `:turn-augmentation/history` (a bounded
  tail) is part of the 237 input contract the augmenter receives. But
  Required behaviour item 3 — the step that actually builds the helper
  session's prompt — says only that the prompt "embeds the
  `entity-resolution` method ... and applies it to **the user text**,"
  with no mention of the history tail being included in what the helper
  model sees. Grepping design.md for "history" turns up only these two
  passages; no other passage says whether the read history tail is ever
  incorporated into the constructed helper prompt content, or is read but
  unused. This is not a cosmetic gap: the embedded `entity-resolution`
  skill method's own step 1 ("Collect local context") lists "Current user
  turn and immediately relevant conversation history" as context to
  collect, and the skill's stated referring-expression types explicitly
  include anaphora ("it", "this", "that", "those", "the former/latter")
  that are frequently only resolvable by looking at prior turns — the
  Goal section's own opening sentence describes resolving "ambiguous/
  underspecified references," which for pronouns/deixis structurally
  requires prior-turn context. If the helper prompt the augmenter
  constructs carries only the bare current-turn user text with no history
  excerpt, the helper model has no way to resolve anaphoric references at
  all, undercutting a class of reference the design otherwise claims to
  handle. Pin down whether the constructed helper prompt includes a
  rendered history-tail excerpt (and if so, in what form — similar to
  `auto-session-name`'s `build-rename-prompt`/`sanitize-session-entries`
  conversation-excerpt pattern) or whether the design intentionally scopes
  this augmenter's resolution to current-turn text only despite reading
  the history tail for some other stated or unstated reason.

- [ ] Ambiguous: Required behaviour item 2 says the augmenter "selects a
  local helper model via `model-selection`" (singular), and Acceptance
  criteria describes "a helper session driven by a **local** model selected
  via `psi.ai.model-selection`" — both read as selecting one candidate.
  Design.md is silent on what happens if that single selected model's
  helper run fails or returns an unusable result while `resolve-selection`
  had *other* tool-calling-capable local candidates ranked below it. The
  design explicitly frames the local-model helper-session mechanism it
  reuses as `auto-session-name`'s pattern (Goal section, References), and
  that mechanism's actual behavior (`select-helper-models` /
  `infer-session-title` in
  `extensions/auto-session-name/src/extensions/auto_session_name.clj`) is
  not "pick the top-ranked candidate and stop" — it takes the *entire*
  ranked candidate list from `resolve-selection` and loops through it,
  retrying the next-ranked model whenever an attempt fails or returns an
  invalid result, only giving up once every ranked candidate has been
  tried. Required behaviour item 5 and Acceptance criteria both list
  "failed/empty helper runs" as an unconditional `:no-op` trigger without
  saying whether that means "the single attempted model failed" or "every
  ranked tool-calling-capable local candidate was tried and failed." This
  changes both the shipped no-op rate under transient single-model failures
  and the shape of the "failed/empty helper run → no-op" test (one
  synthetic failing model vs. an exhausted ranked list of failing models),
  and interacts with Resolved decision 3's "blocking, no-deadline" latency
  posture (retrying across multiple local models on the critical path costs
  more latency than one attempt). Pin down whether this augmenter retries
  across `resolve-selection`'s ranked candidate list like
  `auto-session-name` does, or deliberately simplifies to a single
  top-ranked attempt with immediate `:no-op` on failure — and if the latter,
  note that as a deliberate departure from the cited precedent rather than
  leaving it implicit.
