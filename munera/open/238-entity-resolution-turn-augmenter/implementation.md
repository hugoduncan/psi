## Architecture review (design-review turn 1)

- architectural review added 1 new design step: `psi.ai.model-selection` has
  no tool-calling capability criterion, but this design's helper session is
  tool-using (unlike `auto-session-name`'s toolless pattern it says to reuse
  "exactly"). See design-steps.md.

Context loaded for later ambiguity/inconsistency turns in this session:

- `context-manager`'s install manifest already declares
  `:psi.capability/turn-augmentation`
  (`components/agent-session/src/psi/agent_session/extension_installs.clj`),
  so registering a second augmenter on the same extension needs no new
  manifest/permission work — Resolved decision 1 is architecturally sound.
- The only built-in read-only-without-bash tool today is `"read"` (single
  file read); there is no built-in directory-list or grep tool
  (`components/agent-session/src/psi/agent_session/tools.clj`,
  `make-read-only-tools-with-cwd` only returns `read-tool`). The design's
  "minimal read-only search toolset (file read + directory list + content
  grep)" therefore requires *new* tool defs, not just wiring up existing
  ones — this fits the existing tool-registry pattern fine (not an
  architectural misfit) but is not "already-shipped" as the Goal section
  implies for this piece; worth a look in the ambiguity/inconsistency pass.
- `extensions/context-manager/deps.edn` does not depend on `psi/ai` yet
  (unlike `extensions/auto-session-name/deps.edn`, which does); implementation
  will need to add that dependency to call `psi.ai.model-selection`.

## Ambiguity review (design-review turn 2)

- ambiguity review added 1 new design step: the design leaves both the
  skill-to-helper-prompt delivery mechanism and the helper model's expected
  output contract unspecified. See design-steps.md.
- `psi.prompt-assets.skills/invoke-skill` (used via `prompt_request.clj` and
  `create-child-session`'s `:skill-names`) expands skills based on
  matching/explicit invocation in the *user's own submitted text*, not
  model-judged relevance mid-conversation — confirms `:skill-names` alone
  cannot be assumed to deterministically apply `entity-resolution`'s method
  to a helper session driven by arbitrary parent-turn user text.

## Inconsistency review (design-review turn 3)

- inconsistency review added 2 new design steps: (1) Goal's "already-shipped"
  framing of the auto-session-name pattern overclaims — that pattern's
  helper session is toolless, but this design needs a tool-enabled
  evidence-gathering helper session, which is new work; (2) the Tests list
  in Acceptance criteria omits a test for "failed/empty helper runs → no-op"
  even though that no-op condition is explicitly required one paragraph
  above it. See design-steps.md.

## Notes for the design-steps-resolution slice

Principles to hold while closing out design-steps.md:

- Resolving a design step means editing `design.md` itself (localized edits
  to the relevant section, per `AGENTS.md`'s "localized change, ¬broad
  restructure(spec)"), not just noting an implementation-time workaround —
  `design.md` must reach complete-and-unambiguous before `plan.md` can exist
  (`AGENTS.md` `gate(plan.md)`).
- None of the 4 items require a scope change — no `SCOPE_QUESTION` was
  raised in any of the three review turns, so every item can be closed by
  clarifying/deciding within the existing frozen scope, not by widening or
  narrowing it.
- The model-selection step touches a shared, non-extension component
  (`components/ai`) also used by the existing `:helper` and
  `:auto-session-name` role-defaults in `model_selection.clj` and by
  `auto-session-name` itself — whatever tool-calling fact/criterion gets
  added must be additive (new optional fact/criterion key) so existing
  callers/roles are unaffected.
- The two prompt/output-contract items (ambiguity step, and the related half
  of the "already-shipped" inconsistency step) are coupled: deciding how the
  skill's method reaches the helper prompt also determines what "already
  shipped" should honestly claim in the Goal section — resolve them together
  rather than independently.

Non-task-file context: this review ran as 3 turns of one shared
`design-review` session (architecture → ambiguity → inconsistency); all 4
design steps and their file citations were gathered without re-running
`bb test` or touching any code — nothing in `components/` or `extensions/`
changed during review.

## Design-steps resolution (all 4 items closed)

All 4 unchecked items from the architecture/ambiguity/inconsistency
design-review batch (commits `ac659bce7`..`1ef1a8d50`, baseline
`7842383bd`) were resolved by editing `design.md` directly; no
`SCOPE_QUESTION:` items were present. `design-steps.md` items marked `[x]`.

- Added **Resolved decisions 4–6** to `design.md`:
  4. read-only search toolset (dir-list + grep, beyond existing single-file
     `read`) is new work this task adds, not already-shipped;
  5. an additive tool-calling capability fact/criterion is added to
     `psi.ai.model-selection` (new optional key only — existing `:helper` /
     `:auto-session-name` callers unaffected); no tool-calling-capable local
     model collapses into the existing model-absent `:no-op` fallback (no
     new diagnostic distinguishing "no local model" from "local model, no
     tools" — this was a deliberate simplicity choice, not an oversight);
  6. skill delivery is verbatim embedding of `SKILL.md` content in the
     augmenter's own constructed helper prompt (not `:skill-names`), and the
     helper output contract is a structured per-line format
     (`surface → canonical (evidence; confidence)`) the augmenter parses;
     `:content` is re-rendered from parsed lines, never the raw model text.
  Goal, Required behaviour, Constraints, and Acceptance criteria were
  updated to reference these decisions and stay consistent with them
  (including the "already-shipped" framing correction and the missing
  failed/empty-helper-run test now listed under Acceptance criteria).
- Exact model-registry fact/criterion key names (e.g.
  `:supports-tool-calling`) and the precise structured-line regex/grammar are
  left to plan/implementation — the design step asked to *pin down the
  contract*, not bikeshed the literal key spelling, consistent with how
  Resolved decision 2 already left the toolset's literal tool names at
  "e.g." granularity.
- Next design-review pass (if any) should treat commit range after
  `fce0067b2`..HEAD (this follow-up) as the new prior-follow-up boundary for
  batch-baseline purposes.

## Architecture review (design-review turn 1, second pass — post-resolution)

- no architectural review feedback. Re-checked design.md (as updated by
  `42dbf2086`, which added Resolved decisions 4–6) against `AGENTS.md`,
  `ramora/META.md`, `doc/architecture.md`, and `doc/extension-api.md`;
  confirmed no new architectural misfit beyond what the prior
  architecture/ambiguity/inconsistency batch already found and design.md now
  resolves.
- Re-verified against current code (not just prior notes), all consistent
  with the design:
  - `run-agent-loop-in-session` (`components/agent-session/src/psi/agent_session/mutations/session.clj`)
    drives the full `core/prompt-in!` lifecycle for the child session,
    meaning the helper session goes through its own
    `:session/pre-turn-augment` phase and would itself be eligible for the
    `"project-context"` and `"entity-resolution"` augmenters unless tracked
    — this is exactly what Required behaviour item 6 / Constraints
    "Recursion safety" already require; `auto-session-name` shows the
    correct ordering (`remember-helper-session!` before
    `run-agent-loop-in-session`, `close-session` + `disj` after). No design
    change needed, but implementation must track the child id *before*
    starting the helper run, not after.
  - `psi.ai.model-selection`'s criterion dispatch (`candidate-attribute`,
    `compare-by-criterion`) is a `case`-based, additive per-key lookup —
    adding a `:supports-tool-calling` fact/criterion is mechanically
    additive, confirming Resolved decision 5's additive-only claim.
  - `create-child-session` / `prompt_request.clj`'s `input-expansion` calls
    `prompt-skills/invoke-skill` against the *submitted user text only* —
    confirms Resolved decision 6's rationale (verbatim `SKILL.md` embedding
    in the augmenter-constructed system/user prompt, not `:skill-names`) is
    the only viable existing mechanism; `:skill-names`/skill-text-expansion
    cannot force-include a skill independent of user text.
  - `components/agent-session/src/psi/agent_session/tools.clj` is already
    the core-owned built-in-tool home (read/bash/edit/write); adding
    dir-list/grep read-only tools there (Resolved decision 4) matches the
    existing tool-registry location and pattern, not a new surface.
  - `extension_installs.clj` already grants `context-manager` only
    `turn-augmentation-capability`; a second augmenter on the same
    extension needs no manifest change (Resolved decision 1 confirmed
    again against current manifest).
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Ambiguity review (design-review turn 2, third pass)

- ambiguity review added 1 new design step: Resolved decision 6's verbatim
  embedding of the skill's Method steps 1–5 carries evidence-gathering
  instructions naming `git ls-files`/`find`/`git grep`/"Psi graph
  introspection" — commands/capabilities absent from the decision-4
  read-only toolset (file read + directory list + content grep only, no
  bash, no graph-query tool). Design.md doesn't say whether the embedded
  text is left as-is or adapted to name only the actually-available tools.
  See design-steps.md.
- Considered and ruled out: whether "the helper's raw response" (Resolved
  decision 6, the text the augmenter parses lines from) is well-defined once
  the helper session is tool-using rather than single-shot. Ruled out as not
  ambiguous: `run-agent-loop-in-session`'s result already exposes a single
  `:psi.agent-session/agent-run-text` field
  (`extensions/auto-session-name/src/extensions/auto_session_name.clj`,
  `run-helper-attempt`) that `auto-session-name` already uses as "the
  response" regardless of any tool calls in between; this pre-existing
  mechanism unambiguously supplies "the helper's raw response" for parsing,
  so no design-level decision is needed.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Ambiguity review (design-review turn 2, second pass — post-resolution)

- ambiguity review added 2 new design steps: (1) whether Resolved decision
  6's "verbatim `SKILL.md` content" means the whole file (whose own "Output
  Shape"/"Act or ask" sections conflict with the augmenter's non-interactive,
  parse-only-fixed-line-format contract) or only the Method steps, with the
  augmenter's prompt separately stating the output contract; (2) the
  "Remaining v1 policies" slash-command-only eligibility skip cites
  `auto-session-name`'s guard as precedent, but that guard only filters
  individual context lines, not whole helper runs — the exact turn-level
  detection rule and its no-op/test placement are unspecified. See
  design-steps.md.
- Confirmed `:turn/augmentation-context` (used in Acceptance criteria) is a
  real, already-shipped 237 prompt-layer id
  (`components/turn-runtime/src/psi/turn_runtime/augmentation.clj`,
  `psi.agent_session/prompt_request_test.clj`) — not an ambiguous/new term;
  ruled out as a finding.
- Considered and ruled out: whether "no referring expressions detected" vs.
  "no confident mapping" (Required behaviour item 5) are the same code path.
  Resolved decision 6 already states zero-parsed-lines is treated as "no
  confident mapping," which subsumes the no-referring-expression case — not
  independently ambiguous, so not filed as a new item.

## Inconsistency review (design-review turn 3, second pass — post-resolution)

- inconsistency review added 1 new design step: design.md states the
  rendered `:append-context-block` `:content` field composition three
  different ways — Acceptance criteria (2 fields: surface → canonical only),
  "Remaining v1 policies" (3 fields: adds evidence), Resolved decision 6's
  per-line parse format (4 fields: adds confidence, with no statement of
  whether confidence survives into the rendered `:content`). These three
  passages were evidently not reconciled when decision 6 was added. See
  design-steps.md.
- Considered and ruled out as duplicates of already-filed ambiguity items
  (not re-filed under inconsistency): (a) Resolved decision 6's verbatim
  `SKILL.md` embedding technically *is* inconsistent with the Out-of-scope
  "no interactive clarification prompts" bullet (the skill's own "Act or
  ask" step instructs asking questions), but this is the same root cause
  already captured by the turn-2 skill-embedding-scope item; (b) the
  References section's claim that `SKILL.md` supplies "output shape" is
  stale against decision 6's separately-authored line grammar — same root
  cause as (a), not filed separately.
- Reconfirmed the "no-op" vs. "Tests" list match (previously-flagged
  inconsistency, closed by `42dbf2086`) — both now enumerate the same six
  no-op conditions; no regression.

## Notes for the second design-steps-resolution slice (3 unchecked items)

- The 3 unchecked items (skill-embedding scope, slash-command-only
  eligibility rule, `:content` field-composition inconsistency) are all
  closable within frozen scope — no `SCOPE_QUESTION:` was raised across
  architecture/ambiguity/inconsistency turns of this second pass.
- The skill-embedding-scope item and the `:content` field-composition item
  are coupled and should be resolved together: choosing option (b) there
  (embed only `SKILL.md`'s Method steps + a separately-authored augmenter
  output-format instruction) is exactly where the augmenter's own line
  grammar and rendered-content field list (2/3/4 fields — surface, canonical,
  evidence, confidence) get authored, so pick the embedding approach and the
  field composition in the same design.md edit rather than independently.
  The slash-command-only eligibility item is unrelated to these two and can
  be resolved separately.
- `components/turn-runtime/src/psi/turn_runtime/augmentation.clj`'s
  `render-append-context-blocks` treats each operation's `:content` as an
  opaque pre-rendered string (`"[title]\n" + content`, blocks joined by
  `"\n\n"`) — core does no field-level formatting of the mapping. Whatever
  field composition design.md settles on for `:content` is entirely the
  augmenter's own string-building concern, not a core/rendering constraint.
- Relevant non-task files for resolving these items: `.psi/skills/entity-resolution/SKILL.md`
  (Method steps 1–5 vs. Output Shape/Act-or-ask sections 6+), and
  `extensions/auto-session-name/src/extensions/auto_session_name.clj`
  (`slash-command-text?` — a per-line context filter, not a whole-run skip;
  don't cite it as if it already gates a full run).
- As before: resolving a design step means editing `design.md` itself
  (localized edits), not deferring to plan/implementation notes —
  `design.md` must reach complete-and-unambiguous before `plan.md` can exist.

## Second design-steps-resolution slice — resolved (batch: `bbf888503`..`9860b98cb`, baseline `42dbf2086`)

- Resolved all 3 items flagged in the note above, per the coupling called
  out there:
  - Skill-embedding scope (Resolved decision 6): only `SKILL.md`'s Method
    section (steps 1–5) is embedded verbatim; "Output Shape" and step 6
    ("Act or ask") are explicitly excluded because they conflict with the
    non-interactive parse-only contract. The augmenter's own prompt states
    the output line grammar directly instead.
  - `:content` field composition (Resolved decision 6, "Remaining v1
    policies," Acceptance criteria — all three now aligned): rendered
    `:content` is `surface → canonical (evidence)`, three fields,
    **confidence dropped** (it's gate-only, decides which lines parse as
    "confident," never displayed). The parsed *per-line* format from the
    helper's raw output stays four-field (`surface → canonical (evidence;
    confidence)`) — only the *re-rendered* `:content` is three-field.
  - Slash-command-only eligibility: defined as the same predicate as
    `auto-session-name`'s `slash-command-text?` (trimmed text non-empty and
    starts with `/`), applied to the whole turn's
    `:turn-augmentation/user-text` (turn-level), not per conversation line
    (auto-session-name's line-level use). It's a pre-filter checked before
    model selection/helper-run spend; outcome collapses into the existing
    "no referring expression" `:no-op` reason, but gets its own Tests-list
    entry since it's a distinct (pre-model) code path.
- No `SCOPE_QUESTION:` items existed in this batch; nothing deferred to the
  user this slice.
- design-steps.md: all 7 items now checked `[x]`. No unchecked design-step
  items remain — design.md should be re-readable end-to-end for
  completeness/unambiguity before `plan.md` is created.

## Architecture review (design-review turn 1, third pass — post skill/slash/content-composition resolution)

- no architectural review feedback. Re-checked design.md as edited by
  `b7f717e33` (skill-embedding scope narrowed to Method steps 1–5,
  slash-command-only pre-filter rule pinned down, `:content` field
  composition settled at three fields) against `AGENTS.md`, `ramora/META.md`,
  `doc/architecture.md`, and `doc/extension-api.md` — these edits are
  ambiguity/inconsistency clarifications, not architectural changes, and
  introduce no new architectural surface.
- Re-verified against current code that nothing has drifted since the
  second-pass architecture review (`bbf888503`): `context-manager`'s
  manifest still grants only `turn-augmentation-capability`
  (`extension_installs.clj`); `model_selection.clj` still has no
  `:supports-tool-calling` fact/criterion (additive addition still
  uncomplicated); `tools.clj`'s `make-read-only-tools-with-cwd` still
  exposes only `"read"` (dir-list/grep still genuinely new); `auto-session-name`
  still depends on `psi/ai` directly via `:local/root` while
  `context-manager` still doesn't — confirming the design's stated
  dependency-addition need is accurate and consistent with existing
  extension-dependency precedent (not an isolation violation; already
  established practice, not new).
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Inconsistency review (design-review turn 3, third pass)

- no inconsistency review feedback. Checked, and found consistent (no new
  finding):
  - The no-op enumeration in Required behaviour item 5, "Remaining v1
    policies," Acceptance criteria, and the Tests list all still agree
    (six reasons, slash-command-only listed as a distinct pre-filter path
    that collapses into the "no referring expression" outcome only for
    labeling purposes, per the deliberate "otherwise" wording in Required
    behaviour item 5) — no drift since the `42dbf2086`/`b7f717e33` fixes.
  - `:content` field composition (3 fields, confidence dropped) still reads
    the same way in Resolved decision 6, "Remaining v1 policies," and
    Acceptance criteria.
  - Cross-checked design.md's cited 237 input-contract field names
    (`:turn-augmentation/user-text`, `:turn-augmentation/user-message`,
    `:turn-augmentation/effective-cwd`, `:turn-augmentation/session`,
    `:turn-augmentation/history`, `:turn-augmentation/session-id`,
    `:turn-augmentation/turn-id`, `:turn-augmentation/workflow-run-id`)
    against `dispatch_effects.clj`'s `turn-projection` construction (~line
    408) — exact match, no stale/renamed field.
  - Cross-checked design.md's claim that 237 "explicitly excludes
    interactive pre-turn prompts" against
    `munera/closed/237-pre-turn-request-augmentation/design.md`'s own Out
    of scope list ("Interactive user prompts during pre-turn
    augmentation.") — accurate, not fabricated.
  - Cross-checked `:turn-augmentation/child-session-ids` provenance and
    `:turn/augmentation-context` message id against
    `psi.turn-runtime.augmentation` — both real, matching keys.
  - The already-known References-section staleness ("resolution method and
    output shape" overclaiming that `SKILL.md`'s Output Shape section is
    used) was previously identified and intentionally left unfiled as a
    duplicate of the already-resolved skill-embedding-scope item; not
    re-raised here.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.
