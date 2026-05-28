# 187 `.md` workflow `{{input}}` expansion

## Intent

Make `{{input}}` (and `{{original}}`) in `.md` workflow files expand correctly at
runtime, so `/delegate` prompt text reaches the child session as the rendered
content — not as a literal token. Simultaneously remove the erroneous
system-layer body injection from single-step `.md` workflows, and complete the
task 186 wiring gap so the extracted `.md` prompt files are actually used.

## Problem

### 1. `{{input}}` is never expanded in `.md` files

`markdown-body->contribution` always produces:
```clojure
{:type :template :text body :vars {}}
```
`:vars {}` is empty, so `render-template-contribution` never substitutes
`{{input}}`. The child session receives the literal token `{{input}}` in its
user turn instead of the actual `/delegate` prompt text.

### 2. Body is incorrectly injected into the system layer

`compile-markdown-workflow-file` stores the body in two places:

```clojure
:steps [(markdown-session-step parsed)]       ; body → contributions → user turn
:workflow-file-meta {:framing-prompt body}    ; body → developer-prompt → system layer
```

The system-layer injection is wrong. Psi's system prompt is a structured
composition pipeline (identity, capabilities, skills). Workflow body text is a
task instruction — it belongs in the user turn, not in the system layer. The
`framing-prompt` path bypasses system prompt composition and creates redundancy:
the same body text ends up in both layers, and after the `{{input}}` fix the
system layer would still show the literal token while the user turn has the
expanded text.

`.edn` multi-step workflow steps do not inject into the system layer at all;
single-step `.md` doing so is an inconsistency. If a workflow genuinely needs a
system-level identity override the right mechanism is the `:system-prompt`
frontmatter key, not the body text.

### 3. Task 186 wiring gap

The `.md` prompt files extracted in task 186 are orphaned. None of the `.edn`
workflow files were updated to reference them via `:prompt-workflow`. The `.edn`
files still carry their original inline prompt text, so the extracted `.md` files
have no effect at runtime.

## Scope

### In scope

- Remove the `framing-prompt` injection from `compile-markdown-workflow-file`:
  single-step `.md` workflows no longer push the body into the system/developer
  layer. The body belongs in the user turn only.
- Establish `{{input}}` and `{{original}}` as first-class conventions in `.md`
  workflow bodies:
  - `{{input}}` → `{:from :workflow-input :path [:input]}`
  - `{{original}}` → `{:from :workflow-original}`
- Support a `vars:` frontmatter key in `.md` workflow files for declaring
  arbitrary additional var bindings beyond the two standard ones.
- Fix `markdown-body->contribution` to auto-wire standard vars and merge
  frontmatter-declared vars into the produced `:vars` map.
- Update the parser to read and validate the `vars:` frontmatter key.
- Complete the task 186 wiring gap: update the four affected `.edn` workflows to
  reference their extracted `.md` prompt files via `:prompt-workflow`, removing
  the now-redundant inline prompt text:
  - `review-task-plan.edn` (5 steps: ambiguity-review, ambiguity-follow-up, inconsistency-review, inconsistency-follow-up, clarity-status)
  - `implement-task.edn` (1 step: implement-pass)
  - `review-task-design.edn` (5 steps: ambiguity-review, ambiguity-follow-up, inconsistency-review, inconsistency-follow-up, clarity-status)
  - `create-task-plan.edn` (1 step: create-plan)
  - `review-step.edn` is intentionally excluded (inline prompt retained per task 186 decision)
  - `final-summary` steps in `review-task-plan.edn`, `implement-task.edn`, and
    `review-task-design.edn` are intentionally excluded from wiring: each carries
    `:source` contributions referencing `:workflow-original` and step-output yields
    (`{:from {:step "X" :yield :text}}`). Step-output refs are out of scope for `.md`
    frontmatter vars; wiring would silently drop them. The three `.md` files
    (`review-task-plan-final-summary.md`, `implement-task-final-summary.md`,
    `review-task-design-final-summary.md`) exist but are not referenced by their
    parent `.edn` workflows — this is intentional.
- Add or update loader/compiler tests covering:
  - `{{input}}` expansion in single-step `.md` workflows
  - `{{original}}` expansion
  - frontmatter `vars:` declarations (EDN string syntax)
  - unknown `{{varname}}` tokens produce a compile-time error
  - `:prompt-workflow` references with `{{input}}` expand correctly
  - system layer does not receive the body text for single-step `.md` workflows
  - update existing `compiler_target_authoring_test.clj` assertion to assert
    `:framing-prompt` is absent (not present with value `"Frame it."`)

### Out of scope

- Backward compatibility with existing `.md` files that rely on `{{input}}`
  being literal (no such use is intentional).
- Changing how `.edn` inline template `:vars` work (`.edn` authoring is
  unaffected).
- Supporting step-output references (e.g. `{{step-name}}`) in `.md` frontmatter
  vars — these require step-context resolution that `.md` files don't carry.
- Migrating any remaining `.md` multi-step workflows that embed EDN blocks.

## Desired outcome

After this task:

- Every `{{input}}` in a `.md` workflow body (single-step or `:prompt-workflow`
  reference) is substituted with the workflow input at runtime.
- Every `{{original}}` is substituted with the workflow original input.
- Unknown `{{varname}}` tokens that are neither standard nor declared in
  frontmatter `vars:` produce a clear compile-time error at workflow load.
- Single-step `.md` workflows no longer inject the body into the system/developer
  layer; the body is the user turn only, with vars expanded.
- All task 186 extracted `.md` files that do not carry `:source` contributions
  are wired into their `.edn` workflows via `:prompt-workflow`; duplicate inline
  prompt text is removed from the four target `.edn` files (`review-task-plan.edn`,
  `implement-task.edn`, `review-task-design.edn`, `create-task-plan.edn`). The
  three `final-summary` `.md` files (`review-task-plan-final-summary.md`,
  `implement-task-final-summary.md`, `review-task-design-final-summary.md`) are
  intentionally not wired.
- `bb test` is green.

## Design decisions

### `{{input}}` and `{{original}}` as conventions, not declarations

No frontmatter is required to use `{{input}}` or `{{original}}`. They are
recognised automatically in any `.md` body by scanning for `{{varname}}`
tokens and wiring to their standard source specs. This matches the universal
intent of all existing `.md` files.

Standard var source specs:
- `{{input}}` → `{:from :workflow-input :path [:input]}`
- `{{original}}` → `{:from :workflow-original}`

`{{original}}` maps to `:workflow-original` (not `:workflow-input :path [:original]`)
for consistency with all existing `.edn` workflows and to preserve the
`resolve-source-ref` fallback behaviour: when `:workflow-original` is not
explicitly set on the run, it falls back to
`(get-in workflow-input [:original])` then to `workflow-input` itself.
Using `:workflow-input :path [:original]` would return `nil` when `:original`
is absent, which is the wrong behaviour for this convention.

### `vars:` frontmatter for non-standard bindings

The `vars:` frontmatter value is an EDN string that reads as a map from var name
string to a source-spec map.

```yaml
---
name: my-step
vars: '{"my-var" {:from :workflow-input :path [:some-field]}}'
---
Body text with {{my-var}}.
```

**Parsing approach**: the `vars:` frontmatter key is read as a scalar string by
`parse-yaml-frontmatter` (existing behaviour). `parse-markdown-workflow-file`
then calls `clojure.edn/read-string` on that string to produce the vars map.
This avoids adding nested-map YAML parsing to the custom `parse-yaml-frontmatter`
implementation, which only supports scalars and block sequences. Validation
checks that the parsed value is a map and that each source-spec has a recognised
`:from` value. An EDN parse failure is a compile-time error.

**Valid `:from` values for `vars:` frontmatter**: only keyword `:from` values
that are handled by `resolve-source-ref` / `apply-source-spec` are permitted:

- `:workflow-input` — the workflow input map (use `:path` to extract a field)
- `:workflow-original` — the workflow original input (with fallback semantics)

Step-output references (`{:from {:step ... :output ...}}`) and step-yield
references (`{:from {:step ... :yield ...}}`) are **out of scope** for `.md`
frontmatter — they require step-context resolution that `.md` files don't carry.
`:workflow-runtime` is **not** a valid `:from` value here: it is only handled by
`resolve-binding-ref`, not by `resolve-source-ref` / `apply-source-spec`.
Validation must reject any `:from` value that is not `:workflow-input` or
`:workflow-original`.

### Unknown vars → compile-time error

Any `{{varname}}` in the body that is neither a standard var nor declared in
`vars:` frontmatter is a compile error at workflow load time, not a silent
pass-through. This catches authoring mistakes early.

### Session-config precedence: `.edn` over `.md` frontmatter

When a step uses `:prompt-workflow`, session-config keys present in the `.edn`
step (`:tools`, `:skills`, `:model`, `:thinking-level`, etc.) take precedence
over those in the referenced `.md` frontmatter. The `.md` frontmatter provides
defaults that fill in any keys absent from the `.edn` step.

This is already the behaviour of `merge-markdown-session-config` — it only
copies a key from the `.md` session-config when the step does not already
contain it. No code change is required; the design records this as an explicit
invariant.

**Implication for wiring (Slice 3):** when converting `.edn` inline steps to
`:prompt-workflow` references, keep the existing `:tools`/`:skills` values in
the `.edn` step. They will continue to take precedence over the `.md`
frontmatter, and callers can tune them per-step without modifying the shared
`.md` file.

### No system-layer injection

`compile-markdown-workflow-file` no longer sets `[:workflow-file-meta
:framing-prompt]`. The body belongs in the user turn — it is a task instruction,
not system-level identity or capability context. Workflows that need a
system-level override use the `:system-prompt` frontmatter key, which is already
supported and flows through the normal `developer-prompt` path.

### Implementation path

1. Update `parse-markdown-workflow-file` in `parser.clj` to read and validate
   the `vars:` frontmatter key. This requires:
   - Adding `:vars` to `allowed-md-frontmatter-keys` in `parser.clj` so the
     unsupported-key guard does not reject `.md` files that use `vars:`.
   - Calling `clojure.edn/read-string` on the raw `vars:` scalar string and
     validating the result is a map with recognised `:from` values.
   - Returning the parsed vars map (or `nil`) under a `:vars` key in the result,
     so `parse-markdown-workflow-file` returns:
     `{:workflow-kind :single-step-markdown :name string :description string
       :session-config map :body string :vars map-or-nil}`.
2. Update `markdown-body->contribution` in `compiler.clj` to:
   a. Scan body for all `{{varname}}` tokens using the pattern
      `\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}`. This pattern matches a leading letter
      followed by zero or more letters, digits, underscores, or hyphens. Tokens
      that do not match this pattern are not substituted and are not considered
      unknown-var errors (they pass through literally).
   b. Auto-wire `input` and `original` to their standard source specs.
   c. Merge any frontmatter `vars:` declarations (passed as an optional second
      argument, defaulting to `nil` / empty).
   d. Error on any remaining unresolved `{{varname}}` tokens (matched by the
      pattern above but not in the wired set): throw `ex-info` with a descriptive
      message. `compile-workflow-file` already wraps all compilation in
      `(catch clojure.lang.ExceptionInfo e {:error (.getMessage e)})`, so the
      thrown exception is automatically converted to `{:error ...}` without
      requiring `compile-markdown-workflow-file` to gain its own error-return
      path. The standalone `.md` path and the `:prompt-workflow` path both
      benefit from this single catch point.
3. Update `compile-markdown-workflow-file` in `compiler.clj` to remove the
   `:framing-prompt body` entry from `workflow-file-meta`. No error-return path
   needs to be added to `compile-markdown-workflow-file`: unknown-var errors
   thrown by `markdown-body->contribution` propagate as `ex-info` and are caught
   by the existing `catch clojure.lang.ExceptionInfo` in `compile-workflow-file`.
4. Update `compile-prompt-workflow-step` in `compiler.clj` to pass
   `(:vars referenced)` to `markdown-body->contribution` alongside `(:body referenced)`,
   so frontmatter-declared vars from the referenced `.md` file are honoured.
   `markdown-body->contribution` must accept an optional vars argument.
5. Wire task 186 `.edn` files to use `:prompt-workflow` and remove inline
   prompt text. Also remove `:tools` and `:skills` step-level keys from each
   wired `.edn` step: these keys are identical to the corresponding `.md`
   frontmatter values, so `merge-markdown-session-config` will supply them from
   the `.md` file. Removing the duplicates makes the `.md` frontmatter the
   single source of truth for session config on wired steps and eliminates
   redundancy. (Runtime behaviour is unchanged because `merge-markdown-session-config`
   only fills in keys absent from the step; when the step has them they take
   precedence — but since the values are identical, removing them is safe.)
6. Update tests.

## Acceptance criteria

1. `{{input}}` in a single-step `.md` workflow body expands to the `/delegate`
   prompt text at runtime.
2. `{{original}}` expands to the workflow original input at runtime.
3. `{{varname}}` declared in frontmatter `vars:` expands to the declared source
   at runtime.
4. An unknown `{{varname}}` (not standard, not declared) produces a
   compile-time error at workflow load.
5. Single-step `.md` workflows no longer inject the body into the
   system/developer layer; `workflow-file-meta` carries no `:framing-prompt`.
6. All task 186 extracted `.md` files that do not carry `:source` contributions
   are referenced by their parent `.edn` workflows via `:prompt-workflow`; no
   duplicate inline prompt text remains in those `.edn` files. The three
   `final-summary` `.md` files (`review-task-plan-final-summary.md`,
   `implement-task-final-summary.md`, `review-task-design-final-summary.md`) are
   intentionally not wired.
7. The existing `compiler_target_authoring_test.clj` assertion
   `(get-in definition [:workflow-file-meta :framing-prompt])` is updated to
   assert absence of `:framing-prompt` (i.e. the key is not present) rather
   than asserting its value equals `"Frame it."`.
8. `bb test` is green after all changes.
