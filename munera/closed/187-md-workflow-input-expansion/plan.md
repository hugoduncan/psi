# Plan — 187 `.md` workflow `{{input}}` expansion

## Approach

Three independent slices in dependency order, with tests alongside each:

1. **Parser** — teach `parse-markdown-workflow-file` to read and validate a
   `vars:` frontmatter key (EDN string → map). Prerequisite for compiler slice.

2. **Compiler** — wire `{{input}}`/`{{original}}` auto-expansion and error on
   unknown tokens; remove `framing-prompt` system-layer injection; thread `vars`
   through `compile-prompt-workflow-step`. Depends on parser slice.

3. **Wiring** — convert four `.edn` workflows to reference their extracted `.md`
   prompt files via `:prompt-workflow`. Depends on compiler slice (`.md` files
   must expand correctly before wiring is useful).

Tests are written or updated within each slice before moving to the next.

## Key decisions

- `vars:` parsed as an EDN scalar string — no change to the YAML parser needed.
- Standard vars (`{{input}}`, `{{original}}`) auto-wired; unknown vars are a
  compile-time error.
- `framing-prompt` removed entirely from `compile-markdown-workflow-file`;
  single-step `.md` body belongs in the user turn only.
- `markdown-body->contribution` gains an optional `declared-vars` argument so
  `compile-prompt-workflow-step` can thread vars from referenced `.md` files.
- `review-step.edn` is explicitly excluded from wiring (inline prompt retained).
- `.edn` step `:tools`/`:skills` take precedence over `.md` frontmatter via
  `merge-markdown-session-config`; wiring keeps existing `.edn` values in place.
- **Final-summary steps in `implement-task.edn`, `review-task-plan.edn`, and
  `review-task-design.edn` are excluded from wiring** (option a). Each carries
  `:source` contributions referencing `:workflow-original` and step-output yields
  (`{:from {:step "X" :yield :text}}`). Step-output refs are out of scope for
  `.md` frontmatter vars; wiring would silently drop them, breaking the sessions
  that depend on that context. The three `.md` final-summary files exist but are
  not referenced by their parent `.edn` workflows — this is intentional.
  `create-task-plan.edn`'s single step has no source contributions and is wired
  normally.

## Risks

- `compiler_target_authoring_test.clj` currently asserts
  `(= "Frame it." (get-in definition [:workflow-file-meta :framing-prompt]))`.
  This must be updated in the compiler slice or it will fail immediately.
- The four `.edn` wiring targets have between 1 and 6 steps each; each step
  must name a `.md` file that already exists. Verify all `.md` files exist
  before wiring.
- The four existing `deftest` blocks in `workflow_definitions_test.clj` use
  `load-edn-only`, which copies only the `.edn` file to a temp dir. After
  wiring, the `.edn` files reference `.md` files via `:prompt-workflow`; the
  existing tests must be updated to use `with-workflow-dir` with both the `.edn`
  and all referenced `.md` files, or to read from the real `.psi/workflows` dir
  directly. The Slice 3 test step must update (not just add to) the existing
  `deftest` blocks.

## Slice order

1. Parser: `allowed-md-frontmatter-keys` + `vars:` parsing + parser tests
2. Compiler: `markdown-body->contribution` + `compile-markdown-workflow-file` +
   `compile-prompt-workflow-step` + compiler tests (including framing-prompt
   absence assertion fix)
3. Wiring: four `.edn` files + wiring tests (`:prompt-workflow` round-trip)
4. Final: `bb test` green, confirm all ACs
