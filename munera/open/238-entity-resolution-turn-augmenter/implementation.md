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

## Notes for the third design-steps-resolution slice (1 unchecked item)

- Only one unchecked design step remains (the embedded-Method-text-names-
  unavailable-tools item, filed in the ambiguity pass above). Resolving it
  is the same kind of operation as the two prior resolution slices: a
  localized edit to `design.md` itself (Resolved decision 6, and any
  wording in "Required behaviour" item 3 that assumes the skill text
  applies unmodified), not a plan/implementation-time workaround —
  `design.md` must stay complete-and-unambiguous before `plan.md` can exist.
- Don't over-specify while resolving: decide the *policy* (embed
  `SKILL.md`'s Method steps verbatim and accept the mismatch vs. have the
  augmenter adapt/annotate the git/find/graph-introspection references to
  name only the actually-available tools) without hardcoding literal tool
  identifiers into `design.md` — Resolved decisions 4/5 already
  deliberately left exact tool-id/fact-key spelling to plan/implementation
  ("e.g." granularity), so this resolution should match that precedent
  rather than introduce the first hardcoded tool-name literal.
- If the chosen policy is "adapt the embedded text," the adaptation only
  needs to cover the specific unavailable references actually present in
  Method steps 1 and 3 — "current git status" (step 1) and "`git ls-files` /
  `find`", "`git grep`", and "Psi graph introspection" (step 3). No other
  Method sub-steps (2, 4, 5) name tools/commands, so the fix is localized,
  not a rewrite of the whole embedded section.
- Relevant non-task files for resolving this item:
  - `.psi/skills/entity-resolution/SKILL.md` — Method steps 1 and 3 contain
    the exact unavailable-tool references to reconcile.
  - `components/agent-session/src/psi/agent_session/tools.clj` —
    `make-read-only-tools-with-cwd` is the only existing read-only toolset;
    confirms no dir-list/grep/bash-git tool exists anywhere in the codebase
    today (checked afresh this slice), so there's no already-shipped tool
    the embedded text could be pointing at instead.
  - `extensions/auto-session-name/src/extensions/auto_session_name.clj` —
    `build-rename-prompt` is the existing precedent for how this
    extension family authors a constructed system/user prompt string;
    useful shape reference if plan/implementation needs to see how prompt
    text is assembled today (no adaptation-of-embedded-skill-text
    precedent exists there, since that helper session is toolless).

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

## Architecture review (design-review turn 1, fourth pass — post `d1db1d86e`)

- no architectural review feedback. `d1db1d86e` (the third
  design-steps-resolution slice) only reworded Goal/Required behaviour
  item 3/Resolved decision 6 from "embeds ... verbatim/directly" to "embeds
  ... adapted" — a wording clarification of an already-reviewed decision,
  introducing no new architectural surface, dependency, capability, or
  boundary.
- Re-verified against current code that nothing has drifted since the third
  architecture pass (`302bef5e4`): `model_selection.clj` still has no
  `:supports-tool-calling` fact/criterion; `make-read-only-tools-with-cwd`
  (`components/agent-session/src/psi/agent_session/tools.clj`) still returns
  only the single `read-tool`; `extensions/context-manager/deps.edn` still
  lacks a `psi/ai` dep (unlike `auto-session-name`'s); `extension_installs.clj`
  still grants `context-manager` only `turn-augmentation-capability`. All
  four still match what design.md assumes — no new gap.
- design-steps.md has 0 unchecked items; no new step added this pass.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Ambiguity review (design-review turn 2, fourth pass — post `d1db1d86e`)

- ambiguity review added 1 new design step: Resolved decision 6's adaptation
  policy ("reword to name only actually-available read-only capabilities")
  only has a natural substitute for 2 of the 4 flagged unavailable-tool
  references (`git ls-files`/`find` → directory list; `git grep` → content
  grep). The other 2 — step 1's "current git status" and step 3's "Psi
  graph introspection for runtime/session entities" — have no read/list/grep
  equivalent at all, and design.md doesn't say what "adapted" means for
  those (drop the sub-instruction vs. reword misleadingly vs. flag as an
  unavailable-evidence gap). See design-steps.md.
- git-status is not a hypothetical edge case: Method step 1 ties it to
  "path or task references," which are explicitly in this augmenter's scope
  (Goal section's entity-type list includes "paths" and "tasks"), so this is
  a real evidence-source gap under the frozen read/list/grep-only toolset
  (Resolved decision 4), not a corner the augmenter can avoid hitting.
- No `SCOPE_QUESTION:` raised — this is a prompt-construction/adaptation
  gap closable within frozen scope, not a scope-boundary concern.

## Inconsistency review (design-review turn 3, fourth pass — post `d1db1d86e`)

- no inconsistency review feedback. `d1db1d86e` only touched the Goal
  section, Required behaviour item 3, and Resolved decision 6, all now
  consistently using "adapted"/"adapted, not embedded verbatim" language
  with no stray "verbatim"/"directly" phrasing left describing the same
  skill-embedding mechanism elsewhere in design.md.
- Considered and ruled out as not a new, independently-actionable
  inconsistency: Resolved decision 3's "worktree/git" and Required
  behaviour item 3's "filesystem/git evidence" phrasing both still describe
  the toolset as reaching "git" evidence despite decision 4's toolset having
  no git-command tool — this is the same root cause already filed this
  session as the turn-2 ambiguity item (unmappable git-status/graph-
  introspection adaptation), not a separate inconsistency to re-file.
- Considered and ruled out: Resolved decision 2's toolset description uses
  "e.g. file read + directory list + content grep" (exemplary phrasing)
  while Resolved decision 4/6 and Constraints state the toolset as closed
  ("file read / list / grep only"). Not filed as a live contradiction:
  decision 4 is a later, more specific refinement of decision 2 within the
  same numbered-decisions sequence (both describe the same v1 toolset;
  decision 4 is the authoritative closed-set restatement scoped to this
  task's new-work addition), and every passage after decision 2 (4, 6,
  Constraints, Acceptance criteria) agrees on the closed three-tool set —
  no reader-facing ambiguity survives past decision 4.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Third design-steps-resolution slice — resolved (batch: `302bef5e4`..`b7bd9ebb6`, baseline `b7f717e33`)

- Resolved the 1 remaining unchecked item (embedded-Method-text-names-
  unavailable-tools) per the note above: chose policy (b) — the augmenter's
  prompt-construction step adapts/rewords only the specific Method
  sub-steps (1 and 3) that name unavailable commands (`git status`,
  `git ls-files`/`find`, `git grep`, "Psi graph introspection") so they
  instead name the helper's actually-available read-only capabilities
  (read a file, list a directory, grep file contents). Method sub-steps 2,
  4, 5 (no unavailable-tool references) are embedded unchanged. Exact
  adapted phrasing is left to plan/implementation, matching the existing
  "e.g." granularity precedent in Resolved decisions 4–5.
- Edited Resolved decision 6, the Goal section's skill-delivery paragraph,
  and Required behaviour item 3 to say "embeds ... adapted" instead of
  "embeds ... verbatim/directly" — these three passages previously implied
  unmodified embedding, which is now explicitly false.
- design-steps.md: all 8 items now checked `[x]`. No unchecked design-step
  items remain.
- No `SCOPE_QUESTION:` item existed in this batch; nothing deferred to the
  user this slice.
- If a future design-review pass runs again, treat the commit that lands
  this slice as the new prior-follow-up boundary for batch-baseline
  purposes (same convention used by the two prior resolution slices).

## Notes for the fourth design-steps-resolution slice (1 unchecked item)

- The 1 unchecked item (git-status / graph-introspection unmappable
  adaptation) is closable within frozen scope by picking one of its own
  (a)/(b)/(c) options and editing Resolved decision 6 (and, if the chosen
  option changes what step 1/3 evidence sources this augmenter can rely on,
  Required behaviour item 3's "gather filesystem/git evidence" phrasing,
  which currently overclaims git reach the same way decision 6 does) — no
  `SCOPE_QUESTION:` was raised, so don't treat this as a scope call.
- Recommended framing while resolving: option (a) (drop the unmappable
  sub-instruction) keeps the embedded Method text honest about what the
  toolset can actually do; option (c) (reword as an explicit capability gap
  the model should reason around, e.g. "you cannot check git status or run
  git commands; rely only on file contents you can read/list/grep") is the
  other viable choice and is arguably more useful than silent deletion,
  since it tells the model *why* an expected evidence source is missing
  rather than leaving a silent hole. Option (b) (reword to point at
  read/list/grep anyway) was already identified as producing misleading
  instructions and is the weakest choice — avoid it unless there's a reason
  the other two don't work.
  - Whichever option is chosen, keep it stated at the same "policy, not
    literal phrasing" granularity as the rest of Resolved decision 6 (which
    already defers exact adapted wording to plan/implementation) — don't
    hardcode the actual prompt sentence into design.md.
- This item does not affect Resolved decisions 4 or 5, the toolset
  membership (still exactly file read / directory list / content grep, per
  Constraints' closed "only" list), or any Acceptance-criteria/Tests-list
  enumeration — it is purely about how Method steps 1 and 3's two
  unmappable sub-clauses are handled inside the embedded/adapted prompt
  text.
- Relevant non-task files for resolving this item:
  - `.psi/skills/entity-resolution/SKILL.md` — Method step 1's "current git
    status" bullet and step 3's "Psi graph introspection for runtime/session
    entities" bullet are the exact two sub-clauses in question; steps 2, 4,
    5 and step 3's `git ls-files`/`find`/`git grep` bullets are unaffected
    (already resolved as adaptable via directory-list/content-grep
    substitutes in the third design-steps-resolution slice).
  - `components/agent-session/src/psi/agent_session/tools.clj` —
    confirms (still, as of this slice) no git-status/git-command/graph-query
    tool exists anywhere to point the reworded text at instead.
- As with prior slices: resolve by editing `design.md` itself (localized
  edit to Resolved decision 6, not a plan/implementation-time workaround).

## Architecture review (design-review turn 1, fifth pass — post `b37363b71`)

- no architectural review feedback. `b37363b71` only reworded Resolved
  decision 6's adaptation-policy paragraph (splitting the substitutable
  `git ls-files`/`find`/`git grep` references from the unmappable "current
  git status" / "Psi graph introspection" references, replacing the latter
  with an explicit capability-gap statement instead of a misleading
  reword) — a policy-wording refinement of an already-reviewed decision,
  introducing no new architectural surface, dependency, capability, or
  boundary.
- Re-verified against current code that nothing has drifted since the
  fourth architecture pass (`c416b1a95`): `model_selection.clj` still has
  no `:supports-tool-calling` fact/criterion; `make-read-only-tools-with-cwd`
  (`components/agent-session/src/psi/agent_session/tools.clj`) still
  returns only the single `read-tool`; `extensions/context-manager/deps.edn`
  still lacks a `psi/ai` dep (unlike `auto-session-name`'s); `extension_installs.clj`
  still grants `context-manager` only `turn-augmentation-capability`. All
  four still match what design.md assumes — no new gap.
- `design-steps.md` has 0 unchecked items; no new step added this pass.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Ambiguity review (design-review turn 2, sixth pass — post `f92192104`)

- ambiguity review added 2 new design steps: (1) whether the history-tail
  read by Required behaviour item 1 is ever incorporated into the
  constructed helper prompt (item 3 only says the prompt "applies [the
  method] to the user text," no mention of history), despite the embedded
  skill method's own step 1 needing conversation history to resolve
  anaphora; (2) whether helper-model selection retries across
  `resolve-selection`'s full ranked candidate list on failure (matching
  `auto-session-name`'s actual `select-helper-models`/loop behavior, the
  precedent this design cites) or attempts only the top-ranked candidate
  before `:no-op`. See design-steps.md.
- Used design.md, `AGENTS.md`, `ramora/META.md`, and `doc/architecture.md`
  already loaded in this session; no re-read needed since design.md is
  unchanged since the architecture-review turn. Targeted re-read: opened
  `extensions/auto-session-name/src/extensions/auto_session_name.clj` to
  check the exact precedent behavior for both findings above (history-excerpt
  construction via `build-rename-prompt`/`sanitize-session-entries`, and the
  ranked-candidate retry loop in `select-helper-models`/`infer-session-title`)
  — confirmed via code, not assumption.
- No `SCOPE_QUESTION:` raised — both items are prompt-construction/behavior
  specification gaps closable within frozen scope.

## Inconsistency review (design-review turn 3, sixth pass — post `f92192104`)

- inconsistency review added 1 new design step: Resolved decision 2
  ("searches the worktree/git for evidence itself") and Required behaviour
  item 3 ("gather filesystem/git evidence") both still claim git-evidence-
  gathering capability, unedited through every resolution slice, even
  though Resolved decision 6's settled capability-gap statement explicitly
  tells the model it cannot check git status, run git commands, or query
  the runtime/session graph. A fourth-pass review (post `d1db1d86e`) had
  flagged this same "git" phrasing but deferred it as the same root cause
  as the then-open git-status/graph-introspection adaptation ambiguity;
  when that ambiguity was resolved (`b37363b71`, the fourth
  design-steps-resolution slice), the resolution note explicitly recorded
  "no other section needed a matching edit" — decision 2 and item 3's
  git-evidence claims were never actually reconciled. See design-steps.md.
- Used design.md, `AGENTS.md`, `ramora/META.md`, and `doc/architecture.md`
  already loaded in this session; no re-read needed (design.md unchanged
  since the architecture-review turn). Targeted check: grepped design.md
  for "worktree/git"/"filesystem/git"/"gather...evidence" to confirm both
  overclaiming phrases are still present verbatim at their original
  locations (lines ~99, ~137-138) and were not touched by any later commit.
- No `SCOPE_QUESTION:` raised — this is a wording reconciliation within the
  already-frozen closed toolset (file read/list/grep only), not a
  scope-boundary concern.

## Notes for the sixth design-steps-resolution slice (3 unchecked items)

- All 3 are independent edits to different design.md spans — no forced
  ordering, but resolving the git-evidence inconsistency (decision 2 /
  item 3 wording) first is convenient since its edit sits right next to
  item 3's history-tail question and may touch the same sentence region.
- Git-evidence inconsistency: fix by rewording, not by revisiting the
  toolset itself — the closed read/list/grep toolset (Resolved decision
  4/6) is already frozen and correct; only decision 2's "searches the
  worktree/git" and item 3's "gather filesystem/git evidence" phrasing are
  wrong. Match decision 6's already-settled capability-gap language.
- History-tail item: if resolved by including a rendered history excerpt
  in the helper prompt, keep the excerpt-construction detail (format,
  truncation) at plan/implementation granularity, matching how Resolved
  decisions 4–6 already leave tool names and phrasing "e.g."/policy-level —
  don't hardcode an excerpt algorithm into design.md. If resolved the other
  way (current-turn-text-only is intentional), say why explicitly so a
  future reader doesn't re-flag the anaphora gap.
- Retry-across-candidates item: whichever way this is decided, it changes
  what the "failed/empty helper run → no-op" acceptance-criteria test needs
  to exercise (single failing model vs. exhausted ranked list) — if the
  chosen policy diverges from `auto-session-name`'s retry-until-exhausted
  behavior, say so explicitly in design.md rather than leaving the
  divergence implicit, since Goal/References already cite that extension's
  pattern as precedent.
- Relevant non-task files for resolving these three items:
  - `extensions/auto-session-name/src/extensions/auto_session_name.clj` —
    `build-rename-prompt`/`sanitize-session-entries` (history-excerpt
    construction precedent) and `select-helper-models`/`infer-session-title`
    (ranked-candidate retry-loop precedent). Both cited findings were
    verified directly against this file this session, not assumed.
  - `.psi/skills/entity-resolution/SKILL.md` — Method step 1 is the source
    of the anaphora/history-context requirement motivating the history-tail
    item.
  - `components/ai/src/psi/ai/model_selection.clj` — `resolve-selection`'s
    `:ranking :ranked` shape is what a retry-across-candidates policy would
    loop over, if chosen.
- As with prior slices: resolve by editing `design.md` itself (localized
  edits), not by deferring to plan/implementation-time notes — `design.md`
  must stay complete-and-unambiguous before `plan.md` can exist.

## Ambiguity review (design-review turn 2, fifth pass — post `b37363b71`)

- ambiguity review added 1 new design step: Resolved decision 6's new
  capability-gap statement (landed by `b37363b71`) tells the model to
  "reason about path/task/**session** references" using file-based
  evidence, but "session" appears nowhere else in design.md as a
  resolvable entity type — the Goal section's entity-type list omits it
  entirely. The word was carried over from the `entity-resolution` skill's
  own step 3 wording ("runtime/session entities") without checking it
  against this augmenter's narrower, tool-constrained scope. See
  design-steps.md.
- Targeted re-read only: reused the design.md, `AGENTS.md`, `ramora/META.md`,
  and `doc/architecture.md` content already loaded in this session's
  architecture-review turn; the only design.md text change since the prior
  (fourth-pass) ambiguity review is the `b37363b71` diff to Resolved
  decision 6, so review effort focused there rather than re-reading the
  whole file end-to-end.
- Confirmed by grep that "session" (as a candidate entity-reference type,
  not "helper session"/"child session"/"session-id" plumbing) occurs
  exactly once in design.md, at the new capability-gap sentence — this is
  a genuinely new ambiguity introduced by the latest resolution commit, not
  a pre-existing one missed by prior passes.
- Considered and ruled out: the capability-gap statement's exact
  insertion point (one consolidated statement vs. duplicated at each of
  step 1's and step 3's original locations) — design.md explicitly defers
  "exact adapted phrasing ... to plan/implementation," matching the same
  "policy, not literal phrasing" granularity already established for
  Resolved decisions 4–5; not re-flagged as ambiguous.
- No `SCOPE_QUESTION:` raised — this is a wording/entity-list-alignment
  gap closable within frozen scope, not a scope-boundary concern.

## Inconsistency review (design-review turn 3, fifth pass — post `b37363b71`)

- inconsistency review added 1 new design step: `b37363b71` amended
  Resolved decision 6's adaptation policy alone (split into substitutable
  vs. unmappable reference cases), but the Goal section and Required
  behaviour item 3 — both written earlier by `d1db1d86e` to summarize the
  policy as a uniform "name only the helper's actually-available read-only
  tools" reword — were not updated. Two of decision 6's four flagged
  references (git status, graph introspection) are now explicitly *not*
  handled by that reword rule; they get a distinct capability-gap
  disclosure instead. See design-steps.md.
- Targeted re-read: diffed `b37363b71` against `d1db1d86e` (already
  inspected in this session's architecture-review turn) and grepped
  design.md for "adapted"/"reworded"/"verbatim"/"capability gap" to find
  every passage describing the adaptation mechanism, rather than
  re-reading the whole file — confirmed only decision 6's paragraph
  changed in the latest commit; Goal and Required behaviour item 3's
  adaptation-summary sentences are unchanged since `d1db1d86e` and now lag
  decision 6's more detailed two-case split.
- Considered and ruled out as already filed (not re-filed here): the
  "session" entity-type mention in decision 6's new capability-gap text —
  filed as an ambiguity item in this session's turn 2, not re-raised under
  inconsistency since its root cause (unreconciled wording from the same
  edit) is the same finding already tracked there.
- No `SCOPE_QUESTION:` raised — this is a summary-wording drift closable
  within frozen scope by aligning three passages, not a scope-boundary
  concern.

## Notes for the fifth design-steps-resolution slice (2 unchecked items)

- Both unchecked items were introduced by the same commit (`b37363b71`,
  the fourth-slice resolution) and both live in text near/about Resolved
  decision 6's adaptation mechanism, but they are edits to different
  passages: the "session" item touches decision 6's capability-gap
  sentence itself (and, if resolved by adding "session" as a real entity
  type, the Goal section's entity-type list too); the Goal/Required-
  behaviour-item-3 item touches those two summary passages, not decision
  6's text. Resolve them in the same design.md edit pass anyway (not
  necessarily the same sentence) so a single re-read confirms all three
  passages — Goal, Required behaviour item 3, Resolved decision 6 — agree
  after both fixes land, rather than risking a sixth pass catching drift
  between them again.
- Recommended order: decide the "session" question first (is it in-scope
  or not), since its answer changes what Goal's entity-type list should
  say, and Goal's list is exactly the text the second item also edits.
  Settling "session" first avoids editing Goal's entity-type sentence
  twice in the same slice.
- For the "session" item: the two live options are (a) add "sessions" to
  Goal's entity-type list, uses of `:turn-augmentation/session-id` as one
  possible evidence source, and thread it through Required behaviour /
  Acceptance criteria's entity examples too if chosen — this is an
  *addition* of detail to an already-frozen scope's description, not a
  scope widening, since Required behaviour item 3 already scopes the
  helper to "the user text," so resolving what entity types it may
  recognize is a specification clarification, not new capability; or (b)
  drop "session" from decision 6's capability-gap sentence (reword to name
  only the types actually in Goal's list, e.g. "path/task references") and
  keep sessions out of scope entirely. Option (b) is the smaller, more
  localized edit (touches only decision 6's one sentence); option (a)
  touches at least Goal's list and probably nothing else, since
  Required behaviour and Acceptance criteria already state entity
  scope only via cross-reference to the Goal section, not by repeating
  the list. Neither option requires a `SCOPE_QUESTION:` — this is
  wording precision within the frozen scope, not a boundary call.
- For the Goal/Required-behaviour-item-3 item: the smallest fix is
  loosening both sentences from "names only the helper's
  actually-available read-only tools" to something like "adapted per
  Resolved decision 6" or a one-clause summary that acknowledges the
  two-case split exists, without duplicating decision 6's bullet detail
  in the Goal section (Goal is meant to stay a short overview — see
  `AGENTS.md`'s `role(meta) ≡ {why invariants boundaries ¬how ¬syntax}`
  framing, which this Goal section partly plays; decision 6 already owns
  the "how" detail). Don't expand Goal into a second copy of decision 6's
  bullet list.
- Relevant text spans to re-check together after editing (line numbers as
  of `4bcd1a942`, will drift on edit): Goal section ~lines 32-43,
  Required behaviour item 3 ~lines 90-98, Resolved decision 6 ~lines
  169-220. No other design.md section references the adaptation mechanism
  or the entity-type list (confirmed by grep during this pass), so no
  fourth passage needs checking.
- As with every prior slice: resolve by editing `design.md` directly
  (localized edits to the three spans above), not by adding a
  plan/implementation-time workaround note — `design.md` must be
  complete-and-unambiguous before `plan.md` can exist.
- Batch identification for this follow-up: contiguous architecture →
  ambiguity → inconsistency triple `54a0895c4` (architecture, no finding)
  → `a603b77a8` (ambiguity, added the session item) → `4bcd1a942`
  (inconsistency, added the Goal/item-3 lag item); baseline is
  `b37363b71`, the commit that landed the fourth design-steps-resolution
  slice (parent of the oldest commit in this triple).
- If a future design-review pass runs again, treat the commit that lands
  this (fifth) resolution slice as the new prior-follow-up boundary for
  batch-baseline purposes, matching the convention used by all four prior
  resolution slices.

## Fourth design-steps-resolution slice — resolved (batch: `c416b1a95`..`840e2100e`, baseline `d1db1d86e`)

- Resolved the 1 remaining unchecked item (git-status / graph-introspection
  unmappable adaptation) per the note above: chose option (c) — the
  unmappable sub-references (step 1's "current git status", step 3's "Psi
  graph introspection") are **not** reworded to point at read/list/grep;
  instead the embedded prompt states the capability gap explicitly (model
  told it cannot check git status, run git commands, or query the
  runtime/session graph, and must reason using only read/list/grep-able
  file contents). The two substitutable references (`git ls-files`/`find` →
  directory list, `git grep` → content grep) keep the prior reword-to-
  substitute treatment from the third slice — no change there.
- Edited only Resolved decision 6's adaptation paragraph in `design.md`
  (split into the two-case bullet list); no other section referenced the
  unmappable-subset distinction, so no other passage needed a matching edit.
- `design-steps.md`: all 9 items now checked `[x]`. No unchecked
  design-step items remain — this is the current end state for design
  review purposes.
- No `SCOPE_QUESTION:` item existed in this batch; nothing deferred to the
  user this slice.
- Batch identification for this follow-up: the batch is the contiguous
  architecture → ambiguity → inconsistency review triple `c416b1a95`
  (architecture, no finding) → `e229b3a43` (ambiguity, added the resolved
  item) → `840e2100e` (inconsistency, no finding); baseline is
  `d1db1d86e`, the parent of the oldest commit in that triple (also the
  commit that landed the prior/third design-steps-resolution slice).
- If a future design-review pass runs again, treat the commit that lands
  this slice as the new prior-follow-up boundary for batch-baseline
  purposes (same convention used by the three prior resolution slices).

## Fifth design-steps-resolution slice — resolved (batch: `54a0895c4`..`4bcd1a942`, baseline `b37363b71`)

- Resolved both remaining unchecked items from the note above:
  - "Session" item: chose option (b) — dropped "session" from Resolved
    decision 6's capability-gap sentence (now "reason about path/task
    references," the two in-scope entity types the unavailable git-status/
    graph-introspection evidence would otherwise have served). Added one
    clarifying sentence stating sessions are not a resolvable entity type
    for this augmenter, and that "runtime/session graph" in the same
    sentence names the skill's original unavailable evidence source, not an
    in-scope output entity — so a future reader doesn't re-flag the phrase.
    Goal's entity-type list was **not** touched (already correct; option
    (b) doesn't require it).
  - Goal/Required-behaviour-item-3 lag item: reworded both summary
    sentences from the uniform "names only the helper's actually-available
    read-only tools" to reference "Resolved decision 6's two-case split"
    (one clause each), without duplicating decision 6's bullet detail —
    Goal and item 3 now point at decision 6 as the single source of the
    adaptation mechanism's detail instead of carrying their own
    (now-inaccurate) paraphrase.
- Confirmed by grep (`grep -n session design.md`) that every remaining
  "session" occurrence is helper/child-session plumbing
  (`helper-session`, `session-id`, `auto-session-name`, etc.) or the
  skill's-original-wording reference inside decision 6's own clarifying
  sentence — no other passage claims sessions as a resolvable entity type.
- `design-steps.md`: all 11 items now checked `[x]`. No unchecked
  design-step items remain — this is the current end state for design
  review purposes.
- No `SCOPE_QUESTION:` item existed in this batch; nothing deferred to the
  user this slice.
- Batch identification for this follow-up: the batch is the contiguous
  architecture → ambiguity → inconsistency review triple `54a0895c4`
  (architecture, no finding) → `a603b77a8` (ambiguity, added the session
  item) → `4bcd1a942` (inconsistency, added the Goal/item-3 lag item);
  baseline is `b37363b71`, the parent of the oldest commit in that triple
  (also the commit that landed the fourth design-steps-resolution slice).
- If a future design-review pass runs again, treat the commit that lands
  this slice as the new prior-follow-up boundary for batch-baseline
  purposes (same convention used by all four prior resolution slices).

## Architecture review (design-review turn 1, sixth pass — post `f92192104`)

- no architectural review feedback. `f92192104` only reworded Resolved
  decision 6's two-case-split summary and the Goal/Required-behaviour-item-3
  cross-references to it, and dropped "session" from the capability-gap
  sentence — no new architectural surface, dependency, capability, or
  boundary.
- Re-verified against current code: `extension_installs.clj` still grants
  `context-manager` only `turn-augmentation-capability`; `model_selection.clj`
  still has no `:supports-tool-calling` fact; `make-read-only-tools-with-cwd`
  still returns only the single `read-tool`; `context-manager/deps.edn`
  still lacks a `psi/ai` dep (unlike `auto-session-name/deps.edn`); confirmed
  `doc/extension-api.md` documents UI capability keywords only, not
  turn-augmentation permissions — not a conflicting source for Resolved
  decision 1. All still match design.md's assumptions.
- `design-steps.md` has 0 unchecked items; no new step added this pass.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Sixth design-steps-resolution slice — resolved (batch: `317f1c7de`..`91f3f4ea5`, baseline `f92192104`)

- Batch identification for this follow-up: the batch is the contiguous
  architecture → ambiguity → inconsistency review triple `317f1c7de`
  (architecture, no finding) → `94d5a93b8` (ambiguity, added 2 items:
  history-tail inclusion, single-attempt vs. retry-across-candidates
  selection) → `91f3f4ea5` (inconsistency, added 1 item: decision
  2/item 3 git-evidence overclaim); baseline is `f92192104`, the parent of
  the oldest commit in that triple (also the commit that landed the fifth
  design-steps-resolution slice). `git diff f92192104..HEAD --
  design-steps.md` showed exactly these 3 added, still-unchecked checklist
  lines — no stale/pre-batch items and no `SCOPE_QUESTION:` items in the
  candidate set.
- Resolved all 3 items by editing `design.md` directly:
  - Git-evidence inconsistency: reworded Resolved decision 2 ("searches
    the worktree/git for evidence itself" → "searches the worktree's
    files for evidence itself" + an explicit "no git-command execution"
    clause) and Required behaviour item 3 ("gather filesystem/git
    evidence" → "gather evidence from the worktree's files (read / list /
    grep only — no git-command execution)"), both now consistent with
    Resolved decision 6's capability-gap disclosure.
  - History-tail inclusion: chose "include it" — Required behaviour item 3
    now says the helper prompt applies the method to the user text *plus*
    a rendered history-tail excerpt; added a new "Remaining v1 policies"
    bullet ("History-tail inclusion") stating the rationale (anaphora
    resolution needs prior-turn context) and deferring excerpt format/
    truncation to plan/implementation, at the same granularity as
    Resolved decisions 4–6. Chose inclusion over "current-turn-only" because
    the Goal section's "ambiguous/underspecified references" claim and the
    embedded skill method's own anaphora guidance structurally require
    prior-turn context to be actionable.
  - Retry-across-candidates: chose "single top-ranked attempt, no retry"
    (deliberate departure from `auto-session-name`'s retry-until-exhausted
    precedent) — added a new "Remaining v1 policies" bullet
    ("Single-attempt model selection") and a cross-reference in Required
    behaviour item 2, explicitly framed as v1 simplicity consistent with
    Resolved decision 5's existing precedent of trading away
    `auto-session-name` feature parity (the collapsed no-local-model
    diagnostic). Noted the "failed/empty helper run → no-op" test exercises
    the single attempted candidate, not an exhausted ranked list.
- `design-steps.md`: all 14 items now checked `[x]`. No unchecked
  design-step items remain.
- No `SCOPE_QUESTION:` item existed in this batch; nothing deferred to the
  user this slice.
- Implementation-relevant detail not otherwise in design.md: the two new
  "Remaining v1 policies" bullets (history-tail inclusion, single-attempt
  selection) are the only places design.md states these two behaviours —
  plan/implementation should read them alongside Required behaviour items
  2 and 3, not just the numbered items, since the numbered items only
  cross-reference them.
- If a future design-review pass runs again, treat the commit that lands
  this slice as the new prior-follow-up boundary for batch-baseline
  purposes (same convention used by all five prior resolution slices).


## Architecture review (design-review turn 1, seventh pass — post `bd70bf552`)

- no architectural review feedback. Reviewed current `design.md` against
  `AGENTS.md`, `ramora/META.md`, and `doc/architecture.md`; no new
  architectural misfit, no `SCOPE_QUESTION:`, and no unchecked design step
  added. `design-steps.md` remains fully checked after the prior
  history-tail / single-attempt / git-evidence wording resolution.

## Ambiguity review (design-review turn 2, seventh pass)

- ambiguity review added 1 new design step: Resolved decision 2 says the
  no-git-command helper searches "git-tracked file contents," while the
  actual closed toolset is read/list/grep over the worktree's files. The
  design needs to pin down whether v1 tools are git-aware/tracked-file-only
  or ordinary filesystem-scoped under `effective-cwd`.
