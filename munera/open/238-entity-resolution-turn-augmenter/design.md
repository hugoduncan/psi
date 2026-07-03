# 238 — Automatic entity resolution turn augmenter

## Goal

Automatically resolve ambiguous/underspecified references in a user's submitted
prompt into concrete project entities (paths, tasks, workflows, skills,
extensions, namespaces, vars, commands, docs, vocabulary symbols) and inject
that resolution as pre-turn context, so the parent turn sees an evidence-backed
`surface → canonical` mapping before it acts.

Reuse two already-shipped mechanisms, extended with small additive pieces
this task must add (see Resolved decisions 4–6):

1. the **pre-turn request augmentation** mechanism from closed task
   `237-pre-turn-request-augmentation` (the `:register-turn-augmenter` API,
   `:psi.capability/turn-augmentation`, the `:append-context-block` operation,
   and the `extensions/context-manager` scaffold);
2. the **local-model helper-session mechanism** from
   `extensions/auto-session-name` (model selection with a strong `:locality
   :local` preference, `create-child-session` + `run-agent-loop-in-session`,
   and `helper-session-ids` recursion avoidance) — this mechanism itself is
   already-shipped. What is **not** already-shipped, because
   `auto-session-name`'s helper session is toolless (`:tool-ids []`,
   single-shot title inference), is the tool-enabled evidence-gathering half
   this task needs: a minimal read-only search toolset for the helper
   session (Resolved decision 4) and a tool-calling capability
   fact/criterion in `psi.ai.model-selection` so helper-model selection can
   filter on tool-calling support (Resolved decision 5). Both are new,
   additive work this task adds on top of the shipped mechanism, not
   already-shipped pieces being reused verbatim.

The reasoning method is the existing `entity-resolution` skill
(`.psi/skills/entity-resolution/SKILL.md`): detect referring expressions →
gather evidence → produce a `surface → canonical → evidence → confidence`
mapping → include only unambiguous mappings. The skill's Method section
(steps 1–5 only, not its Output Shape / Act-or-ask sections) is delivered
to the helper session by embedding it in the augmenter's constructed helper
prompt, adapted per Resolved decision 6's two-case split — sub-steps naming
a git/find reference with an available-tool substitute (directory listing,
content grep) are reworded to name that tool directly, while the two
sub-steps with no available-tool substitute (git status, graph
introspection) are replaced with an explicit capability-gap disclosure —
rather than embedding the skill's original git/find/graph-introspection
references verbatim (Resolved decision 6) — not via `create-child-session`'s
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
   :local`, low latency, zero/low cost, **and the tool-calling capability
   criterion from Resolved decision 5**), inheriting the parent session's
   model as context, like `auto-session-name` plus the added tool-calling
   filter — but attempting only the single top-ranked candidate, not
   retrying across `resolve-selection`'s ranked list (see "Remaining v1
   policies" → "Single-attempt model selection");
3. runs a helper session — created **with a minimal read-only search toolset**
   (Resolved decision 4) so the local model can gather evidence from the
   worktree's files (read / list / grep only — no git-command execution,
   per Resolved decision 6's capability-gap disclosure) — whose prompt
   embeds the `entity-resolution` method, adapted per Resolved decision 6's
   two-case split (available-tool substitution for substitutable
   references; explicit capability-gap disclosure for the two unmappable
   ones), and applies it to the user text plus a rendered history-tail
   excerpt (see "Remaining v1 policies" → "History-tail inclusion"),
   producing output in the structured line format from Resolved decision 6,
   restricted to sufficiently-unambiguous entries;
4. returns a `:success` envelope with one `:append-context-block`
   (`:id "entity-resolution"`, `:title "Resolved entities"`, `:content` = the
   mapping re-rendered from the parsed confident lines, per Resolved
   decision 6) when at least one confident mapping is successfully parsed;
5. returns a well-formed `:no-op` envelope (no operations) when the turn is a
   tracked helper session, when there is no effective cwd, when the prompt
   is slash-command-only (pre-filter, see "Remaining v1 policies") or when
   no referring expressions are otherwise detected, when no confident
   mapping is produced or parsed, when no tool-calling-capable local model
   is available, or when the helper run fails/does not return a usable
   result;
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

2. **Evidence — local model + tools.** The helper session is created **with a
   minimal read-only search toolset** (e.g. file read + directory list +
   content grep, no mutation/bash-write) and the local model searches the
   worktree's files for evidence itself, following the `entity-resolution`
   skill method. The toolset has no git-command execution, so "searching
   the worktree" means reading/listing/grepping git-tracked file contents,
   never running `git` (Resolved decision 6's capability-gap disclosure
   tells the model explicitly it cannot run git commands). No deterministic
   pre-gathered corpus is required in v1; the tool surface must be
   read-only and side-effect-free.

3. **Latency — accept the local model on the critical path.** 237 makes pre-turn
   augmentation a blocking, no-deadline barrier and this task keeps the
   local-model call there. No heuristic-only / model-deferred fallback path is
   built in v1; cheap eligibility pre-filters (below) reduce, but do not replace,
   the model call. Local-only + zero/low cost selection keeps it inexpensive and
   private.

4. **Read-only search toolset is new work, added by this task.** Today's
   built-in read-only toolset (`make-read-only-tools-with-cwd` in
   `components/agent-session/src/psi/agent_session/tools.clj`) exposes only
   a single-file `read` tool; there is no directory-list or content-grep
   tool. This task adds a minimal read-only search toolset — file read
   (existing) + directory list + content grep, no mutation/bash-write — for
   the helper session to use. This toolset addition is in scope for this
   task (not a pre-existing dependency to assume as already-shipped).

5. **Model selection gains an additive tool-calling capability
   fact/criterion, added by this task.** `psi.ai.model-selection` / the
   model registry (`psi.ai.models` / `psi.ai.user_models`) currently exposes
   only `:supports-text`, `:supports-images`, `:supports-reasoning`,
   `:locality`, `:context-window`, `:max-tokens`, and cost/latency-tier
   facts — no tool-calling fact, because `auto-session-name`'s toolless
   helper session never needed one. This task adds an additive tool-calling
   capability fact on model entries (e.g. `:supports-tool-calling`) and a
   corresponding selection criterion, and the entity-resolution augmenter's
   `resolve-selection` request sets that criterion. The addition must be
   additive-only (new optional fact/criterion key) so existing `:helper` /
   `:auto-session-name` role defaults and other `model-selection` callers
   are unaffected. If `resolve-selection` yields no tool-calling-capable
   local winner, that is treated the same as "no local model available"
   under decision 3's model-absent fallback: the augmenter returns a
   well-formed `:no-op` — v1 does not add a separate diagnostic
   distinguishing "no local model" from "local model but no tool support";
   both collapse to "no usable helper model for this turn."

6. **Skill delivery and helper output contract.** The augmenter's
   constructed helper-session prompt embeds only the `entity-resolution`
   skill's **Method section (steps 1–5)** from
   `.psi/skills/entity-resolution/SKILL.md` — not the whole file. The
   skill's "Output Shape" section (internal reasoning table plus prose
   "final response" framing) and step 6 ("Act or ask," which instructs
   asking a clarification question or asking for a missing identifier) are
   **not** embedded: both conflict with this augmenter's non-interactive,
   parse-only-a-fixed-line-format contract and with 237's exclusion of
   interactive pre-turn prompts. Within the embedded Method steps, the
   sub-steps whose wording names evidence-gathering commands the helper
   toolset does not expose (Resolved decision 4 is file read / directory
   list / content grep only, with no bash/git-command execution and no
   EQL/psi-graph introspection) are **adapted, not embedded verbatim**, and
   the adaptation splits into two cases depending on whether a read/list/grep
   substitute exists:
   - Step 3's `git ls-files`/`find` (directory listing) and `git grep`
     (content grep) have a natural substitute: those references are
     reworded to name the substitute capability directly (list a directory,
     grep file contents), so the model is never instructed to reach for a
     tool it doesn't have.
   - Step 1's "current git status" and step 3's "Psi graph introspection
     for runtime/session entities" have **no** read/list/grep substitute.
     These are **not** reworded to point at read/list/grep — doing so would
     misleadingly imply those tools can answer a question they can't.
     Instead they are replaced with an explicit statement of the capability
     gap: the prompt tells the model it cannot check git status, run git
     commands, or query the runtime/session graph, and must reason about
     path/task references (the two in-scope entity types, per the Goal
     section's entity-type list, whose evidence those unavailable sources
     would otherwise supply) using only file contents it can read, list, or
     grep. Sessions are not a resolvable entity type for this augmenter —
     the "runtime/session graph" phrase names the skill's original
     unavailable evidence source, not an in-scope output entity type — so
     no session-related mapping is ever produced. The model is expected to
     treat the missing evidence source as unavailable and reason around it,
     not to attempt an unavailable tool call.
   The remaining Method wording that names no unavailable tool is embedded
   unchanged. (The exact adapted phrasing — for both the substituted
   references and the capability-gap statement — is a prompt-construction
   detail left to plan/implementation, consistent with how Resolved
   decisions 4–5 already leave literal tool/fact names at "e.g."
   granularity.) In place of the
   excluded Output Shape / Act-or-ask sections, the augmenter's own prompt
   states the required output contract directly: a structured line format,
   one line per confident mapping, `surface → canonical (evidence;
   confidence)`; the augmenter parses lines matching this format from the
   helper's raw response and discards everything else (preamble, commentary,
   malformed lines, any clarification-question-shaped text). Skill delivery
   is not via `create-child-session`'s `:skill-names` — that option
   auto-expands a skill only when the *user's own submitted text* matches or
   invokes it, and the parent-turn user text driving this helper session is
   never authored to invoke `entity-resolution`.

   The rendered `:append-context-block` `:content` is re-rendered from the
   parsed confident mappings as a compact `surface → canonical (evidence)`
   list — **three** fields per mapping, dropping confidence. Confidence's
   only role is the accept/reject gate on which lines are parsed as
   "confident" in the first place; it is not displayed in the rendered
   block. Zero successfully-parsed lines is treated as "no confident
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
  only the single top-ranked tool-calling-capable local candidate returned
  by `resolve-selection` (Required behaviour item 2); it does not retry the
  next-ranked candidate if that attempt fails or returns an unusable
  result — a failed/empty helper run from the one attempted candidate goes
  straight to `:no-op` (Required behaviour item 5 / Acceptance criteria).
  This deliberately departs from `auto-session-name`'s
  `select-helper-models`/`infer-session-title` behaviour, which retries
  across its entire ranked candidate list until one succeeds or all are
  exhausted; this task instead bounds the blocking, no-deadline critical
  path (Resolved decision 3) to at most one local-model attempt per turn,
  consistent with Resolved decision 5's precedent of trading away
  `auto-session-name` feature parity for v1 simplicity (the collapsed
  "no local model" vs. "local model, no tool support" diagnostic). The
  "failed/empty helper run → no-op" test (Tests list) exercises this single
  attempted candidate failing, not an exhausted ranked list.
- **Confidence gate & output shape.** Only sufficiently-unambiguous mappings
  enter the block; ambiguous/unevidenced references are dropped, never
  guessed. Rendered `:content` is a compact `surface → canonical (evidence)`
  list — see Resolved decision 6 for the exact three-field composition and
  why confidence is gate-only, not displayed; the raw user prompt is always
  preserved.
- **Model-absent fallback.** If `resolve-selection` yields no local winner
  (including no *tool-calling-capable* local winner, per Resolved decision
  5), the augmenter returns a well-formed `:no-op`; it never falls back to a
  cloud model on every turn.

## Constraints

- Preserve the 237 dispatch/effect and data-only-extension boundaries: no
  parent-request or parent-state mutation; augmenter returns an envelope; helper
  work uses existing extension session APIs only (no new child/run API).
- Preserve deterministic replay: the augmenter runs only live; recorded
  accepted operations are replayed without re-invoking the model (already
  guaranteed by 237 — do not weaken it).
- Local-first: helper model selection must strongly prefer `:locality :local`
  and zero/low cost, like `auto-session-name`, filtered on the tool-calling
  capability criterion (Resolved decision 5); never silently use a cloud
  model on every turn.
- The additive tool-calling capability fact/criterion added to
  `psi.ai.model-selection` (Resolved decision 5) must not change behaviour
  for existing callers/roles (`:helper`, `:auto-session-name`) that don't set
  it.
- Recursion safety: track and no-op for the augmenter's own helper session ids.
- Helper toolset is read-only: file read / list / grep only — no mutating,
  bash-write, or otherwise side-effecting tools on the helper session.
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
  `psi.ai.model-selection` with a strong `:locality :local` preference and the
  new tool-calling capability criterion (Resolved decision 5), and the
  helper session is created with a **minimal read-only search toolset** (no
  mutating/side-effecting tools); when no tool-calling-capable local model is
  available it returns a well-formed `:no-op` and no cloud model is used.
- `psi.ai.model-selection`'s new tool-calling capability fact/criterion
  (Resolved decision 5) is additive: existing `:helper` / `:auto-session-name`
  role-default selection behaviour is unchanged when the criterion is unset.
- The augmenter returns a well-formed `:no-op` (no operations) for: tracked
  helper sessions, blank effective-cwd, slash-command-only prompts
  (pre-filter, before spending a helper run), prompts with no detectable
  referring expression, no confident mapping, no tool-calling-capable local
  model, and failed/empty helper runs.
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
  no-op; no-local-model (including no tool-calling-capable local model) →
  no-op; ambiguous reference dropped; failed/empty helper run → no-op; and
  replay reuse.
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
- `.psi/skills/entity-resolution/SKILL.md` — resolution method and output shape.
