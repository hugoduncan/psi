## 2026-05-28 code-shaper follow-up (pass 2)

Executed all three code-shaper (pass 2) follow-up items.

1. **`compiler_target_authoring_test.clj` `parsed` fixture**: added `:vars nil` to align
   fixture shape with the documented `parse-markdown-workflow-file` return contract.

2. **`merge-markdown-session-config` simplified**: replaced the `reduce` form with
   `(merge (select-keys markdown-session-config markdown-session-config-keys) step)`.
   `merge` gives `step` precedence (later map wins) — correct semantics, simpler code.
   Added docstring explaining the precedence rule.

3. **`read-prompt-workflow` guards collapsed**: removed the separate `nil` and `not= :md`
   `cond` branches and the `file-kind` `let` binding. Single branch:
   `(not= :md (file-kind-from-path resolved-path))` handles both nil and `:edn` with
   one error message.

Focused compiler + parser + workflow-definitions + compiler-target-authoring tests:
13 tests, 161 assertions, 0 failures. `clj-kondo`: 0 errors, 0 warnings.

## 2026-05-28 code-shaper review (pass 2)

Three actionable issues found. HEAD `52fc773e`.

**1. `compiler_target_authoring_test.clj` `parsed` fixture is missing `:vars` key.**
The `compile-target-authored-workflow-file-test` fixture (line 11–14) does not include
`:vars nil`. `compiler_test.clj`'s `markdown-parsed` fixture was fixed in `f1ef7514` but
`compiler_target_authoring_test.clj` was not updated. The same shape-divergence risk
applies: a future compiler change that distinguishes `nil` from absent `:vars` would not
be caught.

**2. `merge-markdown-session-config` — nested `if` inside `reduce` is inconsistent idiom.**
The inner `(if (contains? markdown-session-config key) (assoc ...) acc)` guard is
necessary for correctness (avoids assigning `nil` for absent keys), but the whole function
can be expressed more simply as `(merge (select-keys markdown-session-config
markdown-session-config-keys) step)`. `merge` gives `step` precedence (later map wins),
which is the correct semantics. The `reduce` form adds indirection without benefit.

**3. `read-prompt-workflow` — redundant two-branch `nil`/`not= :md` file-kind check.**
`file-kind-from-path` returns `nil`, `:md`, or `:edn`. Two separate `cond` branches handle
`nil` and `(not= :md)` with nearly identical error messages. They can be collapsed to one:
`(not= :md (file-kind-from-path resolved-path))`. The distinct error message for `nil` vs
`:edn` adds no diagnostic value to the caller (both say "must reference a .md file").

## 2026-05-28 review-task-docs (pass 2)

No new actionable issues found.

Reviewed `README.md`, `doc/workflows.md`, `doc/workflow-grammar.md`,
`doc/workflow-grammar-concepts.md`, and `CHANGELOG.md` against the current
implementation state (HEAD `f1ef7514`).

- `doc/workflows.md` `.md` single-step authoring section: accurate and complete.
  `{{input}}`/`{{original}}` auto-wiring, `vars:` EDN syntax, allowed `:from`
  values, unknown-var compile-time error, and non-matching token pass-through are
  all documented correctly and match the implementation.
- `CHANGELOG.md`: three `Added` entries under `[Unreleased]` are present,
  accurate, and cover all user-visible task-187 changes.
- `README.md`: delegates to `doc/workflows.md`; no direct authoring surface to update.
- No stale references to removed behaviours (`:framing-prompt` absent from all
  user-facing docs).
- Minor pre-existing gap noted (not a task-187 regression): the `.md` authoring
  section documents `tools:` but does not enumerate all supported session-config
  frontmatter keys (`skills`, `model`, `thinking-level`, `response-mode`,
  `temperature`). This predates task 187; the section was entirely new in this
  task and the omission is out of scope here.

## 2026-05-28 test-shaper review (pass 2)

Two actionable gaps found.

**1. `markdown-parsed` fixture in `compiler_test.clj` is missing the `:vars` key.**
The parser contract now returns `{... :vars map-or-nil}` for single-step markdown. The
`markdown-parsed` fixture (used by `compile-markdown-workflow-file-test`) lacks `:vars`
entirely. The test passes because `compile-workflow-file` treats absent `:vars` the same
as `nil`, but the fixture silently diverges from the documented parser output shape. A
future compiler change that distinguishes `nil` from absent would not be caught by this
fixture. Add `:vars nil` to `markdown-parsed`.

**2. `final-summary step is inline` assertion is vacuous in three `workflow_definitions_test` tests.**
`review-task-design-test`, `review-task-plan-test`, and `implement-task-test` each assert
`(some? final-step)` under the label "final-summary step is inline (not prompt-workflow wired)".
This only checks the step exists — it does not verify the step is actually inline. A regression
where `final-summary` got accidentally wired via `:prompt-workflow` would not be caught (the step
would still exist). The assertion should verify `(seq (:contributions final-step))` to confirm
inline contributions are present.

No other actionable gaps. Economy: `review-task-design-test` and `review-task-plan-test` share
near-identical structure — acceptable duplication given the parallel workflow shapes.

## 2026-05-28 task-test-review (pass 2)

All 8 ACs have test coverage. Tests are well-formed, use real files / temp dirs, no mocks or stubs. One gap found:

**`vars:` frontmatter threading through `:prompt-workflow` path is untested.**
`compile-prompt-workflow-step` passes `(:vars referenced)` to `markdown-body->contribution`,
but no test exercises this with a non-nil `vars` value. The only `:prompt-workflow` test
uses `{{input}}` (a standard var) — custom `vars:` declared in a `.md` frontmatter and
referenced via `:prompt-workflow` from an `.edn` step is not covered. A regression in
`compile-prompt-workflow-step`'s vars-threading would not be caught.

No other actionable gaps. All previous review follow-ups are complete.

## 2026-05-28 task-implementation-review (pass 2) follow-up

Reverted all four `review-task-plan-*.md` files to use `steps.md` as the follow-up target.

- `review-task-plan-ambiguity-review.md`: step 2 changed `design-steps.md` → `steps.md`
- `review-task-plan-inconsistency-review.md`: step 2 changed `design-steps.md` → `steps.md`
- `review-task-plan-ambiguity-follow-up.md`: read/write/mark-done targets all restored to `steps.md`; "design-steps" terminology removed throughout
- `review-task-plan-inconsistency-follow-up.md`: read/write/mark-done targets all restored to `steps.md`; "design-steps" terminology removed throughout

Internal inconsistency in follow-up files resolved: read target and write/mark-done target now both agree on `steps.md`.

## 2026-05-28 task-implementation-review (pass 2)

**Bug: `review-task-plan-*` workflows write follow-ups to `design-steps.md` instead of `steps.md`.**

Commit `245d2152` ("update review-task-plan to use design-steps.md") changed all four
`review-task-plan-*.md` files to use `design-steps.md` as the follow-up target. This is
wrong: `design-steps.md` is a design-review artifact; plan-review follow-ups belong in
`steps.md`. The change also introduced an internal inconsistency in the follow-up files:
`ambiguity-follow-up.md` says "Read the task's steps.md to identify unchecked items" but
then says "mark it done in design-steps.md" — the read and write targets disagree.

This commit is outside task 187's original scope (task 187 wired the `.md` files into
`.edn` workflows; the `design-steps.md` change is a content regression in the `.md`
bodies). The four files must be reverted to use `steps.md` as the follow-up target.

No other actionable issues found. All 8 acceptance criteria are satisfied. Core
implementation (parser, compiler, wiring) is correct. Tests are comprehensive and green
(3 pre-existing unrelated failures unchanged). Docs updated. CHANGELOG updated.

## 2026-05-28 code-shaper follow-up

Executed all three code-shaper follow-up items.

1. **`markdown-session-step` — `cond-> ... true` removed**: replaced with
   `(merge {:name "step" :type :session :contributions ...} session-config)`.

2. **`compile-edn-workflow-file` — `cond-> ... true` removed**: threaded the
   always-true `:file-kind :edn` update unconditionally via `->`, keeping only
   the `source-path` conditional branch in `cond->`.

3. **`strip-yaml-single-quotes` — `''` escape limitation documented**: added
   docstring paragraph explaining that only outer delimiters are stripped, interior
   `''` sequences are not unescaped, and why this is safe in practice (EDN string
   values use double-quotes).

Focused compiler + parser + workflow-definitions + compiler-target-authoring tests:
13 tests, 156 assertions, 0 failures. `clj-kondo`: 0 errors, 0 warnings.

## 2026-05-28 code-shaper review

Applied code-shaper (simplicity ∧ consistency ∧ robustness) to compiler.clj, parser.clj, and the wired `.md` workflow files. Reviewed committed HEAD — the working tree has three unstaged regressions in `review-task-plan-{ambiguity-review,ambiguity-follow-up,inconsistency-review}.md` (unrelated to task 187; not included below).

**1. `markdown-session-step` — `cond-> ... true` is a no-op guard.**
`(cond-> {:name "step" :type :session :contributions ...} true (merge session-config))` — the `true` branch is always taken; `cond->` adds no value. Should be `(merge {:name "step" ...} session-config)`.

**2. `compile-edn-workflow-file` — second `cond-> ... true` no-op guard.**
`(cond-> (assoc config ...) true (update :workflow-file-meta ...) source-path ...)` — the `true` branch is always taken. The always-true update should be unconditional; only the `source-path` branch belongs in `cond->`.

**3. `strip-yaml-single-quotes` — undocumented limitation re YAML `''` escaping.**
YAML single-quoted strings escape an interior `'` as `''`. The helper strips outer delimiters but does not unescape `''` → `'` inside. A value like `'{"k" "it''s"}'` produces `{"k" "it''s"}` (invalid EDN). Low risk in practice (EDN string values use double-quotes) but the function is silently wrong for that input and carries no comment stating the scope.

No issues found in: `parse-vars-frontmatter` logic, `compile-prompt-workflow-step` vars threading, `markdown-body->contribution` merge order (standard-vars win correctly), test coverage shape, or the wired `.edn`/`.md` files at HEAD.

## 2026-05-28 review-task-docs follow-up

Executed both unchecked `review-task-docs` follow-up items.

1. **`doc/workflows.md` — `.md` authoring surface documented**: added a new
   "`.md` single-step workflow authoring" section in the Authoring guidelines area
   covering: (a) `{{input}}` and `{{original}}` auto-wiring with no frontmatter
   declaration needed; (b) the `vars:` frontmatter key (EDN string syntax) with a
   minimal example; (c) allowed `:from` values (`:workflow-input`, `:workflow-original`);
   (d) unknown-var compile-time error behaviour; (e) non-matching token pass-through.

2. **`CHANGELOG.md` — `[Unreleased]` `Added` entries**: added three bullet points
   covering `{{input}}`/`{{original}}` auto-wiring, the `vars:` frontmatter key, and
   the compile-time unknown-var error.

## 2026-05-28 review-task-docs

Reviewed `README.md`, `doc/workflows.md`, `doc/workflow-grammar.md`,
`doc/workflow-grammar-concepts.md`, and `CHANGELOG.md` against task 187 changes.

Two actionable gaps found.

**1. `doc/workflows.md` — `.md` authoring surface undocumented.**
The doc covers `.md` single-step workflow authoring (step 1 of the authoring loop,
`planner`/`builder`/`reviewer` as examples) but says nothing about the new
`{{input}}`/`{{original}}` auto-wiring convention, the `vars:` frontmatter key for
custom bindings, or the compile-time error for unknown `{{varname}}` tokens. These are
user-visible authoring behaviours that belong in the `.md` authoring section of
`doc/workflows.md`. At minimum: a short paragraph or example showing `{{input}}` usage
in a `.md` body, the `vars:` frontmatter key syntax, and the unknown-var error.

**2. `CHANGELOG.md` — no entry for user-visible `.md` workflow changes.**
The `[Unreleased]` section has no entry for: `{{input}}`/`{{original}}` expansion in
`.md` workflow bodies, the `vars:` frontmatter key, or the compile-time error for
unknown vars. These are new user-visible authoring capabilities (extension capability
class per changelog policy) and warrant an `Added` entry.

No other gaps: `README.md` delegates to `doc/workflows.md` (no direct authoring docs
to update). `doc/workflow-grammar.md` and `doc/workflow-grammar-concepts.md` cover the
general template/vars surface and do not need `.md`-specific additions. The `:prompt-workflow`
wiring change is internal (no user-facing authoring surface change).

## 2026-05-28 test-shaper review

Reviewed `parser_test.clj`, `compiler_test.clj`, `compiler_target_authoring_test.clj`, and
`workflow_definitions_test.clj` against the test-shaper criteria (clarity, signal, robustness,
economy). Three actionable gaps found.

**1. Unknown-var error test has weak assertion — var name not verified.**
`compiler_test.clj` "body with unknown {{foo}} not declared returns error" asserts only
`re-find #"Unknown \{\{varname\}\} tokens"`. The actual error message includes the unknown
var name (`"foo"`). The test passes even if the error message names the wrong var or omits
the name entirely. The assertion should also verify the specific unknown var name appears in
the error (e.g. `re-find #"\"foo\""` or `re-find #"foo"`).

**2. Non-pattern-matching tokens not tested — pass-through behavior unverified.**
Design step 2a states tokens that do not match `\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}` (e.g.
`{{1bad}}`, `{{}}`) pass through literally and are not subject to unknown-var errors. No
test covers this. Without it, a regression that widens the scan pattern would not be caught.

**3. `workflow_definitions_test` "actor steps have {{input}} wired" assertion covers
final-summary steps, which are intentionally inline (not wired).** The `doseq` over all
steps in `implement-task-test`, `review-task-design-test`, and `review-task-plan-test`
asserts `step-has-input-var-wired?` for every step including `final-summary`. The
`final-summary` steps happen to pass because the inline `.edn` contributions contain
`{{input}}` — but this is incidental. The assertion does not specifically verify the
prompt-workflow wiring mechanism. It would pass even if the wiring were broken and
`final-summary` were the only step. The wired steps (non-final-summary) should be
asserted separately and explicitly.

No other actionable gaps: all 8 ACs are covered, standard-vars override protection is
tested, `framing-prompt` absence is tested, parser valid/invalid `:from` values are tested,
and `vars:` threading through `:prompt-workflow` is tested.

## 2026-05-28 task-test-review

Reviewed all three slices (parser, compiler, wiring) against design ACs 1–8.
Tests: 13 tests, 150 assertions, 0 failures (focused compiler + parser + workflow-definitions + compiler-target-authoring).

Two actionable test gaps found:

1. **`vars:` `:from :workflow-original` not tested as valid in parser** —
   `parser_test.clj` only tests `{:from :workflow-input ...}` as a valid `:from`
   value in `vars:`. The allowed set is `#{:workflow-input :workflow-original}` per
   `parse-vars-frontmatter`, but `:workflow-original` has no positive acceptance test.

2. **`vars:` map-valued `:from` (step-output ref) not tested as invalid in parser** —
   `parse-vars-frontmatter` rejects any `:from` that is not `:workflow-input` or
   `:workflow-original`. A map `:from` like `{:step "x" :yield :text}` is explicitly
   out-of-scope per design but has no parser rejection test.

No other actionable gaps: all 8 ACs are covered, standard-vars override protection
is tested, `framing-prompt` absence is tested, wiring round-trips are tested.

## 2026-05-28 implementation-review follow-up

Fixed standard-vars override protection bug in `markdown-body->contribution`:
changed `(merge standard-vars declared-vars)` to `(merge declared-vars standard-vars)`
so that `"input"` and `"original"` always use their canonical source specs regardless
of any `vars:` frontmatter declaration.

Added compiler test `"standard vars always win over declared vars override attempts"`:
passes `vars: {"input" {:from :workflow-original}}` alongside `{{input}}` in body;
asserts compiled vars still resolve `"input"` to `{:from :workflow-input :path [:input]}`.

Focused compiler + parser + workflow-definitions tests: 12 tests, 141 assertions, 0 failures.
`clj-kondo`: 0 errors, 0 warnings.

## 2026-05-28 task-implementation-review

**Bug: standard-vars override protection violated.**
`markdown-body->contribution` does `(merge standard-vars declared-vars)`, so
`declared-vars` wins for `"input"` and `"original"`. The spec (steps.md Slice 2)
says "standard vars always use their canonical source specs regardless of any
override in `declared-vars`". Fix: `(merge declared-vars standard-vars)` so
standard-vars always win. No test covers this case.

No other actionable issues found. All three slices match design/spec. Tests
cover all acceptance criteria. `bb test` green.

## 2026-05-28 implementation complete

All three slices implemented and tests passing.

**Slice 1 (Parser):** Added `:vars` to `allowed-md-frontmatter-keys`; added
`strip-yaml-single-quotes` helper (YAML parser doesn't strip single quotes);
added `parse-vars-frontmatter` to read EDN scalar, validate map, check `:from`
values; updated `parse-markdown-workflow-file` return shape to include `:vars`.

**Slice 2 (Compiler):** Added `standard-vars` and `template-var-pattern` constants;
rewrote `markdown-body->contribution` to scan for `{{varname}}` tokens, auto-wire
standard vars, merge declared vars, throw `ex-info` on unknowns; removed
`:framing-prompt` from `compile-markdown-workflow-file`; updated
`compile-prompt-workflow-step` to pass `(:vars referenced)`.

**Slice 3 (Wiring):** Converted 5 steps in `review-task-design.edn`, 5 steps in
`review-task-plan.edn`, 1 step in `implement-task.edn`, 1 step in
`create-task-plan.edn` to `:prompt-workflow` references. Removed redundant
`:tools`/`:skills` from wired steps. Updated `workflow_definitions_test.clj` to
use new `load-edn-with-md-refs` helper for the four affected tests.

**Deviation:** `strip-yaml-single-quotes` helper was not in the design but is
required because `parse-yaml-frontmatter` does not strip YAML single-quote
delimiters. The design example uses `vars: '{...}'` (single-quoted) which the
YAML parser returns as `'...'` including the quotes.

## 2026-05-28 inconsistency follow-up pass 3

Executed all three follow-up items from inconsistency review pass 3.

1. **steps.md Slice 2 error mechanism fixed**: updated Slice 2 step wording from
   "return `{:error "..."}`" to "throw `ex-info`" — now matches `design.md` step 2d.

2. **steps.md Slice 2 regex fixed**: updated Slice 2 example regex from `#"\{\{(\w+)\}\}"`
   to `#"\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}"` — now matches `design.md` step 2a.

3. **steps.md Slice 3 `:tools`/`:skills` removal resolved**: chose option (a). Verified
   that all wired `.edn` step `:tools`/`:skills` values are identical to the corresponding
   `.md` frontmatter values across all four wiring targets (`review-task-design.edn`,
   `review-task-plan.edn`, `implement-task.edn`, `create-task-plan.edn`). Removing them
   from the `.edn` steps produces identical runtime behaviour — `merge-markdown-session-config`
   fills in from `.md` frontmatter when the step key is absent. Updated `design.md` step 5
   to explicitly authorize this removal with rationale.

## 2026-05-28 inconsistency review pass 3

Reviewed `plan.md` and `steps.md` against `design.md` and `compiler.clj`. Found three actionable
inconsistencies.

1. **steps.md Slice 2 error mechanism conflicts with design.md**: `steps.md` line 32 says
   `Error (return {:error "..."})` for unknown `{{varname}}` tokens. `design.md` implementation
   path step 2d explicitly says `throw ex-info` (caught by the existing
   `catch clojure.lang.ExceptionInfo` in `compile-workflow-file`). These are different
   mechanisms. Steps.md must be updated to match design.md.

2. **steps.md Slice 2 regex differs from design.md**: `steps.md` specifies the scanning regex
   as `#"\{\{(\w+)\}\}"` (allows digit-leading names, no hyphens). `design.md` step 2a specifies
   `\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}` (requires leading letter, allows hyphens). The patterns
   diverge on both leading-digit and hyphen handling. Steps.md must match design.md's pattern.

3. **steps.md Slice 3 adds `:tools`/`:skills` removal not present in design.md**: `steps.md`
   line 84 says "remove `:tools`, `:skills` step-level keys that are now covered by the
   referenced `.md` frontmatter". `design.md` implementation step 5 says only "Wire task 186
   `.edn` files to use `:prompt-workflow` and remove inline prompt text" — no mention of removing
   `:tools`/`:skills`. Since `merge-markdown-session-config` gives step-level keys precedence
   over `.md` frontmatter, removing them changes runtime behaviour. Design.md must either
   authorize this removal or steps.md must drop the step.

## 2026-05-28 inconsistency follow-up pass 2

Executed the one follow-up item from inconsistency review pass 2. Resolved in `design.md`.

1. **`design.md` wiring scope synced with `plan.md` final-summary exclusion**: updated
   `design.md` in three places:
   - Scope step counts reduced: `review-task-plan.edn` 6→5, `implement-task.edn` 2→1,
     `review-task-design.edn` 6→5 (final-summary steps removed from each list).
   - Added explicit exclusion note for `final-summary` steps in those three workflows,
     parallel to the `review-step.edn` exclusion note, with rationale (`:source`
     contributions carrying step-output yield refs that are out of scope for `.md`
     frontmatter vars).
   - Desired outcome updated: "all task 186 extracted `.md` files that do not carry
     `:source` contributions are wired…" with explicit callout that the three
     `final-summary` `.md` files are intentionally not wired.
   - Acceptance criterion 6 updated to the same precise wording, naming the three
     excluded files.

## 2026-05-28 inconsistency review pass 2

Reviewed `design.md` against `plan.md`, `compiler.clj`, and the `.psi/workflows/` corpus.
Found one actionable inconsistency. Added follow-up item to `design-steps.md`.

1. **`design.md` includes `final-summary` in wiring scope; `plan.md` excludes it.**
   `design.md` scope lines 71–73 and desired outcome list `final-summary` as a step to be
   wired via `:prompt-workflow` for `review-task-plan.edn` (6 steps), `implement-task.edn`
   (2 steps), and `review-task-design.edn` (6 steps). But `plan.md` explicitly records the
   decision (from plan ambiguity follow-up pass 1) to exclude `final-summary` steps from
   wiring in those three workflows, because they carry `:source` contributions
   (`{:from :workflow-original}` and `{:from {:step "..." :yield :text}}`) that
   `compile-prompt-workflow-step` would silently drop. Acceptance criterion 6 and the
   desired outcome in `design.md` also say "all task 186 extracted `.md` files are
   referenced" — but `implement-task-final-summary.md`, `review-task-plan-final-summary.md`,
   and `review-task-design-final-summary.md` are intentionally NOT wired per `plan.md`.
   `design.md` must be updated to reflect the exclusion decision.

## 2026-05-28 plan ambiguity follow-up pass 1

Executed both follow-up items from plan ambiguity review pass 1.

1. **Final-summary wiring decision**: chose option (a) — exclude final-summary steps from
   wiring in `implement-task.edn`, `review-task-plan.edn`, and `review-task-design.edn`.
   Rationale: these steps carry `:source` contributions with step-output yield refs
   (`{:from {:step "X" :yield :text}}`) which are out of scope for `.md` frontmatter vars.
   Wiring would silently drop them. The three `.md` final-summary files exist but are
   intentionally not referenced by their parent `.edn` workflows. `create-task-plan.edn`'s
   single step has no source contributions and is wired normally.
   Updated: `plan.md` (Key decisions + Risks), `steps.md` Slice 3 wiring instructions.

2. **Test fixture gap**: documented in `plan.md` Risks. Updated Slice 3 test step in
   `steps.md` to explicitly require updating (not just adding to) the four existing
   `deftest` blocks to use `with-workflow-dir` with both the `.edn` and all referenced
   `.md` files.

## 2026-05-28 plan ambiguity review pass 1

Reviewed `plan.md` and `steps.md` against `compiler.clj`, `workflow_definitions_test.clj`,
and the `.psi/workflows/` `.edn`/`.md` corpus. Found two actionable ambiguities.

1. **Final-summary steps have `:source` contributions that would be silently dropped by wiring.**
   `implement-task.edn`, `review-task-plan.edn`, and `review-task-design.edn` final-summary steps
   each carry `{:type :source :from :workflow-original}` and `{:type :source :from {:step "X" :yield :text}}`
   contributions alongside the template. `compile-prompt-workflow-step` replaces ALL `:contributions`
   with the `.md` body. The `.md` final-summary files (`implement-task-final-summary.md`,
   `review-task-plan-final-summary.md`, `review-task-design-final-summary.md`) contain only `{{input}}`
   — no source references. Wiring as planned silently drops the step-output context that these
   sessions depend on. The plan/steps are silent on this. Must decide: (a) exclude final-summary
   steps from wiring (keep inline, like review-step.edn), (b) express the sources in the `.md` files
   somehow, or (c) explicitly accept the behavior change. `create-task-plan.edn` is unaffected
   (its single step has only a template contribution).

2. **Existing `workflow_definitions_test.clj` tests use `load-edn-only` and will break after wiring.**
   All four affected workflows (`review-task-design`, `review-task-plan`, `implement-task`,
   `create-task-plan`) already have `deftest` blocks using `load-edn-only`, which copies only the
   `.edn` file to a temp dir. After wiring, the `.edn` steps reference `.md` files via
   `:prompt-workflow`; `compile-prompt-workflow-step` resolves them relative to the `.edn` path and
   returns an error if not found. The existing tests would fail immediately. `steps.md` says
   "add loader tests" but the existing tests must also be updated to use `with-workflow-dir` with
   both the `.edn` and all referenced `.md` files. This is not mentioned anywhere in the plan.

## 2026-05-28 ambiguity review pass 2

Reviewed design.md against compiler.clj, parser.clj, source_resolution.clj, and the .psi/workflows/ corpus.
Found three actionable ambiguities. Added follow-up items to design-steps.md.

1. **`vars:` valid `:from` values unspecified**: design says "supported `:from` values match the same
   source-spec grammar used in `.edn` :vars" but `.edn` :vars support step-output/step-yield refs
   (map `:from` values) which are explicitly out-of-scope for `.md` frontmatter vars. The design
   contradicts itself. Validation must restrict `:from` to keyword-only values
   (`:workflow-input`, `:workflow-original`, etc.) but the allowed set is never enumerated.

2. **`{{varname}}` token scanning pattern unspecified**: `markdown-body->contribution` is described
   as scanning for `{{varname}}` tokens but the character set valid in `varname` is never stated.
   This affects both auto-wiring and the unknown-var error detection regex/pattern.

3. **Unknown-var compile-time error propagation unspecified**: `compile-markdown-workflow-file`
   currently has no error-return path — it always returns `{:definition ...}`. If
   `markdown-body->contribution` needs to signal an unknown-var error, the design doesn't specify
   the mechanism (throw `ex-info`, return `{:error ...}`, etc.) or how callers handle it.
   The `:prompt-workflow` path already has `{:error ...}` handling; the standalone `.md` path does not.

## 2026-05-28 ambiguity follow-up pass 1

Executed all five design-steps from ambiguity review pass 1. All resolved in design.md.

1. **`vars:` parsing → EDN string**: chose option (b). `vars:` value is a scalar string
   read via `clojure.edn/read-string`. No changes to `parse-yaml-frontmatter` required.
   Design updated with EDN syntax example and parser rationale.

2. **Implementation step 4 corrected**: design now states `compile-prompt-workflow-step`
   must pass `(:vars referenced)` to `markdown-body->contribution`, and that
   `markdown-body->contribution` must accept an optional vars argument.

3. **`{{original}}` → `:workflow-original`**: changed from `:workflow-input :path [:original]`
   to `{:from :workflow-original}` for consistency with `.edn` usage and to preserve the
   `resolve-source-ref` fallback (avoids `nil` when `:original` absent from workflow-input).
   Rationale documented in design.

4. **Test update noted**: acceptance criterion 7 added — existing
   `compiler_target_authoring_test.clj` `:framing-prompt` assertion must be updated to
   assert absence. Also added to the scope's test list.

5. **Wiring gap targets enumerated**: scope now explicitly lists the four `.edn` files and
   their step counts. `review-step.edn` exclusion noted. Desired outcome updated to name
   the four files.

## 2026-05-28 ambiguity review pass 1

Reviewed `design.md` against `components/workflow-loader/src/psi/workflow_loader/compiler.clj`,
`parser.clj`, `components/prompt-assets/src/psi/prompt_assets/prompt_templates.clj`
(`parse-yaml-frontmatter`), `components/workflow-step-materialization/src/psi/workflow_step_materialization/source_resolution.clj`,
`components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`,
`components/workflow-loader/test/psi/workflow_loader/compiler_target_authoring_test.clj`,
and the current `.psi/workflows/` `.edn`/`.md` files.

Found five actionable ambiguities. Added follow-up items to `design-steps.md`.

1. **`vars:` frontmatter YAML parsing is unspecified**: `parse-yaml-frontmatter` supports
   only scalars and block sequences — it cannot parse nested maps. The design's `vars:`
   example requires nested map syntax (`my-var:\n  from: workflow-input\n  path: [some-field]`).
   The design says "update the parser" but does not specify the parsing approach (extend
   the YAML parser, use EDN syntax for the value, inline-map syntax, etc.).

2. **Implementation step 4 is incorrect — `:prompt-workflow` path needs a change**:
   `compile-prompt-workflow-step` calls `(markdown-body->contribution (:body referenced))`.
   After step 1 adds `:vars` to the parsed result, `compile-prompt-workflow-step` must
   also pass `(:vars referenced)` to `markdown-body->contribution`. The design states
   "no additional change needed there" — this is wrong.

3. **`{{original}}` source spec choice is unexplained**: Design specifies `{{original}}` →
   `{:from :workflow-input :path [:original]}`. Existing `.edn` workflows use
   `{:from :workflow-original}` or `{:from :workflow-original :path [:original]}`.
   `resolve-source-ref` for `:workflow-original` has special fallback logic that diverges
   from `:workflow-input :path [:original]` when `:workflow-original` is explicitly set
   on the run. The design doesn't explain the choice or whether `:workflow-original`
   should be used instead.

4. **Existing test `compiler_target_authoring_test.clj` will break**: Line 19 asserts
   `(get-in definition [:workflow-file-meta :framing-prompt])` equals `"Frame it."`.
   Removing `:framing-prompt` from `compile-markdown-workflow-file` breaks this test.
   The design doesn't mention updating it.

5. **Task 186 wiring gap scope is unenumerated**: Design says "update all affected `.edn`
   workflows" without listing them. Based on orphaned `.md` files in `.psi/workflows/`:
   `review-task-plan.edn` (5 steps), `implement-task.edn` (2 steps),
   `review-task-design.edn` (6 steps), `create-task-plan.edn` (1 step).
   (`review-step.edn` was intentionally left inline per task 186 impl notes.)

## 2026-05-28 inconsistency follow-up pass 1

Executed all three design-steps from inconsistency review pass 1. All resolved in design.md.

1. **`review-task-plan.edn` step count → 6**: chose option (a). Created
   `.psi/workflows/review-task-plan-final-summary.md` (parallel to
   `review-task-design-final-summary.md`). Updated design scope step count from 5 to 6.
   The `.md` file is now part of the task 186 wiring corpus for `review-task-plan.edn`.

2. **`allowed-md-frontmatter-keys` added to implementation step 1**: updated the
   implementation path to explicitly name adding `:vars` to `allowed-md-frontmatter-keys`
   as a prerequisite sub-step within step 1.

3. **`parse-markdown-workflow-file` return shape specified**: implementation step 1 now
   explicitly documents the updated return shape:
   `{:workflow-kind :single-step-markdown :name string :description string
     :session-config map :body string :vars map-or-nil}`.

## 2026-05-28 inconsistency review pass 1

Reviewed `design.md` against `compiler.clj`, `parser.clj`,
`compiler_target_authoring_test.clj`, and the current `.psi/workflows/` corpus.
Found three actionable inconsistencies. Added follow-up items to `design-steps.md`.

1. **`review-task-plan.edn` step count wrong**: design scope says 5 steps
   (ambiguity-review, ambiguity-follow-up, inconsistency-review,
   inconsistency-follow-up, clarity-status), but the actual file has 6 steps
   including `final-summary`. No `review-task-plan-final-summary.md` exists.
   The desired outcome says "all task 186 extracted `.md` files are referenced"
   — yet `final-summary` in `review-task-plan.edn` has no `.md` counterpart and
   is unaddressed. Inconsistency between the step count, the `.md` corpus, and
   the desired outcome.

2. **`allowed-md-frontmatter-keys` not updated in implementation path**: the
   parser rejects unknown frontmatter keys via `unsupported-frontmatter-error`.
   Adding `vars:` support requires adding `:vars` to `allowed-md-frontmatter-keys`
   in `parser.clj`. Implementation step 1 says "read and validate the `vars:`
   frontmatter key" but omits this prerequisite change. Without it, any `.md`
   file using `vars:` will fail with an unsupported-key error before the new
   parsing logic is reached.

3. **Parsed result shape for `vars:` not specified**: implementation step 4
   references `(:vars referenced)` on the parsed result, implying `parse-markdown-workflow-file`
   must return a `:vars` key. The design never updates the documented return
   shape (`{:workflow-kind :name :description :session-config :body}`). This
   creates a gap between step 1 (parser) and step 4 (compiler): the compiler
   assumes a `:vars` key that the parser contract does not define.

## 2026-05-28 ambiguity follow-up pass 2

Executed all three design-steps from ambiguity review pass 2. All resolved in design.md.

1. **Valid `:from` values for `vars:` frontmatter**: restricted to `:workflow-input` and
   `:workflow-original` only. These are the only keyword `:from` values handled by
   `resolve-source-ref` / `apply-source-spec`. `:workflow-runtime` is handled only by
   `resolve-binding-ref` (a different code path) and is therefore invalid here. Step-output
   and step-yield map `:from` refs are already out of scope per the design. The `vars:`
   frontmatter section now states the exact allowed set and the rationale. Validation
   must reject any other `:from` value.

2. **`{{varname}}` token scanning pattern**: specified as `\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}`.
   Leading letter required; subsequent chars may be letters, digits, underscores, or hyphens.
   Tokens not matching this pattern pass through literally and are not subject to unknown-var
   errors. Added to implementation path step 2a.

3. **Unknown-var error propagation**: chose option (a) — throw `ex-info` from
   `markdown-body->contribution`. `compile-workflow-file` already wraps all compilation in
   `(catch clojure.lang.ExceptionInfo e {:error (.getMessage e)})`, so no error-return path
   needs to be added to `compile-markdown-workflow-file`. Implementation path steps 2d and 3
   updated to document this mechanism explicitly.

## Closure (2026-05-31 audit)

Closed as complete during the open-task reconciliation audit. All `steps.md` items checked and review loops recorded no actionable feedback.
