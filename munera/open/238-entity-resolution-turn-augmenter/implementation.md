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
