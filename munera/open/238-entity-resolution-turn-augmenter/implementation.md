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
