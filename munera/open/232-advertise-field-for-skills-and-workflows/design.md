# 232 — `advertise` field for skills and workflows

## Intent

Add an `advertise` field to both skills and workflows that controls whether the
skill/workflow is listed in the agent's **system context** (the system-prompt
contributions the model sees each turn).

- Defaults to `true` (current behaviour: everything advertised).
- When `false`, the skill/workflow is **omitted from the system context** but
  remains fully usable through every other path (direct file read, `/skill:`,
  `/delegate`, and invocation as a sub-step from another workflow).

## Why

The system context currently lists *every* discovered skill and *every* loaded
workflow. Several of these are not meant for the model to choose directly:

- **Workflow-internal skills** — e.g. the `review-*` and `issue-*` skills are
  read on demand by workflow steps (`review-step.edn` reads
  `.psi/skills/{{skill}}/SKILL.md` via the read tool). They add system-prompt
  noise/tokens without being something the top-level model should pick.
- **Sub-workflows** — e.g. `review-task-design-core`, `review-step`,
  `*-final-summary`, `*-implement-pass`, and other definitions that only exist
  to be invoked from a parent workflow. Listing them as top-level `/delegate`
  targets is misleading and noisy.

`advertise: false` lets us suppress these from the model's view while keeping
them callable by the machinery that actually uses them.

## Scope

### Skills

- Source: YAML frontmatter key `advertise` in `SKILL.md`
  (parsed in `components/prompt-assets/src/psi/prompt_assets/skills.clj`,
  reusing the existing frontmatter parser).
- Default `true` when the key is absent or unparseable-as-false.
- System-context formatters
  (`format-skills-for-prompt`, `format-skills-for-prompt-lambda`) exclude
  skills with `advertise: false`.
- Non-advertised skills remain discoverable in the registry and invocable via
  `/skill:name` and direct file read (the workflow path).

### Workflows

- Source: top-level EDN key `:advertise` in the workflow definition file
  (`.psi/workflows/*.edn`), alongside `:name`/`:description`/`:summary`.
- Default `true` when absent.
- The system-context workflow listing
  (`build-prompt-contribution` in
  `components/agent-session/src/psi/agent_session/workflow/text.clj`) excludes
  workflows with `:advertise false`.
- Non-advertised workflows remain invocable via `/delegate <name>` and as
  delegate sub-steps of other workflows; their definitions stay registered.

### Apply the field

Mark the genuinely workflow-internal skills and sub-only workflows as
`advertise: false` as part of this work (the concrete set to be enumerated
during planning — see open questions).

## Constraints

- Behaviour-preserving for the default (`advertise` absent / `true`): the
  system context is byte-identical for every skill/workflow whose `advertise`
  remains absent/`true`. This task deliberately flips the `review-*`/`issue-*`
  skills and sub-only workflows to `advertise: false` (see *Apply the field* and
  the Acceptance criteria), so those items are intentionally removed from the
  system context; the byte-identical invariant does not apply to them.
- `advertise: false` must **only** affect system-context listing — never
  registration, discovery, invocability, or execution.
- Field semantics, default, and naming are identical across skills and
  workflows (one concept, two surfaces).

## Acceptance criteria

- A skill with `advertise: false` in frontmatter does not appear in
  `format-skills-for-prompt` / `-lambda` output, but is still found by the
  registry and invocable by name.
- A workflow with `:advertise false` does not appear in the delegate
  prompt-contribution listing, but `/delegate <name>` and sub-step invocation
  still run it.
- Absent/`true` `advertise` preserves current listing behaviour exactly.
- The `review-*`/`issue-*` workflow-internal skills and the sub-only workflows
  are no longer advertised in the system context.

## Open questions (resolve during collaborative design refinement)

1. **Relationship to existing `disable-model-invocation` (skills).** Skills
   already support `disable-model-invocation: true`, which also hides the skill
   from the prompt while keeping `/skill:name` working — functionally close to
   `advertise: false`. Do we:
   (a) keep both as independent fields (hide-from-prompt iff either is set),
   (b) treat `advertise` as the unified successor and migrate
       `disable-model-invocation` usages, or
   (c) define `advertise` as workflow-only and reuse
       `disable-model-invocation` for skills?
   Decision affects whether this introduces one cross-cutting concept or two.
2. **Interactive listings.** Should non-advertised workflows still appear in the
   user-facing `/delegate list` and the `delegate` tool's `action=list`, or only
   be removed from the model's system-prompt contribution? ("system context"
   reads as the prompt contribution only — confirm.)
3. **Exact set to mark.** Which skills (`review-*`, `issue-*`, …) and which
   sub-workflows (`*-core`, `review-step`, `*-final-summary`, `*-implement-pass`,
   `*-resolve`, …) should be flipped to `advertise: false`. Needs an explicit
   enumeration so the change is auditable.
4. **Parse robustness.** Confirm the false-coercion rule: only the literal word
   `false` (case-insensitive, whitespace-trimmed) disables; any other value,
   including typos, defaults to advertised, to avoid silently hiding a skill on a
   frontmatter typo. The same coercion is shared with `disable-model-invocation`
   (literal `true`/`false`, case-insensitive, trimmed) via a single
   `frontmatter-flag` helper so the two boolean keys use one consistent rule.
