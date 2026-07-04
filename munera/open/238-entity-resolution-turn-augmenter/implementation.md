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

## Inconsistency review (design-review turn 3, seventh pass)

- inconsistency review added 1 new design step: the References entry for
  `.psi/skills/entity-resolution/SKILL.md` still says "resolution method and
  output shape," contradicting Resolved decision 6's settled rule that only
  Method steps 1–5 are embedded and the skill's Output Shape is excluded.

## Notes for the seventh design-steps-resolution slice (2 unchecked items)

- Resolve both items by localized edits to `design.md`; neither requires a
  scope change or `SCOPE_QUESTION:`. Keep the closed helper capability set
  intact: read / list / grep only, no git-command execution, no bash, no graph
  introspection.
- Evidence-corpus ambiguity: choose and state one corpus rule. Either make the
  new tools explicitly git-aware/tracked-file-only, or drop "git-tracked" and
  specify ordinary filesystem-scoped read/list/grep under `effective-cwd`.
  Do not leave design.md implying both.
- Skill reference inconsistency: the References entry should not say
  `SKILL.md` provides the output shape unless it also says that output shape is
  deliberately not used. Resolved decision 6 is authoritative: Method steps
  1–5 only; augmenter-authored structured line contract supplies output.
- Relevant non-task files:
  - `components/agent-session/src/psi/agent_session/tools.clj` — existing
    built-in read tool and likely home/pattern for new read-only list/grep
    tools.
  - `.psi/skills/entity-resolution/SKILL.md` — Method vs Output Shape / Act-or-ask
    split that the design references.
  - `extensions/context-manager/src/extensions/context_manager.clj` — host
    extension where the augmenter will construct helper prompts and select
    helper tools.


## User requirement change — bash-only helper tool access

User changed task 238's requirements: remove any need for new read-only tools
and give the entity-resolution helper session access to the existing `bash`
tool instead.

Applied to `design.md`:

- Goal now says the task deliberately does not add new read-only tools or a new
  model-selection tool-calling fact/criterion.
- Required behaviour now creates the helper with the existing `bash` tool only.
- Resolved decision 2 now defines evidence gathering as local-model + `bash`.
- Resolved decision 4 now says no directory-list/content-grep/git-aware search
  or other new read-only tool is in scope.
- Resolved decision 5 now says no `psi.ai.model-selection` tool/function-calling
  fact or criterion is added.
- Resolved decision 6 now adapts the embedded method to `bash`-based evidence
  gathering and keeps runtime/session graph introspection as an unavailable
  capability.
- Constraints and acceptance criteria now assert no new read-only/search tools
  and no new model-selection capability facts.
- The two previously open design steps are marked resolved in `design-steps.md`:
  the git-tracked-vs-filesystem read/list/grep corpus question is obsolete, and
  the SKILL.md reference wording now matches the method-only delivery contract.

## Architecture review (design-review turn 1, eighth pass — post bash-only requirement change)

- no architectural review feedback. First architecture pass over the
  bash-only design (user requirement change). The change reduces
  architectural surface rather than adding it: it drops the previously
  design-flagged new `:supports-tool-calling` model-selection fact and the
  new read-only toolset, and instead reuses the existing `bash` tool
  (`components/agent-session/src/psi/agent_session/tools.clj` — `bash-tool`,
  in the built-in tool set) granted via the existing `create-child-session`
  `:tool-ids` path (`child_session_state.clj` / `resolve-tool-defs`). No new
  child/run API, no new tool surface.
- Conformance verified against `AGENTS.md` (VSM), `ramora/META.md`,
  `doc/architecture.md`: data-only extension envelope + no parent mutation +
  replay determinism (237/S5) preserved; capability gating unchanged
  (`turn-augmentation-capability`, no manifest change); graceful `:no-op`
  degradation when the local model can't use bash keeps invariants intact.
- `context-manager` will need a `psi/ai` dep added for `model-selection`;
  consistent with `auto-session-name/deps.edn`'s existing `psi/ai` dep — an
  established extension-dependency precedent, not an isolation violation.
- Considered and not filed: relying on prompt constraints (behavioral) for
  bash safety on an auto-fired per-turn helper is in mild tension with the
  `impossible_invalid_states` structural-safety ethos, but (a) it is a
  deliberate, user-directed, documented decision (Resolved decisions 2/4),
  (b) the existing bash tool already routes through app-runtime
  `effective-policy`/tool-output policy (`tools.clj` ~line 372), providing a
  structural boundary automatically, and (c) filing it would re-litigate the
  frozen bash-vs-restricted-tools scope. Not an architectural misfit against
  a violated invariant.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.
- design-steps.md has 0 unchecked items; no new step added this pass.

## Ambiguity review (design-review turn 2, eighth pass — bash-only design)

- ambiguity review added 1 new design step: the bash-only helper is now a
  multi-round tool-using agent loop with no design-specified bound on
  agent-loop rounds / bash commands / total wall-clock time, running on
  Resolved decision 3's blocking no-deadline critical path of every eligible
  turn. Decision 3's "no-deadline" was framed for a single toolless model
  call (the `auto-session-name` `:tool-ids []` precedent); it doesn't address
  an unbounded tool-using loop. See design-steps.md.
- Ruled out (checked, not filed): the "helper bash runs under the effective
  cwd" claim is unambiguous — `:turn-augmentation/effective-cwd` =
  `(:worktree-path session-data)` (`dispatch_effects.clj:413`), and
  `create-child-session` inherits the parent's `:worktree-path` and binds the
  child's tools to it (`child_session_state.clj:70,172`), so the granted
  `bash` runs in effective-cwd automatically via existing inheritance — no
  design decision needed.
- Ruled out (not filed): bash could technically reach `psi-tool`/nrepl to do
  the "unavailable" runtime/session graph introspection; this is covered by
  decision 6's capability-gap disclosure + decision 2's "bash for evidence
  gathering only" constraint, and re-raising it would re-litigate the frozen
  bash-safety-via-prompt-constraints scope decision.
- No `SCOPE_QUESTION:` raised — the loop-bound item is closable within frozen
  scope.

## Inconsistency review (design-review turn 3, eighth pass — bash-only design)

- inconsistency review added 1 new design step: Goal's "additive wiring this
  task must add (see Resolved decisions 4–6)" citation is stale — decisions 4
  ("No new read-only tools") and 5 ("No new model-selection fact") are now
  non-additions, contradicting the same paragraph's "deliberately does not
  add" wording. Leftover from the pre-bash read-only design. See
  design-steps.md.
- Checked and found consistent (not filed): no stale "cannot check git
  status"/"worktree/git"/"git-tracked"/"filesystem/git" phrasing survives the
  bash edit (grepped design.md); decision 6's capability gap now covers only
  runtime/session graph introspection, git commands correctly framed as
  available via bash; References SKILL.md entry correctly says Output Shape
  not used; acceptance-criteria/tests list correctly say "bash tool only" and
  "no new directory-list/content-grep/read-only toolset"; the old
  "tool-enabled evidence-gathering half is new work" inconsistency is
  resolved by bash (bash + create-child-session :tool-ids is genuinely
  already-shipped).
- Not re-filed (same root cause as ambiguity turn's loop-bound item):
  Resolved decision 3's "the local-model call" singular framing vs. the
  multi-round bash agent loop — already captured by the ambiguity pass, not
  duplicated here.
- No `SCOPE_QUESTION:` raised — the finding is closable within frozen scope.

## Notes for the eighth design-steps-resolution slice (2 unchecked items)

- The 2 unchecked items are independent (different design.md spans) and both
  closable by localized `design.md` edits within frozen scope — no
  `SCOPE_QUESTION:` was raised across this session's arch/ambiguity/inconsistency
  turns. Resolve by editing `design.md` itself (design.md must be
  complete-and-unambiguous before `plan.md` exists), not via plan/impl notes.
- Goal additive-wiring citation (inconsistency): pure wording fix. Under the
  bash design the real additions are the second augmenter registration +
  helper-prompt construction (decision 6) and the existing-`bash` grant via
  `create-child-session` :tool-ids (decision 2); decisions 4/5 are
  non-additions. Just repoint/reword the "(see Resolved decisions 4–6)"
  clause — don't touch decisions 4/5's own text (they're correct).
- Helper-loop-bound (ambiguity): decide the policy at the same "policy, not
  literal numbers" granularity as Resolved decisions 4–6 — don't hardcode a
  round count / ms budget into design.md. Relevant existing structural facts
  to lean on (already verified this session, no need to re-derive):
  - each `bash` command is capped at 30s default
    (`components/agent-session/src/psi/agent_session/tools.clj`, `execute-bash`
    `timeout-secs (or timeout 30)`), so per-command wall-time is already bounded.
  - `run-agent-loop-in-session`
    (`components/agent-session/src/psi/agent_session/mutations/session.clj`)
    runs `core/prompt-in!` to turn completion with **no** agent-loop round cap
    surfaced — the unbounded dimension is number of rounds/commands, not
    per-command time.
  - if the chosen policy diverges from decision 3's "no-deadline" wording,
    reconcile decision 3 in the same edit (it currently says "the local-model
    call," singular, framed for the toolless single-shot precedent).

## Eighth design-steps-resolution slice — resolved (baseline `c070dd961`)

- Batch identification: the immediately preceding whole `design-review` batch is
  the eighth-pass triple `2284b1849` (architecture, no finding) → `81361a548`
  (ambiguity, added helper-loop-bound item) → `5613076a4` (inconsistency, added
  Goal additive-wiring citation item). Baseline = `c070dd961`, parent of the
  oldest review commit in that segment. `git diff c070dd961..HEAD --
  design-steps.md` showed exactly these 2 added, still-unchecked lines — no
  stale/pre-batch items, no `SCOPE_QUESTION:` items in the candidate set.
- Note: design.md carried an uncommitted working-tree bash-only edit at
  follow-up start (the user requirement change); both resolutions were applied
  on top of that working tree, so this slice's commit lands the bash-only
  design.md change together with these two follow-up edits.
- Resolved both items by editing `design.md` directly, within frozen scope:
  - Goal additive-wiring citation (inconsistency): repointed the "additive
    wiring this task must add" clause from "(see Resolved decisions 4–6)" to
    decisions 2 (bash grant) and 6 (augmenter registration + helper prompt),
    and added an explicit note that decisions 4/5 are deliberate non-additions.
    Decisions 4/5's own text was not touched.
  - Bounded helper agent loop (ambiguity): chose "bounded, exceeding →
    `:no-op`". Reworded Resolved decision 3 so "no-deadline" means the
    augmenter adds no wall-clock deadline of its own, not that the helper loop
    is unbounded; added a new "Remaining v1 policies" bullet ("Bounded helper
    agent loop") stating a finite bound exists (round cap and/or wall-clock
    budget) and that hitting it collapses into the existing failed-helper-run
    `:no-op` (Required behaviour item 5); cross-referenced it from Required
    behaviour item 3. Exact bound numbers deferred to plan/implementation at
    the same "e.g."/policy granularity as decisions 4–6.
- The bound-exceeded outcome deliberately collapses into Required behaviour
  item 5 / Acceptance-criteria's existing "failed/empty helper run → no-op"
  enumeration rather than adding a new no-op reason — no new test case or
  enumeration entry is required; the existing failed/empty-helper-run test
  covers it.
- `design-steps.md`: both eighth-pass items now `[x]`; 0 unchecked items remain.
- No `SCOPE_QUESTION:` item existed in this batch; nothing deferred to the user.
- Structural facts to lean on for plan/impl bound sizing (verified in prior
  slices): each `bash` command is capped at 30s default
  (`components/agent-session/src/psi/agent_session/tools.clj`, `execute-bash`);
  `run-agent-loop-in-session`
  (`components/agent-session/src/psi/agent_session/mutations/session.clj`) has
  no agent-loop round cap surfaced today — the round count is the dimension the
  new bound must cap.
- If a future design-review pass runs again, treat the commit that lands this
  slice as the new prior-follow-up boundary for batch-baseline purposes.

## Architecture review (design-review turn 1, ninth pass — post eighth-slice resolution)

- no architectural review feedback. Re-checked the fully-resolved bash-only
  design.md (0 unchecked design-steps items) against AGENTS.md (VSM),
  ramora/META.md, and doc/architecture.md. Core invariants preserved: data-only
  extension envelope + no parent mutation + replay determinism (237/S5),
  unchanged capability gating (`:psi.capability/turn-augmentation`), extension
  isolation via existing `create-child-session` `:tool-ids` + existing `bash`
  tool (no new child/run API, no new tool surface), and `psi/ai` dep matching
  `auto-session-name` precedent.
- The two eighth-slice resolution edits introduce no new architectural surface:
  the bounded-helper-loop bullet collapses bound-exhaustion into the existing
  failed-run `:no-op` (finite blocking latency, no new invariant/effect path);
  the Goal additive-wiring citation repoint is documentation wording only.
- Considered and not re-filed: bash-safety-via-prompt-constraints tension with
  `impossible_invalid_states` — already classified in the eighth pass as a
  frozen, user-directed scope decision with a structural boundary via
  app-runtime effective-policy; re-raising would re-litigate frozen scope.
- No `SCOPE_QUESTION:` raised. No new design step added this pass.

## Ambiguity review (design-review turn 2, ninth pass — post eighth-slice resolution)

- ambiguity review added 1 new design step: the confidence-gate mechanism is
  underspecified — decision 6 requires a `confidence` field in the helper line
  format and calls it "the accept/reject gate," but no confidence scale/
  vocabulary or threshold is defined, leaving unresolved whether the augmenter
  value-thresholds it or relies on model self-gating (augmenter accepts any
  well-formed line). Affects the "ambiguous reference dropped" test shape. See
  design-steps.md. Distinct from the already-resolved content-composition
  (display-field-count) and output-contract-format items.
- Checked and not filed (already resolved / policy-deferred by design):
  history-tail excerpt format, bounded-helper-loop numbers, and single-attempt
  model selection are all intentionally deferred to plan/impl at "e.g."/policy
  granularity — not ambiguities. No `SCOPE_QUESTION:` raised.

## Inconsistency review (design-review turn 3, ninth pass — post eighth-slice resolution)

- no inconsistency review feedback. Verified the three no-op enumerations
  (Required behaviour item 5, Acceptance criteria, Tests list) are aligned
  (all seven reasons + tests, incl. failed/empty-helper-run and the distinct
  slash-command pre-filter test); rendered-content composition is stated
  identically (3-field `surface → canonical (evidence)`, confidence dropped)
  across Acceptance criteria / decision 6 / "Confidence gate & output shape";
  model-selection (single-attempt, local, inherit context), child-session-ids
  provenance, and bounded-loop→failed-run `:no-op` collapse are consistent.
- Not filed (would duplicate): the confidence-gate underspecification is
  already captured by this session's ambiguity pass; its "accept/reject gate"
  wording tension is an ambiguity, not a distinct contradiction. No
  `SCOPE_QUESTION:` raised.

## Notes for the ninth design-steps-resolution slice (1 unchecked item: confidence-gate)

- Only one new unchecked item this batch: the confidence-gate mechanism
  (ambiguity turn 2). Resolve by editing `design.md` within frozen scope —
  clarify the in-scope confident filter; do not change entity types/operations.
- Two candidate resolutions to pick between (state the choice + rationale in
  design.md, don't leave both):
  (a) augmenter value-thresholds the confidence field → must fix a confidence
      scale/vocabulary + threshold at "e.g."/policy granularity (matching
      decisions 4–6), and the "ambiguous reference dropped" test asserts a
      low-confidence line is dropped by the augmenter;
  (b) model self-gates via "never guess" and the augmenter accepts any
      well-formed line (confidence token required-but-value-unconstrained,
      dropped at render) → then soften decision 6's "confidence's only role is
      the accept/reject gate" so it doesn't overstate augmenter-side filtering,
      and the "ambiguous reference dropped" test asserts the model omits the
      mapping (no augmenter confidence filtering exercised).
- Keep the three-field rendered composition (`surface → canonical (evidence)`,
  confidence dropped at render) unchanged whichever way is chosen — that is
  already settled; only the accept/reject step is in question.
- Whichever is chosen, align: decision 6's gate wording, the "Confidence gate &
  output shape" policy bullet, the "never guess" Constraint, and the Tests-list
  "ambiguous reference dropped" framing — so all agree on where the drop happens
  (model vs augmenter).
- Relevant precedent files (no re-derivation needed): confidence/self-gating
  language originates in `.psi/skills/entity-resolution/SKILL.md` (only Method
  steps 1–5 embedded; Output Shape excluded per decision 6); helper output
  parsing/render lives in the augmenter to be added under
  `extensions/context-manager/src/extensions/context_manager.clj`.
- No `SCOPE_QUESTION:` outstanding from this design-review batch. Architecture
  and inconsistency turns added nothing; this batch's only follow-up is the
  confidence-gate item.

## Ninth-pass confidence-gate resolution (design-steps slice)

- Resolved the ninth-pass confidence-gate item by choosing **model
  self-gating (interpretation (b))**: the augmenter accepts every well-formed
  parsed line and applies no confidence-value threshold/scale. Rationale: the
  design already forbids guessing and never provided a scale/threshold; (b) is
  the minimal-mechanism choice consistent with existing text and avoids
  introducing unauthored confidence-scale machinery.
- Confidence token stays a **required** field of the `surface → canonical
  (evidence; confidence)` line format (forces the model to state confidence,
  reinforcing self-gating) but is model-authored text — not validated, not
  displayed. Three-field render (`surface → canonical (evidence)`) unchanged.
- Aligned four passages to agree the drop happens model-side: decision 6's
  gate wording (two spots), the "Confidence gate & output shape" policy
  bullet, and the Tests-list "ambiguous reference dropped" framing (now
  asserts no line is emitted/parsed for the ambiguous surface). The "never
  guess" Constraint already matched and needed no edit.
- Implementer note: there is deliberately no confidence-parse/threshold step —
  the parser only matches line *shape* and keeps all well-formed lines; the
  confidence sub-token can be captured or ignored but must not gate acceptance.

## Architecture review (design-review turn 1, tenth pass — post ninth-slice confidence-gate resolution)

- no architectural review feedback. Reviewed design.md as edited by the
  ninth-slice confidence-gate resolution (`a75e7b61c`) — the only design.md
  change since the ninth-pass architecture review — against AGENTS.md (VSM),
  ramora/META.md, and doc/architecture.md. The confidence-gate resolution
  (model self-gating; augmenter accepts every well-formed parsed line with no
  value threshold; confidence a required-but-undisplayed field) is a
  prompt/output-contract clarification only: no new architectural surface,
  dependency, capability, boundary, or effect path. Data-only extension
  envelope + no parent mutation + replay determinism (237/S5), capability
  gating (`:psi.capability/turn-augmentation`), and extension isolation via
  existing `create-child-session` `:tool-ids` + existing `bash` tool remain
  intact.
- Re-verified against current code, still matching design assumptions:
  `extension_installs.clj` grants `context-manager` only
  `turn-augmentation-capability` (decision 1 sound); `model_selection.clj`
  still has no `:supports-tool-calling` fact (decision 5's non-addition holds);
  `context-manager/deps.edn` still lacks a `psi/ai` dep (the design's stated
  additive dependency, matching `auto-session-name` precedent, remains accurate).
- design-steps.md has 0 unchecked items; no new step added this pass.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Ambiguity review (design-review turn 2, tenth pass — post ninth-slice confidence-gate resolution)

- no ambiguity review feedback. The only design.md change since the ninth-pass
  ambiguity review is the ninth-slice confidence-gate resolution (`a75e7b61c`),
  which *removed* the confidence-gate ambiguity by choosing model self-gating
  (augmenter accepts every well-formed parsed line; confidence is a
  required-but-unvalidated, undisplayed field). That resolution is internally
  unambiguous and aligned across decision 6, the "Confidence gate & output
  shape" policy bullet, and the Tests-list "ambiguous reference dropped"
  framing.
- Considered and ruled out (not filed): the exact "well-formed line"
  grammar/regex and whether a line missing the confidence token is malformed —
  deliberately deferred to plan/implementation at the same "e.g."/policy
  granularity as Resolved decisions 4–6, not a design-level ambiguity. Same
  for history-tail excerpt format, bounded-helper-loop numbers, and
  single-attempt selection (all previously classified as intentional
  policy-level deferrals).
- design-steps.md has 0 unchecked items; no new step added this pass.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Inconsistency review (design-review turn 3, tenth pass — post ninth-slice confidence-gate resolution)

- no inconsistency review feedback. The only design.md change since the
  ninth-pass inconsistency review is the confidence-gate resolution
  (`a75e7b61c`). Grepped all "confiden" occurrences: the reasoning-method
  description (`surface → canonical → evidence → confidence`), the line-format
  spec, the self-gating/no-threshold gate wording (Resolved decision 6 both
  spots + "Confidence gate & output shape" policy bullet), the three-field
  render (confidence dropped, not displayed), the "never guess" Constraint,
  and the Tests-list "ambiguous reference dropped" framing all agree —
  model-side self-gating, augmenter accepts every well-formed line, confidence
  a required-but-unvalidated/undisplayed field.
- No regression in the previously-verified alignments (ninth pass): the three
  no-op enumerations (Required behaviour item 5, Acceptance criteria, Tests
  list) and rendered-content composition are unchanged and still consistent.
- design-steps.md has 0 unchecked items; no new step added this pass.
- No `SCOPE_QUESTION:` raised — no scope-boundary concern found.

## Notes after tenth design-review pass (no follow-ups added)

- The tenth-pass design-review triple (architecture → ambiguity →
  inconsistency, all post ninth-slice confidence-gate resolution `a75e7b61c`)
  added **no** new design-steps and no `SCOPE_QUESTION:`. design-steps.md has
  0 unchecked items and design.md is complete-and-unambiguous — the
  `gate(plan.md)` precondition (AGENTS.md) is satisfied, so the next
  task-lifecycle step is `plan.md` creation, not another resolution slice.
- Design review has now converged: the last two full triples (ninth resolved
  the confidence-gate item; tenth found nothing) show the design is stable. A
  future review only needs re-running if design.md changes again.
- Principles for the plan/implementation task (carry forward, not re-derivable
  from design.md alone):
  - design.md deliberately leaves several v1 knobs at "e.g."/policy
    granularity — do NOT treat their absence as underspecification: the
    structured-line grammar/regex, the confidence sub-token capture (must not
    gate acceptance), the bounded-helper-loop round/wall-clock numbers, and
    the history-tail excerpt format/truncation are all intentionally
    plan/impl-owned.
  - Read the two "Remaining v1 policies" bullets (history-tail inclusion,
    single-attempt model selection) alongside Required behaviour items 2–3 —
    those numbered items only cross-reference them.
  - Track the helper child-session id *before* `run-agent-loop-in-session`
    (recursion safety), mirroring `auto-session-name`'s
    `remember-helper-session!` ordering.
  - Additive-only: adding `psi/ai` dep to `context-manager/deps.edn` and a
    second augmenter on the existing `turn-augmentation-capability` grant
    needs no manifest/model-selection changes (decisions 1/5).
- Relevant non-task files (already cited across prior slices, consolidated):
  - `extensions/context-manager/src/extensions/context_manager.clj` — host;
    augmenter registration + helper-prompt construction land here.
  - `extensions/auto-session-name/src/extensions/auto_session_name.clj` —
    precedent for helper session, `build-rename-prompt`, recursion tracking,
    and the ranked-candidate loop this task deliberately does NOT copy.
  - `components/ai/src/psi/ai/model_selection.clj` — `resolve-selection`.
  - `components/agent-session/src/psi/agent_session/tools.clj` — existing
    `bash-tool` (the only granted helper tool).
  - `components/agent-session/src/psi/agent_session/mutations/session.clj` —
    `run-agent-loop-in-session` (no round cap surfaced; the bound is new work).
  - `.psi/skills/entity-resolution/SKILL.md` — Method steps 1–5 (embedded,
    adapted); Output Shape / step 6 excluded.
  - `munera/closed/237-pre-turn-request-augmentation/design.md` — augmentation
    rail, envelope, replay guarantee.

## Plan-review ambiguity review (plan-review turn 1)

- no ambiguity review feedback. Reviewed plan.md (steps.md as read-only
  context) against the fully-converged design.md; plan faithfully translates
  the bash-only design. The one apparent fork (point 7's round-cap via
  run-agent-loop-in-session option vs. prompt-instructed) is not an
  unresolved ambiguity: the 120s wall-clock budget is the always-enforced
  finite bound satisfying design's sole requirement, with loop-option
  verification scheduled in slice 3. Concrete v1 knobs (8 rounds, 120s,
  ~4000-char excerpt) correctly pin the design's policy-level deferrals.

## Plan-review inconsistency review (plan-review turn 2)

- no inconsistency review feedback. Cross-checked plan.md against design.md
  (targeted grep of block id/title, model-selection criteria, single-attempt,
  :source/child-session-ids, atom naming) — all concrete values match design
  exactly. steps.md matches plan.md slice-for-slice; the 9 design acceptance
  tests are fully distributed across plan/steps slices with none dropped or
  duplicated. Plan's pinned v1 numbers (8 rounds / 120s / ~4000-char excerpt)
  and confidence-token-required parse shape are consistent with design's
  policy-level deferrals and model-self-gating resolution, not contradictions.

## Notes after plan-review batch (no follow-ups added)

- The plan-review batch (ambiguity + inconsistency turns) added **no**
  design-steps and no `SCOPE_QUESTION:`. plan.md/steps.md are complete and
  internally consistent with the converged design.md — the next
  task-lifecycle step is implementation (slice 1), not a plan-resolution
  slice.
- Principle if a future plan-review pass runs: plan.md deliberately pins v1
  knobs design.md left at "e.g."/policy granularity (8 rounds, 120s
  wall-clock, ~4000-char history excerpt, confidence-token-required parse
  shape). Do NOT re-flag these as under/over-specification — they are the
  plan-level policy values design.md explicitly deferred to plan/impl.
- Implementer entry points (already consolidated in the tenth design-review
  note above; not repeated here) remain the authoritative file list — start
  from plan.md's slice order.

## Implementation pass 1 (slices 1–5, extension-side complete)

All code + tests landed in `extensions/context-manager` in one coherent pass;
28 tests / 80 assertions green (`bb test --focus extensions.context-manager-test`).

Key decisions / discoveries:

- **`run-agent-loop-in-session` has no round-cap option** (verified in
  `components/agent-session/src/psi/agent_session/mutations/session.clj:148`;
  params are only `:session-id :prompt :model :api-key`). Per plan.md's stated
  mitigation, the enforced finite bound is the **120s wall-clock budget** (a
  `future` + `(deref fut helper-wall-clock-ms ::timeout)`; timeout ⇒ cancel ⇒
  treated as failed/empty run ⇒ no-op). The **8-round cap is prompt-instructed**
  only (embedded in the bash-safety prompt text). Each bash command already has
  its own 30s cap. Constants: `max-helper-rounds` 8, `helper-wall-clock-ms`
  120000, `max-history-chars` 4000.

- **Orchestration testability without mocks.** `entity-resolution-augmentation`
  takes an optional 3rd `collaborators` arg injecting `:select-model` (fn
  [parent-session-id] → model|nil) and `:run-helper` (fn [run-opts] →
  {:child-session-id :text}|nil). Tests pass deterministic fakes (real logic,
  nullable infrastructure) — no mocking framework, per `testing-without-mocks`.
  `init` registration wraps the real defaults (`default-select-model`,
  `default-run-helper`) closed over `api`.

- **Helper line format / parsing.** Parser regex accepts
  `surface (→|->) canonical (evidence; confidence)` per line and keeps *every*
  well-formed line (model self-gating — no confidence-value threshold; the
  confidence sub-token is captured but never gates acceptance and is dropped at
  render). Rendered `:content` is three-field `surface → canonical (evidence)`.

- **Recursion safety.** Child id is added to
  `entity-resolution-helper-session-ids` (a distinct atom from the existing
  `helper-session-ids`) *before* `run-agent-loop-in-session`, removed after
  close in `finally`. The augmenter no-ops for any tracked helper session id.

- **Provenance / data-only.** Success envelope operation omits `:source` (core
  injects it); the helper child id is reported in
  `:turn-augmentation/child-session-ids`. The no-confident-mapping no-op still
  reports the child id (a helper *did* run).

- **Dispatch insertion + replay reuse NOT duplicated.** The
  `:turn/augmentation-context` block insertion (before the current user
  message) and replay-without-re-invocation are augmenter-id-agnostic 237-rail
  guarantees, already fully tested in
  `components/agent-session/.../prompt_request_test.clj`
  (`build-prepared-request-inserts-turn-augmentation-context-test` + 237 replay
  tests). The extension's own responsibility — producing a correctly-shaped
  envelope operation — is asserted by
  `entity-resolution-confident-mapping-success-test`. Re-proving the shared rail
  inside the extension test alias would require pulling agent-session +
  turn-runtime deps into the extension (coupling AGENTS.md's shims/adapters
  guidance warns against). Steps for those two items are marked `[~]` with this
  rationale rather than `[x]`.

- **deps.edn**: added `psi/ai {:local/root "../../components/ai"}` (mirrors
  `auto-session-name`), needed for `psi.ai.model-selection/resolve-selection`.

- **Docs/changelog**: `doc/extensions.md` context-manager section now describes
  both augmenters; CHANGELOG `[Unreleased] → Added` entry added.

## Implementation review (turn 1)

- added 4 follow-up steps: helper child inherits full default system prompt
  (missing `:prompt-component-selection`), silently-ignored `:worktree-path`
  arg, unchecked `agent-run-ok?`, and mapping-line regex parens/`;` fragility.

## Implementation-review follow-ups (turn 1) — resolved

- addressed 4 review steps in `default-run-helper` / `parse-mapping-lines`:
  1. **prompt-component-selection**: added `{:agents-md? false
     :extension-prompt-contributions [] :tool-names ["bash"] :skill-names []
     :components #{}}` to `create-child-session` so the augmenter's embedded
     Method-only system prompt is authoritative (auto-session-name precedent).
  2. **worktree-path dead arg**: dropped `:worktree-path` (and the now-unused
     `:cwd` run-helper opt); child cwd comes from parent-worktree inheritance,
     documented in the fn docstring/comment.
  3. **agent-run-ok? gate**: `:text` is now `(when agent-run-ok? agent-run-text)`
     — a failed run (`ok? false`, `"Error: ..."`) no longer feeds the parser.
  4. **mapping-line-re hardening**: trailing `(evidence; confidence)` anchored
     to the last parenthesized group; evidence splits at the last `;`. Canonical
     may now contain `(...)` and evidence may contain `;`.
- New tests: two `parse-mapping-lines-test` cases (canonical-with-parens,
  evidence-with-semicolon), `default-run-helper-gates-on-run-ok-test`,
  `default-run-helper-suppresses-default-prompt-and-omits-worktree-test`.
- Focused suite: 30 tests, 87 assertions, 0 failures; clj-kondo clean.

## Implementation-review note (turn 2)

- added 1 step: `default-select-model` can return a cloud winner when no local
  model exists (`:locality :local` is only a strong-preference, not required),
  violating the local-only acceptance criterion/constraint/doc guarantee; the
  no-local-model test stubs selection and never exercises the real path.

## Implementation-review follow-ups turn 2 — resolved

- addressed 1 review step (cloud-model selection gap): guarded
  `default-select-model` on `:local` locality so a cheap-tier cloud winner
  (which survives the required filter, since `:locality :local` is only a
  strong preference) yields nil → `:no-op`, never a per-turn cloud helper
  run. Two new tests drive the real `default-select-model`/`resolve-selection`
  path via a redefed `catalog-view`: cloud-only pool → nil, local pool →
  selected. Shipped `doc/extensions.md` local-only claim now accurate; no doc
  change required. `bb test --focus extensions.context-manager-test` green
  (32 tests); clj-kondo clean.

## Implementation-review (turn 3)

- added 2 follow-up steps to steps.md.

## Implementation-review follow-ups resolution (turn 3, 2 items)

- Addressed both turn-3 implementation-review follow-up steps:
  - Timeout teardown race: `default-run-helper`'s run future now owns its own
    teardown (closes + untracks the child in `finally` once the blocking call
    truly returns/throws). The wall-clock-timeout path returns promptly with
    `:text nil` and leaves the child tracked until that `finally` fires;
    `future-cancel` removed (cannot unwind a blocking model/HTTP call and its
    cancel-then-`deref` defeats genuine-settlement detection). Settled path
    behaviour unchanged — the future's `finally` completes before `deref`
    yields the value, so close/untrack has happened by the time the augmenter
    reads the result.
  - Untested enforced bound: `helper-wall-clock-ms` is now injectable via a
    `:wall-clock-ms` run-opt (defaults to the 120s constant). New
    `default-run-helper-timeout-branch-test` drives the real
    `deref`/`::timeout`/`finally` path with a 20ms injected budget against an
    uninterruptible spin stub: asserts `:text` nil (→ `:no-op`), child stays
    tracked and unclosed during the orphan run, and is closed + untracked only
    after the orphan settles.
- `bb test --focus extensions.context-manager-test` green (33 tests, 95
  assertions, 0 failures); `clj-kondo` clean on both touched files.

## Implementation-review (turn 4)

- added 1 follow-up step: `parse-mapping-lines`/`mapping-line-re` accepts
  degenerate and non-mapping lines (empty canonical, incidental code-shaped
  lines with `->` + trailing `(…; …)`, nested parens in evidence),
  producing false-positive/misleading `Resolved entities` block content
  against the "never guess" + robust-parsing guarantees. See steps.md.

## Turn-4 implementation-review follow-up — resolved

- addressed 1 review step (parser hardening / false-positive rejection).
  Replaced the greedy single `mapping-line-re` with a structural parser:
  `balanced-trailing-group` (right-to-left balanced-paren scan) + last-`;`
  evidence split + non-empty-field gate + `balanced-parens?` on
  surface/canonical. Rejects empty-canonical, incidental code-shaped lines,
  and correctly isolates nested parens in evidence; preserves the turn-1
  parens-in-canonical and semicolon-in-evidence cases. 3 new
  `parse-mapping-lines-test` cases pin the accept/reject boundary.
  `bb test --focus extensions.context-manager-test` green (33 tests, 98
  assertions, 0 failures); clj-kondo clean on both touched files.

## Implementation-review (turn 5)

- added 1 follow-up step: the registered `entity-resolution` handler's
  `api`-threading through the default `select-model`/`run-helper`
  collaborators is untested — all 8 augmenter test call sites use an empty
  `{}` api with injected stubs, and the registration test asserts only
  `fn?`. See steps.md.

## Turn-5 implementation-review follow-up — resolved

- addressed 1 review step (untested default-collaborator api-threading seam).
  Added `entity-resolution-registered-handler-threads-real-api-test`: drives
  the **registered** handler (built by `init`, real nullable `api` closed
  over, **no** injected collaborators) with a base turn projection under a
  `catalog-view`-redefed empty model pool. The real `#(default-select-model
  api %)` closure runs through `resolve-selection` (empty pool → `:no-winner`
  → nil), so the handler reaches the deterministic `no-op "no local model"`
  outcome — covering the production `api`-into-defaults seam a regression
  (dropped `api`, swapped defaults, mis-ordered `or` fallback) would break,
  unlike the 8 stub-injected `{}`-api call sites. `bb test --focus
  extensions.context-manager-test` green (34 tests, 100 assertions, 0
  failures); clj-kondo clean on the touched test file.

## Implementation-review (turn 6)

- added 1 follow-up step: `render-history-excerpt`/`history-line` mis-read the
  `:turn-augmentation/history` projection (real shape is a map
  `{:message-count :tail [{:role :snippet ...}]}` per the 237 contract and the
  live `build-augmentation-history-projection`, not a flat vector of
  `{:role :text}` entries), so history-tail inclusion is dead in production and
  the prompt test masks it with a wrong-shaped fixture. See steps.md.

- addressed 1 turn-6 review follow-up: fixed `render-history-excerpt`/
  `history-line` to consume the real 237 `:turn-augmentation/history`
  projection (`{:message-count :tail [{:role :snippet ...}]}`) — read `:tail`
  and each entry's `:role`+`:snippet`; rewrote the prompt-test fixture to the
  real map shape and added regressions (map-tail snippet rendered;
  nil/empty-tail/flat-vector → no excerpt). Focused tests + lint green.

## Implementation-review (turn 7)

- no new actionable follow-ups. Reviewed code/tests/docs and verified the
  6 prior turns' fixes against the live codebase: `create-child-session`
  param set (no `:worktree-path`), parent-worktree cwd inheritance
  (`child-session-state` reads `(:worktree-path parent-sd)`),
  `run-agent-loop-in-session` ok?/text shape and options (no `:max-rounds`),
  `resolve-selection` `:facts :locality` candidate shape, and the live 237
  `build-augmentation-history-projection` map shape (`:tail` of
  `{:role :snippet}`). Focused tests green (34 tests, 104 assertions, 0
  failures); clj-kondo clean on touched sources. Implementation is coherent,
  design-faithful, and converged.

## Test-review (turn 7)

- added 3 steps to be addressed (test-quality gaps): ambiguous-dropped test
  is a duplicate of the empty-run test (does not exercise drop-one-keep-another);
  no test spans the production recursion-avoidance loop (real
  `default-run-helper` tracking → augmenter pre-filter); settled-success
  close+untrack cleanup path is unasserted (only the timeout branch is).

## Test-review follow-ups (turn 7) — addressed (3 items)

- addressed 3 test-review follow-up steps (test-only changes; no production
  code touched):
  - strengthened `entity-resolution-ambiguous-dropped-test` to feed mixed
    helper output (confident "the resolver" line + prose declining ambiguous
    "that thing") and assert `:success` content keeps the confident surface
    and drops the ambiguous one — now distinct from the empty-run path;
  - added `entity-resolution-recursion-loop-end-to-end-test` linking the real
    `default-run-helper` producer (`conj` under a blocking run) to the real
    augmenter pre-filter consumer via the shared
    `entity-resolution-helper-session-ids` atom;
  - added `default-run-helper-settled-run-closes-and-untracks-test` (with
    `fake-run-api` now recording `close-session` ids + an `await-untracked`
    helper) covering the common settled-run close+untrack cleanup path.
- `bb test --focus extensions.context-manager-test`: 36 tests, 110 assertions,
  0 failures. `clj-kondo` on the test ns: 0 errors, 0 warnings.

## Test review (turn 8)

- Applied `task-test-review` skill. Added 2 steps: untested
  `render-history-excerpt` tail-truncation branch; and the un-caught
  run-helper exception path vs. the dead `throw?` `stub` affordance (behaviour
  ↔ test-affordance mismatch).

- Addressed 2 turn-8 test-review follow-ups. (1) tail-truncation:
  `build-entity-resolution-prompt-tail-truncation-test` drives the `subs`
  tail-cut branch (excerpt ≤ 4000, NEWMARKER tail kept, OLDMARKER head
  dropped). (2) throwing helper: `entity-resolution-augmentation` now wraps
  `run-helper` in `try/catch Throwable → nil` (defensive; collapses to
  `:no-op`), activating the dormant `throw?` `stub` affordance via
  `entity-resolution-throwing-helper-no-op-test`. clj-kondo clean; focused
  suite 38 tests / 116 assertions, 0 failures.

## Test review (turn 9)

- Applied `task-test-review` skill. Added 4 steps: untested capability-gap
  prompt disclosure (Resolved decision 6); untested round-cap prompt
  instruction (sole representation of the round bound); unverified
  select-model→run-helper `:model` threading; and `default-select-model`
  tests stubbing `catalog-view` via `with-redefs` rather than injecting a
  catalog (`λtest` nullable-over-mock standard, though `resolve-selection`
  already exposes a `:catalog` seam).

## Test-review follow-ups (turn 9) — addressed

- Addressed all 4 turn-9 test-review follow-ups (all test/testability items,
  behaviour already correct):
  1. Capability-gap disclosure now asserted in
     `build-entity-resolution-prompt-test` ("cannot query the Psi
     runtime/session graph" + "sessions are not a resolvable entity type").
  2. Round-cap prompt instruction now asserted ("at most 8 rounds").
  3. Added `entity-resolution-selected-model-flows-into-run-test` capturing
     run-helper's `:model` run-opt to pin the selection→run seam.
  4. Gave `default-select-model` an optional `catalog` arg threaded into
     `resolve-selection`'s `:catalog` seam; the two `default-select-model`
     tests now inject a nullable `{:candidates [...]}` pool instead of
     `with-redefs`-ing `catalog-view`. The registered-handler real-api test
     keeps `with-redefs catalog-view` (production 2-arity default path has no
     injection point). Production 2-arity call site unchanged.
- `bb test --focus extensions.context-manager-test`: 39 tests, 121
  assertions, 0 failures. clj-kondo clean on src + test.

## Test-review (turn 10)

- Added 1 step: `default-run-helper` child-creation-failure branch (nil/throwing
  `create-child-session` → nil result / no run / no tracking) is untested.

## Test-review follow-ups addressed (turn 10)

- addressed 1 review step: added `default-run-helper-child-creation-failure-test`
  (nil-child and throwing-child sub-cases) asserting nil result → no run →
  no nil/orphan tracking. `bb test --focus extensions.context-manager-test`:
  39 tests, 125 assertions, 0 failures. clj-kondo clean.

## Test-review turn 11 (task-test-review)

- added 2 steps.
- non-compliance: commit `94ccb3f21` deleted the turn-5
  `entity-resolution-registered-handler-threads-real-api-test` (closing that
  follow-up's coverage) without replacement, so that step's `[x]`/DONE note is
  now inaccurate — the default-collaborator api-threading seam is uncovered
  again. Current suite is 38 tests / 119 assertions, below the "39 tests / 125
  assertions" recorded in the turn-10 note above, confirming the lost test.

## Test-review turn-11 follow-ups — addressed

- Item 1 (turn-5 seam allegedly deleted): premise false. `git show 94ccb3f21`
  is a **move**, not a delete — `entity-resolution-registered-handler-threads-real-api-test`
  was relocated verbatim (require + body) into
  `context_manager_entity_resolution_registration_test.clj`, which exists and
  passes (`bb test --focus
  extensions.context-manager-entity-resolution-registration-test` → 2
  assertions, 0 failures). The removed hunk in `context_manager_test.clj`
  equals the added hunk in the new ns. No replacement test added; step marked
  `[x]` with the corrected finding. (The prior implementation.md note that the
  test was lost was itself based on the same misread of a rename as a
  deletion.)
- Item 2 (multi-mapping render untested): added a ≥3-mapping case to
  `render-mapping-content-test` and a new
  `entity-resolution-multi-mapping-success-test` (2 lines → multi-line block),
  covering the `str/join "\n"` path and the end-to-end multi-mapping success
  block, both asserting input order.
- addressed 2 review steps; focused suite green
  (`extensions.context-manager-test` 39 tests / 122 assertions, 0 failures).

## Test review (turn 12)

- added 3 test-coverage follow-up steps: (1) `:tool-ids ["bash"]` tool-grant
  acceptance criterion unasserted (only `:prompt-component-selection`
  captured); (2) prompt's design-required *exclusions* (skill step 6 "Act or
  ask" / "Output Shape") untested — only inclusions asserted; (3)
  entity-resolution `:no-op` envelopes' "no operations" clause unasserted.

## Test-review turn-12 follow-ups addressed (test-coverage only)

- addressed 3 turn-12 test-review follow-ups (all pure test additions; no
  production change — behaviours already correct):
  1. `:tool-ids ["bash"]` + `:thinking-level :off` now asserted in
     `default-run-helper-suppresses-default-prompt-and-omits-worktree-test`.
  2. exclusion assertions added to `build-entity-resolution-prompt-test`
     (no "Act or ask", "Output Shape", clarification-question, or
     missing-identifier guidance).
  3. `(= [] (:turn-augmentation/operations env))` added to all seven
     entity-resolution no-op tests.
- `bb test --focus extensions.context-manager-test` → 38 tests, 133
  assertions, 0 failures; clj-kondo clean on the touched test ns.

## Test-review turn-13 (task-test-review skill)

- Independent test review (task-test-review skill): well-formedness, design-
  behaviour coverage, and nullable-not-mocked infra deps. Ran full extension
  test suite (39 tests, 141 assertions, 0 failures) and exercised
  `parse-mapping-lines` edge cases directly. No new actionable issues — every
  design acceptance criterion has a covering test; the two `[~]` slice-4
  items (dispatch-level insertion, replay reuse) are augmenter-id-agnostic
  237 guarantees verified by existing component tests
  (`build-prepared-request-inserts-turn-augmentation-context-test`,
  `replayed-turn-augmentation-uses-close-payload-without-live-invocation-test`),
  so the non-duplicating deferral is sound. No steps added.

## Test-review turn-14 (test-shaper skill)

- Independent test-shaper review — added 1 step (turn 14): the
  confidence-required-field reject boundary (a mapping-shaped line whose
  trailing group carries no `;`/confidence token) is silently dropped per
  Resolved decision 6 but no test isolates that reject case.

- addressed turn-14 review step: added `parse-mapping-lines-test` case
  pinning the confidence-required reject boundary (`;`-less trailing group →
  `[]`). Focused tests + lint pass.

## Test-review turn-15 (test-shaper skill)

- Independent test-shaper review — added 2 steps (turn 15): (1)
  `render-history-excerpt`'s over-long branch truncates mid-line/mid-word,
  injecting a corrupt role-less partial first line the turn-8 test does not
  pin or guard; (2) the success-envelope `:child-session-ids` provenance
  clause is asserted on only one of the four sibling success tests
  (consistency gap).

## Turn-15 test-review follow-ups addressed

- addressed 2 turn-15 test-review steps: (1) `render-history-excerpt` now
  truncates at line boundaries via `tail-lines-within` (drops whole leading
  lines, every survivor keeps its `Role:` prefix; no mid-word/role-less
  fragment) — pinned by an added `Role:`-prefix assertion in
  `build-entity-resolution-prompt-tail-truncation-test`; (2) added
  `:child-session-ids` provenance assertions to the three sibling success
  tests (multi-mapping, model-flow, ambiguous-dropped) so the clause is
  pinned uniformly across the success cluster. Focused suite: 36 tests,
  136 assertions, 0 failures; lint clean.

## Test-review turn-16 (test-shaper skill)

- Independent test-shaper review — added 2 steps (turn 16): (1)
  `default-select-model`'s `catch Exception` branch is untested — it is the
  sole guard against a thrown selection propagating onto 237's blocking
  pre-turn path (the augmenter wraps only `run-helper`, not `select-model`,
  in its own try/catch); (2) `history-line`'s whitespace-collapse
  (`\s+ → " "`) is untested — every history fixture uses single-space
  snippets, so an embedded-newline snippet that would inject a role-less
  continuation line into the excerpt is unguarded.

## Test-review turn-16 follow-ups addressed

- addressed 2 turn-16 test-review steps (both pure test additions, no
  production-code change): (1) `default-select-model-catches-thrown-selection-test`
  drives a throwing `:query-session` and asserts nil (select-side catch guard);
  (2) whitespace-collapse case in `build-entity-resolution-prompt-test` asserts
  an embedded newline/tab/multi-space snippet renders as one collapsed
  `Role:` line. Focused + full context-manager suite green (38 tests, 138
  assertions); clj-kondo clean.

## Test-review note (turn 17)

- added 2 steps (test-shaper): untested parent-session-model context
  inheritance in `default-select-model`, and the untested `::error`
  deref-catch branch of `default-run-helper`.

## Test-review turn-17 follow-ups addressed

- addressed 2 turn-17 test-review steps (both pure test additions, no
  production-code change): (1) parent-model context inheritance —
  `helper-model-selection-request-inherits-parent-model-test` pins the
  request builder's `:context {:session-model {:provider :id}}`, and
  `default-select-model-inherits-parent-model-context-test` drives the real
  `default-select-model` with a concrete `:query-session`, asserting the
  parent is queried for provider/id and the parent-provider candidate wins
  via the inherited `:same-provider-as-session` context; (2)
  `default-run-helper-run-throws-deref-error-branch-test` drives the `::error`
  deref-catch (thrown run → nil text, no propagation, child still
  closed/untracked by the future's `finally`). Focused + full context-manager
  suite green (42 tests, 152 assertions); clj-kondo clean.

## Test review (turn 18, test-shaper)

- added 2 steps: no-op *diagnostic* asserted for only 1 of 4 entity-resolution
  no-op reasons (consistency/meaningful-failures gap across the no-op cluster);
  `default-run-helper`'s `:model`-forwarding `cond->` arm untested at the
  real-fn level (only the stubbed selection→run seam is covered).

- addressed 2 turn-18 test-review follow-ups: pinned the no-op diagnostic
  string uniformly across the entity-resolution no-op cluster (blank-cwd /
  slash-command / empty-run / nil-run / throwing-helper + diagnostic-less
  recursion no-op); added `default-run-helper-forwards-selected-model-test`
  (via `fake-run-api` `:run-calls`) covering both the model-present and
  nil `cond->` arms of the real fn's run-param construction. Focused suite:
  35 tests, 140 assertions, 0 failures; test ns lints clean.

## Test review (turn 19, test-shaper) — ψ

- added 4 steps to be addressed (turn-19 follow-ups): shared-fixture
  duplication/divergence from the test-suite split (`base-tp` ×3, two
  same-named divergent `stub`s), `await-untracked` ×4, ad-hoc vs `fake-run-api`
  test-double inconsistency for the `default-run-helper` seam, and asymmetric
  augmenter exception-safety (run-helper wrapped, select-model not) untested at
  the augmenter boundary. All are consistency/economy shaping items; behaviour
  coverage is otherwise thorough (35 tests, 140 assertions passing).

## Turn-19 follow-ups addressed (test-shaper consistency + symmetric exception-safety)

- addressed 4 turn-19 review steps.
- Extracted `extensions.context-manager-test-support` (new test ns): single
  canonical `base-tp`, `stub`, `await-untracked`, and an extended `fake-run-api`
  (adds `:create-result`/`:create-throws?`/`:run-throws?`/`:block-until`/
  `:run-began` injection points). All six entity-resolution test files now
  `:refer` from it; removed 3 duplicate `base-tp`, 2 divergent same-named
  `stub`s, 2 duplicate `await-untracked` defns + 2 inlined poll loops, and 4
  bespoke inline `default-run-helper` api maps.
- Made the augmenter exception-safe symmetrically: wrapped the `select-model`
  call in `entity-resolution-augmentation` in `(try … (catch Throwable _ nil))`
  (mirroring the turn-8 run-helper wrap). Added
  `entity-resolution-throwing-select-model-no-op-test`. A thrown selection now
  collapses to the no-model `:no-op` ("no local model") rather than propagating
  onto 237's blocking pre-turn path.
- Verified: `clj-kondo` 0 errors/warnings on src+test; all context-manager
  test namespaces green (main 31 tests, model-selection 6, helper-runtime,
  helper-failure, flow, rendering, registration all pass).

## Implementation review (turn 20)

- reviewed against task-implementation-review skill; no new steps added.
- Code matches design (237-rail reuse, auto-session-name precedent, data-only
  extension, local-only guard); tests pass (all context-manager namespaces
  green); lint clean; docs (`doc/extensions.md`) + CHANGELOG coherent. Verified
  `mutate-session` first-arg→`:session-id` maps `parent-session-id` onto
  `create-child-session`'s parent param correctly, and the embedded method
  faithfully drops skill step 6 / Output Shape / runtime-graph line per
  Resolved decision 6. All 19 prior review turns resolved; DONE notes match code.

## Test review (turn 20)

- Added 1 step: `tail-lines-within`'s single-line-over-limit branch is
  untested and violates the turn-15 `<= max-history-chars` excerpt-bound
  invariant (a >4000-char single snippet yields a >4000-char excerpt). The
  documented deliberate-exception behaviour has no covering test, so the
  length-bound contract is two silently-conflicting statements.

## Test-review turn-20 follow-up

- Addressed 1 review step (turn-20): added
  `build-entity-resolution-prompt-single-line-over-limit-test` pinning the
  `tail-lines-within` single-line-over-limit kept-whole exception (option
  a — the documented intended behaviour); scoped the turn-15 `<= 4000`
  assertion to the multi-line case. Length-bound contract is now one coherent
  tested pair. Focused tests + clj-kondo clean.

## Test-review note (turn 21)

- Added 1 step: slash-command-only pre-filter has only a positive case and no
  mid-string-`/` negative-boundary test, so a mis-anchored predicate
  regression that disables the augmenter for path-like prompts would pass.

## Test-review turn-21 follow-up

- Addressed 1 review step (turn-21): added
  `entity-resolution-slash-command-only-negative-boundary-test` — a
  mid-string-`/` path-like prompt reaches select+run (not the slash no-op),
  plus a leading-whitespace `"  /help"` positive case, pinning the anchored
  predicate's two-sided contract against a naive `includes?` regression.
  Focused tests + clj-kondo clean.

## Test review (turn 22)

- Reviewed against task-test-review skill (well-formed / spec-behaviour
  coverage / infra-dep nullable-not-mocked). No steps added: every design
  behaviour and acceptance criterion maps to a covering test; the non-`:ok`
  select branch is hit by the empty-catalog registration test; infra deps use
  the nullable extension api + `fake-run-api` doubles + injectable
  `:select-model`/`:run-helper`/`:catalog` seams. The sole remaining
  `with-redefs catalog-view` (registration test) and the `[~]`-deferred
  insertion-point/replay coverage are pre-existing documented, justified
  boundary decisions, not new gaps.

## Test review (turn 22 — test-shaper)

- Added 1 step: history-excerpt tests pin only the `User:` role label; the
  `Assistant:` (non-`user`) arm of `history-line` is unguarded — a
  role-agnostic `[A-Z][a-z]*: .*` line-shape check plus `User:`-only equality
  would pass a role-collapsing/dropping/mis-casing regression on the
  augmenter's anaphora material.
