# 238 — Automatic entity resolution turn augmenter

## Goal

Automatically resolve ambiguous/underspecified references in a user's submitted
prompt into concrete project entities (paths, tasks, workflows, skills,
extensions, namespaces, vars, commands, docs, vocabulary symbols) and inject
that resolution as pre-turn context, so the parent turn sees an evidence-backed
`surface → canonical` mapping before it acts.

Reuse two already-shipped mechanisms, extended with small additive wiring
this task must add — the second augmenter registration plus helper-prompt
construction (Resolved decision 6) and the existing-`bash`-tool grant via
`create-child-session` (Resolved decision 2). (Resolved decisions 4 and 5 are
deliberate non-additions — no new read-only tools, no new model-selection
tool-calling fact — not wiring this task adds.)

1. the **pre-turn request augmentation** mechanism from closed task
   `237-pre-turn-request-augmentation` (the `:register-turn-augmenter` API,
   `:psi.capability/turn-augmentation`, the `:append-context-block` operation,
   and the `extensions/context-manager` scaffold);
2. the **local-model helper-session mechanism** from
   `extensions/auto-session-name` (model selection with a strong `:locality
   :local` preference, `create-child-session` + `run-agent-loop-in-session`,
   and `helper-session-ids` recursion avoidance) — unlike
   `auto-session-name`'s toolless single-shot title helper, this task creates
   a tool-enabled helper by granting the existing `bash` tool (Resolved
   decisions 2, 4, and 5). It deliberately does **not** add new read-only
   tools, and it does **not** add a new model-selection tool-calling
   fact/criterion.

The reasoning method is the existing `entity-resolution` skill
(`.psi/skills/entity-resolution/SKILL.md`): detect referring expressions →
gather evidence → produce a `surface → canonical → evidence → confidence`
mapping → include only unambiguous mappings. The skill's Method section
(steps 1–5 only, not its Output Shape / Act-or-ask sections) is delivered
to the helper session by embedding it in the augmenter's constructed helper
prompt, with evidence-gathering wording adapted to the helper's actual
`bash` capability (Resolved decision 6) — not via `create-child-session`'s
`:skill-names`, which only auto-expands a skill when the *user's own
submitted text* matches/invokes it, and the parent-turn user text driving
this helper session is never authored to invoke `entity-resolution`.

## Why

- The `entity-resolution` skill currently only fires when the model chooses to
  invoke it. Making resolution an automatic pre-turn phase means every eligible
  parent turn benefits without relying on the parent model to remember the
  skill.
- Task 237 built the augmentation rail specifically so extensions can propose
  turn-scoped context as data; the shipped `context-manager` only emits a
  trivial "Working directory: <cwd>" block. This task is the first substantive,
  model-backed augmenter and the intended proof that the rail carries real work.
- Using a local model keeps this cheap and private: it runs on every eligible
  turn, so it must not add cloud cost or meaningful latency, mirroring the
  `auto-session-name` cost/latency posture.

## Context / current state

- `extensions/context-manager/src/extensions/context_manager.clj` registers one
  augmenter, `:augmenter-id "project-context"`, returning a single
  `:append-context-block`. It already tracks a `helper-session-ids` atom (unused
  today) and no-ops for helper sessions.
- The augmenter receives the bounded v1 input contract (design of 237):
  `:turn-augmentation/user-text`, `:turn-augmentation/user-message`,
  `:turn-augmentation/effective-cwd`, `:turn-augmentation/session`,
  `:turn-augmentation/history` (bounded tail), `:turn-augmentation/session-id`,
  `:turn-augmentation/turn-id`, `:turn-augmentation/workflow-run-id`. It does
  **not** receive `ctx`, atom handles, tools, or credentials.
- `psi.ai.model-selection/resolve-selection` returns a ranked candidate list;
  `auto-session-name` requests `:latency-tier :low`, `:cost-tier #{:zero :low}`,
  strong `:locality :local`, and inherits the source session's model as context.
- Helper sessions are ordinary sessions created via
  `psi.extension/create-child-session` and driven with
  `psi.extension/run-agent-loop-in-session`; the parent `session-id` is the
  allocation reference. Recursion is avoided by remembering helper ids and
  returning `:no-op` when the augmenter is invoked for one of them.
- Model registry supports `:locality :local` (see `psi.ai.models` /
  `psi.ai.user_models`), so a local-only requirement is expressible.

## Required behaviour

A second turn augmenter registered by the existing `extensions/context-manager`
extension (new `:augmenter-id "entity-resolution"`, alongside the current
`"project-context"` augmenter) that, for an eligible parent turn:

1. reads the bounded turn projection (user text + history tail + effective-cwd);
2. selects a local helper model via `model-selection` (strong `:locality
   :local`, low latency, zero/low cost), inheriting the parent session's
   model as context, like `auto-session-name` — but attempting only the
   single top-ranked candidate, not retrying across `resolve-selection`'s
   ranked list (see "Remaining v1 policies" → "Single-attempt model
   selection");
3. runs a helper session — created with access to the existing `bash` tool
   only (Resolved decisions 2, 4, and 5) so the local model can gather
   evidence from the worktree using ordinary shell commands under the
   effective cwd, running as a bounded multi-round agent loop (see
   "Remaining v1 policies" → "Bounded helper agent loop") — whose prompt
   embeds the `entity-resolution` method,
   adapted per Resolved decision 6 to permit `bash`-based evidence gathering,
   and applies it to the user text plus a rendered history-tail excerpt (see
   "Remaining v1 policies" → "History-tail inclusion"), producing output in
   the structured line format from Resolved decision 6, restricted to
   sufficiently-unambiguous entries;
4. returns a `:success` envelope with one `:append-context-block`
   (`:id "entity-resolution"`, `:title "Resolved entities"`, `:content` = the
   mapping re-rendered from the parsed confident lines, per Resolved
   decision 6) when at least one confident mapping is successfully parsed;
5. returns a well-formed `:no-op` envelope (no operations) when the turn is a
   tracked helper session, when there is no effective cwd, when the prompt
   is slash-command-only (pre-filter, see "Remaining v1 policies") or when
   no referring expressions are otherwise detected, when no confident
   mapping is produced or parsed, when no local model is available, or when
   the helper run fails/does not return a usable result;
6. tracks its helper session ids and cleans them up (close on completion) as
   `auto-session-name` does, so it never augments its own helper turns.

The augmenter returns **data only** (per 237): it must not mutate the parent
request or parent session, must omit `:source` (core injects provenance), and
its helper-session ids go in `:turn-augmentation/child-session-ids` as
provenance.

## Resolved decisions

1. **Host — inside `context-manager` (for now).** The entity-resolution
   augmenter is registered as a second `:augmenter-id "entity-resolution"` by
   the existing `extensions/context-manager` extension, reusing its
   `:psi.capability/turn-augmentation` grant. It maintains its own helper-session
   id tracking (distinct from any `project-context` helper tracking) so
   recursion avoidance is per-augmenter-correct. A dedicated extension may be
   split out later; not in this task.

2. **Evidence — local model + `bash`.** The helper session is created with
   access to the existing `bash` tool and the local model gathers evidence
   itself by running ordinary shell commands from the effective cwd, following
   the `entity-resolution` skill method. No deterministic pre-gathered corpus
   and no new read/list/grep toolset are required in v1. The helper prompt
   must instruct the model to avoid mutating commands and to use `bash` only
   for evidence gathering.

3. **Latency — accept the local model on the critical path.** 237 makes pre-turn
   augmentation a blocking, no-deadline barrier and this task keeps the
   local helper run there. No heuristic-only / model-deferred fallback path is
   built in v1; cheap eligibility pre-filters (below) reduce, but do not replace,
   the helper run. Local-only + zero/low cost selection keeps it inexpensive and
   private. Because granting `bash` (Resolved decision 2) makes the helper a
   multi-round tool-using agent loop rather than a single toolless model call,
   "no-deadline" here means the augmenter imposes no wall-clock deadline of its
   own on the barrier — it does **not** mean the helper loop itself is
   unbounded. The helper agent loop is bounded (see "Remaining v1 policies" →
   "Bounded helper agent loop"), so worst-case blocking latency per eligible
   turn stays finite.

4. **No new read-only tools.** This task does not add directory-list,
   content-grep, git-aware search, or any other new read-only tools. The
   helper session uses the existing `bash` tool as its evidence-gathering
   interface. Any command safety is handled by prompt constraints and by
   the fact that the helper is a child session created specifically for this
   bounded pre-turn resolution job; there is no new special-purpose
   filesystem tool surface in scope.

5. **No new model-selection tool-calling fact/criterion.** This task does
   not change `psi.ai.model-selection`, `psi.ai.models`, or
   `psi.ai.user_models` to add a tool/function-calling capability fact. The
   augmenter selects a local helper model using the existing locality,
   latency, and cost criteria. If the selected local model cannot use the
   granted `bash` tool well enough to produce parseable confident mappings,
   that is treated as an ordinary failed/empty helper run and yields the
   existing well-formed `:no-op` fallback.

6. **Skill delivery and helper output contract.** The augmenter's
   constructed helper-session prompt embeds only the `entity-resolution`
   skill's **Method section (steps 1–5)** from
   `.psi/skills/entity-resolution/SKILL.md` — not the whole file. The
   skill's "Output Shape" section (internal reasoning table plus prose
   "final response" framing) and step 6 ("Act or ask," which instructs
   asking a clarification question or asking for a missing identifier) are
   **not** embedded: both conflict with this augmenter's non-interactive,
   parse-only-a-fixed-line-format contract and with 237's exclusion of
   interactive pre-turn prompts. Within the embedded Method steps, evidence
   gathering is adapted to the helper's actual `bash` access: references to
   shell-oriented discovery such as `git status`, `git ls-files`, `find`, and
   `git grep` may remain available as commands the helper can run from the
   effective cwd, while unavailable runtime/session graph introspection is
   replaced with an explicit capability-gap disclosure. Sessions are not a
   resolvable entity type for this augmenter — the "runtime/session graph"
   phrase names the skill's original unavailable evidence source, not an
   in-scope output entity type — so no session-related mapping is ever
   produced. The prompt must also instruct the helper that `bash` is for
   evidence gathering only and that it must not mutate files, install
   dependencies, start long-running processes, or perform unrelated side
   effects. In place of the
   excluded Output Shape / Act-or-ask sections, the augmenter's own prompt
   states the required output contract directly: a structured line format,
   one line per confident mapping, `surface → canonical (evidence;
   confidence)`; the augmenter parses lines matching this format from the
   helper's raw response and discards everything else (preamble, commentary,
   malformed lines, any clarification-question-shaped text). The confidence
   token is **model-self-gated, not augmenter-value-thresholded**: the model
   emits a line only for a mapping it judges confident (per the "never guess —
   only confident, evidence-backed mappings" constraint), and the augmenter
   accepts *every* well-formed parsed line as confident without comparing the
   token against any fixed scale/vocabulary or numeric threshold. The
   confidence token remains a **required** field of the line format — the
   model must state its confidence explicitly, which reinforces the
   self-gating discipline — but its value is model-authored text the augmenter
   neither validates nor displays. There is intentionally no
   confidence-scale/threshold machinery in v1: the accept/reject boundary is
   the model choosing whether to emit the line at all, not augmenter-side
   value comparison. Skill delivery
   is not via `create-child-session`'s `:skill-names` — that option
   auto-expands a skill only when the *user's own submitted text* matches or
   invokes it, and the parent-turn user text driving this helper session is
   never authored to invoke `entity-resolution`.

   The rendered `:append-context-block` `:content` is re-rendered from the
   parsed confident mappings as a compact `surface → canonical (evidence)`
   list — **three** fields per mapping, dropping confidence. Confidence's
   only role is to make the model's self-gating explicit (it emits a line
   only for mappings it judges confident); the augmenter does not threshold
   or display the token, and every well-formed parsed line is kept. Zero
   successfully-parsed lines is treated as "no confident
   mapping" and yields the `:no-op` in Required behaviour item 5.

### Remaining v1 policies (settled, low-risk)

- **Eligibility pre-filter.** Skip tracked helper sessions and blank-cwd turns
  (per 237 scaffold), and skip slash-command-only prompts before spending a
  helper run. "Slash-command-only" uses the same predicate as
  `auto-session-name`'s `slash-command-text?` (trimmed text is non-empty and
  starts with `/`), applied to the *whole turn's*
  `:turn-augmentation/user-text` rather than to individual conversation
  lines (which is how `auto-session-name` uses it — to filter lines out of
  its rename-inference excerpt, not to skip its own run). This is a
  turn-level reuse of an existing line-level predicate, not an existing
  whole-run-skip mechanism. A slash-command-only turn is treated as having
  no detectable referring expression, so it collapses into the "no
  referring expression" `:no-op` reason in Required behaviour item 5 and
  Acceptance criteria — but because it is a distinct pre-filter code path
  (skipped *before* selecting a model or spending a helper run, unlike the
  model-determined "no referring expression" outcome), it gets its own
  entry in the Tests list asserting the helper session is never created for
  such prompts.
- **History-tail inclusion.** The helper prompt (Required behaviour item 3)
  applies the `entity-resolution` method to the current-turn user text
  *plus* a rendered excerpt of the read history tail
  (`:turn-augmentation/history`, Required behaviour item 1) — not
  current-turn text alone. This is required for the embedded method's
  anaphora-resolution guidance ("it", "this", "that", "those", "the
  former/latter") to be actionable, since anaphora is only resolvable
  against prior-turn context, and the Goal section's "ambiguous/
  underspecified references" claim structurally includes such references.
  Excerpt construction (format, truncation) is a prompt-construction detail
  left to plan/implementation, mirroring `auto-session-name`'s
  `build-rename-prompt`/`sanitize-session-entries` conversation-excerpt
  precedent, at the same "e.g."/policy-level granularity already used by
  Resolved decisions 4–6.
- **Single-attempt model selection.** The augmenter selects and attempts
  only the single top-ranked local candidate returned by `resolve-selection`
  (Required behaviour item 2); it does not retry the
  next-ranked candidate if that attempt fails or returns an unusable
  result — a failed/empty helper run from the one attempted candidate goes
  straight to `:no-op` (Required behaviour item 5 / Acceptance criteria).
  This deliberately departs from `auto-session-name`'s
  `select-helper-models`/`infer-session-title` behaviour, which retries
  across its entire ranked candidate list until one succeeds or all are
  exhausted; this task instead bounds the blocking, no-deadline critical
  path (Resolved decision 3) to at most one local-model attempt per turn,
  consistent with Resolved decision 5's precedent of trading away
  `auto-session-name` feature parity for v1 simplicity. The
  "failed/empty helper run → no-op" test (Tests list) exercises this single
  attempted candidate failing, not an exhausted ranked list.
- **Bounded helper agent loop.** Granting the existing `bash` tool (Resolved
  decision 2) makes the helper session a multi-round tool-using agent loop:
  `run-agent-loop-in-session` runs the prompt lifecycle until the model stops
  issuing tool calls, and each `bash` command is already capped at the existing
  per-command timeout, but the *number* of agent-loop rounds / commands is not
  otherwise bounded. Since this loop sits on Resolved decision 3's blocking,
  per-eligible-turn critical path, the augmenter bounds the helper run so
  worst-case latency is finite: it caps the helper agent loop with an upper
  bound on rounds (and, if needed, a total wall-clock budget for the whole
  helper run), and treats hitting that bound as an unusable/failed helper run
  that collapses into the `:no-op` of Required behaviour item 5 (same path as
  any other failed/empty helper run). The exact bound (round count and/or
  wall-clock budget) is a policy-level decision left to plan/implementation at
  the same "e.g."/policy granularity as Resolved decisions 4–6 — design.md
  fixes only that a finite bound exists and that exceeding it yields `:no-op`,
  not the literal numbers.
- **Confidence gate & output shape.** Only sufficiently-unambiguous mappings
  enter the block; ambiguous/unevidenced references are dropped, never
  guessed. The gate is **model self-gating**, not an augmenter-side value
  threshold: the model emits a line only for mappings it judges confident,
  and the augmenter accepts every well-formed parsed line without validating
  the confidence token against a fixed scale/threshold (see Resolved decision
  6). Rendered `:content` is a compact `surface → canonical (evidence)` list
  — see Resolved decision 6 for the exact three-field composition and why
  confidence is stated-but-not-displayed; the raw user prompt is always
  preserved.
- **Model-absent fallback.** If `resolve-selection` yields no local winner,
  the augmenter returns a well-formed `:no-op`; it never falls back to a
  cloud model on every turn.

## Constraints

- Preserve the 237 dispatch/effect and data-only-extension boundaries: no
  parent-request or parent-state mutation; augmenter returns an envelope; helper
  work uses existing extension session APIs only (no new child/run API).
- Preserve deterministic replay: the augmenter runs only live; recorded
  accepted operations are replayed without re-invoking the model (already
  guaranteed by 237 — do not weaken it).
- Local-first: helper model selection must strongly prefer `:locality :local`
  and zero/low cost, like `auto-session-name`; never silently use a cloud
  model on every turn.
- No new read-only/search tools and no new model-selection capability facts are
  in scope for this task; the helper's tool grant is the existing `bash` tool.
- Recursion safety: track and no-op for the augmenter's own helper session ids.
- Helper prompt constrains `bash` use to evidence gathering and forbids
  intentional mutation or unrelated side effects.
- Never guess: only confident, evidence-backed mappings are injected; ambiguity
  is dropped, not collapsed.
- Follow project change_chain: meta/spec/tests/code/doc coherence; malli for
  schemas; Scry-first tests.

## Acceptance criteria

- An entity-resolution turn augmenter is registered through
  `:register-turn-augmenter` and gated by `:psi.capability/turn-augmentation`
  (host extension declares the capability in its manifest/effective
  permissions).
- For a parent turn whose user text contains a referring expression that maps to
  exactly one strongly-evidenced project entity, the prepared request contains a
  `:turn/augmentation-context` block (via `:append-context-block`
  `:id "entity-resolution"`) carrying the `surface → canonical (evidence)`
  mapping (Resolved decision 6's three-field rendered composition), inserted
  before the current user message.
- The augmenter uses a helper session driven by a **local** model selected via
  `psi.ai.model-selection` with a strong `:locality :local` preference, and
  the helper session is created with access to the existing `bash` tool only;
  when no local model is available it returns a well-formed `:no-op` and no
  cloud model is used.
- No new directory-list/content-grep/read-only toolset is introduced, and no
  new `psi.ai.model-selection` tool/function-calling capability fact or
  criterion is introduced.
- The augmenter returns a well-formed `:no-op` (no operations) for: tracked
  helper sessions, blank effective-cwd, slash-command-only prompts
  (pre-filter, before spending a helper run), prompts with no detectable
  referring expression, no confident mapping, no local model, and
  failed/empty helper runs.
- Helper sessions the augmenter creates are tracked, reported in
  `:turn-augmentation/child-session-ids`, cleaned up, and never themselves
  augmented (recursion avoidance verified).
- Ambiguous or unevidenced references are dropped from the mapping and never
  guessed; the raw user prompt is always preserved.
- Replaying the turn reuses the recorded operation and does not re-invoke the
  local model (inherited 237 guarantee, asserted by a test).
- Tests (Scry-first) cover: confident single mapping → success block; no
  referring expression → no-op; slash-command-only prompt → no-op with no
  helper session created (pre-filter, distinct from the model-determined no
  referring expression path); helper-session recursion no-op; blank cwd
  no-op; no-local-model → no-op; ambiguous reference dropped (the helper
  model omits the ambiguous mapping under its self-gating constraint — the
  augmenter exercises no confidence-value threshold, so the assertion is that
  no line is emitted/parsed for the ambiguous surface, not that a
  low-confidence line is filtered);
  failed/empty helper run → no-op; and replay reuse.
- Docs updated to describe automatic entity resolution as a context-manager /
  entity-resolution augmenter capability.

## Out of scope

- Changing the 237 augmentation mechanism, statechart lifecycle, capability
  gating, or operation vocabulary.
- New operation types beyond `:append-context-block`.
- Cloud-model resolution, multi-model ensembles, or resolution caching across
  turns.
- Interactive clarification prompts during pre-turn augmentation (237 explicitly
  excludes interactive pre-turn prompts).
- Rewriting the submitted user prompt or acting on resolved entities; this task
  only injects context.

## References

- `munera/closed/237-pre-turn-request-augmentation/design.md` — augmentation
  mechanism, input contract, envelope, rendering, replay.
- `extensions/context-manager/src/extensions/context_manager.clj` — augmenter
  scaffold to extend or model after.
- `extensions/auto-session-name/src/extensions/auto_session_name.clj` —
  local-model helper-session pattern and recursion avoidance.
- `components/ai/src/psi/ai/model_selection.clj` — `resolve-selection` request
  shape (locality/latency/cost criteria).
- `.psi/skills/entity-resolution/SKILL.md` — resolution method source; its
  Output Shape section is explicitly not used by this augmenter.
