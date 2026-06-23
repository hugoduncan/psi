# Steps — 232 `advertise` field

## Slice 1 — Mechanism
- [x] Skills: parse `advertise` in `parse-skill-file`, propagate via `->skill`.
- [x] Skills: exclude `advertise: false` in `format-skills-for-prompt` and `-lambda` (shared `prompt-hidden?`).
- [x] EDN workflows: filter `:advertise false` in `build-prompt-contribution`.
- [x] Markdown workflows: allow + parse `advertise` frontmatter; propagate into definition.
- [x] Tests for skills parse + filtering (absent/true/false; coexist with disable-model-invocation).
- [x] Tests for workflow prompt-contribution filtering and markdown parse/compile.
- [x] Run verification (clj-kondo clean + 30 tests pass).

## Slice 2 — Apply the field
- [x] Flip enumerated review-*/issue-* skills to `advertise: false` (7 skills).
- [x] Flip enumerated sub-only workflows to `advertise: false` (22 md + 8 edn).
- [x] Verify dropped from system context, still registered/invocable (live load check).
