# 184 package psi-owned skills inside psi via the easiest path

## Intent

Choose and document the simplest correct way for psi to ship its own skills as a built-in product capability on current `master`.

This remains a design task, not an implementation task. Its job is to remove ambiguity about how psi-owned skills should be packaged, discovered, enabled, consumed by the AI, and documented before any code or migration work begins.

## Why this task exists now

Current `master` has already clarified several adjacent realities:

- skills now have a canonical runtime definition store via `components/skill-registry/src/psi/skill_registry/root_storage.clj`
  - canonical skill definitions live in shared root-registry storage under `:skills`
  - sessions own skill membership via `:skill-ids`
- startup still discovers shipped/project/user skills from ordinary filesystem paths via `components/prompt-assets/src/psi/prompt_assets/skills.clj`
  - global skills: `~/.psi/agent/skills`
  - project skills: `.psi/skills`
  - extra paths: CLI-configured additional paths
- startup still calls `skills/discover-skills` from `components/app-runtime/src/psi/app_runtime.clj`
- the library build already packages runtime code components, including `prompt-assets` and `skill-registry`, but there is no current packaged-resource design for psi-owned skill content itself
- prompt rendering and skill invocation still assume file-backed skills with absolute `:file-path` and `:base-dir`, and the prompt explicitly teaches the model to resolve relative references against the skill directory

That means the old question is now sharper:

- runtime canonical skill *registration* is already root-registry-backed
- but psi-owned skill *content discovery and AI access* are still filesystem-local
- and that filesystem assumption becomes fragile once psi is shipped or run outside a source tree

So this task should no longer treat skill packaging as a greenfield abstraction choice. It should define the smallest move from the current architecture to a packaged built-in skill story that still fits the runtime as it exists today.

## Problem statement

Psi needs a first-class way to include psi-owned skills in the product distribution itself rather than relying only on external user/project skill directories.

The open question is not whether psi should support psi-owned skills, but how to do so with the least complexity and the clearest user model on top of the current codebase.

The design must solve both halves of the problem:

1. how psi-owned skills are packaged, discovered, and registered
2. how the AI reads and uses those skills when psi is running from a jar or other non-source-tree packaging

## Current-master baseline

### What currently counts as a skill at runtime

A runtime skill today is effectively a canonical map with at least:

- `:name`
- `:description`
- `:file-path`
- `:base-dir`
- `:source`
- optional display/control fields such as `:lambda-description` and `:disable-model-invocation`

The prompt and invocation path still treat the skill as a readable artifact, not merely metadata:

- prompt prelude exposes the skill name/description/location
- `/skill:name` loads full `SKILL.md` content on demand
- relative references inside the skill are resolved against the skill directory

### What current master already gives us

- canonical skill definition authority is no longer tied to only ad hoc vectors
- session membership is already a distinct concern from definition storage
- skill-list ordering is already canonicalized by skill name rather than registration order
- skill loading is still separate from extension lifecycle machinery

These are strong signals that the easiest path should preserve the current conceptual model:

- a skill is still a skill
- its content is still an authored artifact
- runtime should gain a packaging/discovery path, not a full semantic rewrite

## Design questions to answer

The resulting design must answer all of these clearly and unambiguously:

1. What exactly counts as a psi-owned skill?
2. Where do psi-owned skill files live in the repository?
3. What file layout is canonical for a packaged skill?
4. How are psi-owned skills discovered at startup?
5. Are psi-owned skills always available, enabled by default, manifest-activated, or controlled by some other mechanism?
6. How do psi-owned skills appear in the runtime capability model and introspection surfaces?
7. How do psi-owned skills coexist with user-level or project-level skills?
8. If two skills have the same name, what precedence/override rule applies?
9. How does the AI read and use a psi-owned skill when psi is running from a jar or other non-source-tree packaging?
10. Is the AI-facing consumption model still ordinary file `read`, or does it change to another runtime surface?
11. If the AI no longer reads skills directly from repository files, how are relative references from `SKILL.md` handled?
12. What documentation surfaces must change once the design is implemented?
13. Why is the chosen model easier than the rejected alternatives?

## Scope

In scope:

- compare the workflow packaging model and the extension packaging model as reference designs
- identify the smallest viable packaging/discovery/activation model for psi-owned skills on current `master`
- define the canonical repository layout for psi-owned skills
- define runtime loading and availability semantics
- define precedence/coexistence rules between psi-owned skills and externally supplied skills
- define how the AI accesses and reads psi-owned skills when they are packaged rather than source-tree-local
- compare at least these AI access strategies:
  - project or cache materialization to readable disk paths
  - runtime/classpath resource access through a dedicated surface
  - direct prompt injection rather than read-based access
- define the user-facing explanation/documentation shape for the chosen model
- define acceptance criteria for the follow-up implementation task

Out of scope:

- implementing the packaging change
- migrating existing skills into the chosen location
- changing the semantics of what a skill is beyond what is required for packaging/discovery and AI access
- changing workflow or extension behavior except where the design explicitly depends on an existing surface
- broad unification of skills, workflows, and extensions into a single generalized asset system

## Constraints

- Prefer the smallest architectural move that fits the current psi model.
- Reuse existing patterns where doing so reduces complexity.
- Do not force skills through extension lifecycle machinery unless that produces a meaningfully simpler system overall.
- Keep the user model easy to explain: a reader of the docs should be able to tell where psi-owned skills come from, why they are available, and how the AI reads them.
- Preserve the conceptual distinction between skills, workflows, and extensions unless the design explicitly justifies convergence.
- The chosen approach must allow psi to ship core skills without requiring users to activate them one by one manually.
- The chosen approach should be compatible with future packaging/distribution of psi, including non-source-tree usage, without requiring this task to solve all release mechanics in detail.
- If possible, preserve the current simple AI contract that skills are readable artifacts rather than hidden runtime-only prompt state.
- Align with current `master` skill authority: canonical runtime skill definitions should continue to flow through `skill-registry` / root-registry-backed storage rather than inventing a second authoritative skill store.

## Decision summary

### Recommended packaging / activation option

**Choose Option C — dedicated minimal built-in skill path.**

Psi-owned skills should be a first-class built-in skill asset class with its own narrow loading path, separate from extension lifecycle machinery and lighter than workflow packaging.

### Recommended AI access option

**Choose Access 1 — materialize packaged skills to readable disk.**

Packaged psi-owned skills should ship as jar/classpath resources, but runtime should materialize them to a managed readable cache directory before registration so the AI can continue using ordinary file `read` and current relative-path semantics.

## Why this is the easiest path on current master

Because current `master` already separates:

- skill definition authority
- session membership
- prompt/invocation consumption

The smallest viable move is:

1. add a built-in resource-backed discovery source for psi-owned skills
2. materialize those resources to stable local paths
3. feed the resulting skill maps through the existing skill discovery/registration path
4. keep AI consumption file-based

This avoids:

- forcing skills through extension manifests and lifecycle callbacks
- inventing a new AI-only resource read protocol
- rewriting prompt assembly around hidden prompt injection
- creating a second semantic model for built-in versus external skills

## Packaging option comparison

| Option | Summary | Advantages | Costs / risks | Verdict |
|---|---|---|---|---|
| **A — built-in loading analogous to workflows** | Treat skills as another built-in asset loaded automatically from a canonical location | familiar “built-in asset” story; matches product-shipped capability concept | workflows are executable definitions, while skills are readable artifact bundles; analogy helps but is not exact; risks over-borrowing workflow-specific machinery | **Close, but too vague by itself** |
| **B — extension/manifest-backed packaging** | Ship skills through extension installation/activation machinery | reuses an existing packaging surface; could centralize packaging concepts | mismatches skill semantics; adds activation/lifecycle/ownership complexity that skills do not need; makes built-in core skills feel optional/configured rather than shipped | **Reject** |
| **C — dedicated minimal built-in skill path** | Add a narrow built-in skill packaging/discovery path and register resulting skills through existing runtime skill authority | smallest move; preserves skill semantics; avoids extension complexity; aligns with current file-backed prompt/invocation model | requires one new resource-discovery/materialization seam | **Choose** |
| **D — single internal built-ins extension** | Create one internal extension that registers built-in skills/tools/etc. | consolidates some built-in asset bootstrapping | still drags skills through extension-shaped ownership/lifecycle story; blurs concept boundaries; harder docs | **Reject** |

## AI access option comparison

| Access | Summary | Advantages | Costs / risks | Verdict |
|---|---|---|---|---|
| **1 — materialize to readable disk** | Ship resources, extract/project them to stable local files, keep ordinary `read` | preserves current AI contract; preserves relative references; minimal prompt/invocation churn; easiest to explain | requires cache/materialization policy and freshness rules | **Choose** |
| **2 — runtime resource-read surface** | Keep skills inside resources and expose a dedicated runtime read API | avoids disk materialization | introduces a second read model; relative references become custom; prompt/docs/tooling all get more complex | **Reject** |
| **3 — direct prompt injection** | load skill content straight into prompt assembly | no read step for built-ins | breaks current progressive-disclosure model; hides artifact boundary; makes `/skill:name` and relative references awkward; larger semantic change | **Reject** |

## Chosen design

### 1. What counts as a psi-owned skill

A psi-owned skill is a skill artifact shipped as part of psi itself, authored and maintained in the psi repository, packaged into the product distribution, and automatically available as a built-in skill source.

It is still a normal skill:

- authored as `SKILL.md`
- parsed into the same canonical runtime skill map shape
- registered into the same skill-registry/root-registry-backed authority
- exposed in the same capability/introspection surfaces as other skills

Psi-owned is about provenance and packaging, not a different runtime concept.

### 2. Canonical repository layout

Psi-owned built-in skills should live under:

- `resources/psi/skills/<skill-name>/SKILL.md`
- plus any skill-local support files under the same directory

Example:

- `resources/psi/skills/clojure-coding-standards/SKILL.md`
- `resources/psi/skills/clojure-coding-standards/examples.edn`
- `resources/psi/skills/refactoring/SKILL.md`

Why this layout:

- it packages naturally into jars/resources
- it preserves the existing “skill directory with `SKILL.md` plus relative siblings” model
- it clearly distinguishes psi-owned packaged skills from project-local `.psi/skills` and user-global `~/.psi/agent/skills`

### 3. Canonical file layout

Each psi-owned skill directory should follow the same authored layout as current directory-based skills:

- required: `SKILL.md`
- optional: any relative support files referenced by `SKILL.md`

The canonical entrypoint remains `SKILL.md`.

### 4. Startup discovery/loading rules

At startup, psi should discover skills from four source classes:

1. psi-owned built-in packaged skills
2. user-global skills: `~/.psi/agent/skills`
3. project skills: `.psi/skills`
4. additional explicit paths via `:extra-paths`

For source 1:

- runtime enumerates packaged skill resources under `resources/psi/skills/`
- runtime materializes them into a managed local cache directory
- runtime parses those materialized directories with the same loader shape used for filesystem skills
- resulting skill definitions are registered through the canonical skill-registry/root-storage path like any other discovered skill

The design intentionally keeps a single runtime skill shape after discovery.

#### Deterministic precedence algorithm

Current `master` `discover-skills` behavior is source-ordered and effectively first-discovered-wins:

1. user-global
2. project
3. `:extra-paths`

With built-in skills added, the design must make collision resolution explicit rather than leaving precedence as an accidental byproduct of traversal order.

The required semantic precedence is:

1. `:extra-paths` — highest precedence, because they are explicit caller-supplied overrides
2. project skills
3. user-global skills
4. psi-owned built-in packaged skills — baseline fallback

Implementation may realize that either by loading lowest-precedence sources first and allowing later higher-precedence replacement, or by keeping any discovery order internally and selecting the winner with an explicit precedence comparison before registration. The design does **not** require one specific mechanism, but it does require the final visible skill set to be equivalent to that precedence order.

That means:

- built-in skills seed the baseline set
- user-global skills can override built-in skills with the same `:name`
- project skills can override user-global or built-in skills with the same `:name`
- `:extra-paths` skills can override any other source with the same `:name`

Within a single source class, collisions should remain deterministic and simple:

- if multiple candidates with the same name arise from the same source class, the implementation should keep the first encountered candidate within that class and emit a same-precedence collision diagnostic
- this task does not require inventing finer-grained precedence among multiple project directories or multiple `:extra-paths`; if needed, implementation may preserve existing per-sequence traversal order within that source class as the tie-breaker

This resolves the ambiguity between current first-discovered-wins mechanics and the intended user model: overall source precedence is explicit, while within-source tie-breaking may remain local traversal order unless a later task chooses to refine it further.

### 5. Enablement/default-availability rules

Psi-owned packaged skills are:

- always shipped with psi
- enabled by default
- available without per-skill manual activation

This matches the meaning of “psi-owned core capability”.

If psi later needs a feature flag for subsets of built-in skills, that should be an additive future concern, not the baseline model.

### 6. Runtime capability/introspection model

Psi-owned skills should appear as ordinary skills in runtime capability surfaces, with explicit provenance.

High-level expectations:

- built-in skills are listed alongside other available skills
- their source/provenance is visible as built-in/psi-owned rather than project/user
- canonical ordering remains exact skill-name ordering, consistent with current skill-registry behavior
- session membership still controls whether a session has that skill available
- definition authority still comes from root-registry-backed skill definitions

Concretely, the implementation follow-up should preserve the current general skill shape and add a stable provenance/source marker suitable for introspection and docs.

### 7. Coexistence and precedence rules

Psi should support psi-owned skills coexisting with user/project skills.

Recommended source precedence for name collisions:

1. `:extra-paths` skill wins over every discovered baseline source
2. project skill wins over user-global and psi-owned built-in
3. user-global skill wins over psi-owned built-in
4. psi-owned built-in is the fallback baseline

In other words: **nearest or most explicit user-controlled definition wins**.

Rationale:

- it preserves user/project override power
- it lets psi ship defaults without blocking local customization
- it treats `:extra-paths` as the strongest explicit caller override
- it matches common expectation that product defaults are overrideable by closer or more deliberate scopes

Collision handling should still be explicit in diagnostics:

- when a higher-precedence skill overrides a lower-precedence skill with the same name, diagnostics should identify the winner, the shadowed candidate, and both sources
- when two skills collide within the same precedence class, diagnostics should identify that same-precedence collision and which candidate was retained by local traversal order
- the visible runtime skill should be the winning one only

### 8. AI consumption model

The AI consumption model remains ordinary file `read`.

That means built-in packaged skills must be presented to the runtime as readable files with stable absolute paths.

The runtime should materialize built-in skill resources to a managed cache directory, for example under a psi-owned cache area. The exact cache root can be chosen in implementation, but the design requires:

- stable absolute paths for the lifetime of the runtime
- directory layout preserving skill-relative files
- safe refresh when packaged skill contents change across psi versions
- no requirement that the user run inside the source tree

### 9. Relative references from `SKILL.md`

Relative references continue to resolve against the materialized skill directory.

This is a major reason to prefer Access 1.

The model-facing instruction can stay conceptually the same:

- resolve relative paths against the skill directory
- use absolute paths in tool calls

No special resource URI syntax should be exposed to the model.

### 10. Why not extension-backed packaging

Extension-backed packaging is heavier than the problem:

- skills do not need init hooks
- skills do not need extension event permissions
- skills do not need manifest activation to feel “installed”
- shipping core skills as extensions creates a more confusing user story than shipping built-in skills directly

Use the extension system for code-bearing runtime modules, not for simple built-in skill artifacts.

### 11. Why not direct prompt injection

Direct prompt injection would rewrite the skill contract too much:

- skills would stop being readable artifacts
- progressive disclosure would be weakened or lost
- `/skill:name` would no longer naturally map to “read this artifact now”
- relative support files would need a second new mechanism

That is a broader product change than this task should introduce.

## Documentation impact

At minimum, the implementation follow-up must update:

- `README.md`
  - explain that psi ships built-in skills
  - explain the precedence model between built-in, user, and project skills
- `doc/skills.md` if added, or the current skill-loading documentation surface
  - canonical source order
  - built-in skill packaging location
  - override/collision rules
  - how built-in skills still appear as readable files to the AI
- `doc/extensions.md`
  - clarify that psi-owned built-in skills are **not** extension-manifest-activated capabilities
  - point readers to the skill-loading documentation when appropriate
- `doc/workflows.md`
  - only if it currently implies a broader built-in asset story that would now include skills by analogy
- any user-facing runtime/introspection docs showing skill provenance/source semantics

## Follow-up implementation acceptance criteria

The implementation task derived from this design should satisfy at least the following:

1. Psi-owned skills live under a canonical packaged resource path.
2. The build includes those skill resources in distributable artifacts.
3. Startup discovers psi-owned packaged skills without requiring a source checkout.
4. Runtime materializes psi-owned packaged skill directories to readable filesystem paths.
5. Built-in skill maps preserve ordinary `:file-path` and `:base-dir` semantics.
6. Relative references from built-in `SKILL.md` files work the same way as existing filesystem-backed skills.
7. Built-in skill definitions register through the canonical skill-registry/root-storage path rather than a parallel ad hoc store.
8. Runtime capability/introspection surfaces expose built-in skills as ordinary skills with stable provenance/source metadata.
9. Collision precedence is deterministic and documented: `:extra-paths` > project > user-global > built-in.
10. Collisions emit diagnostics naming the winner and the shadowed source, with same-precedence ties also reported deterministically.
11. Existing `/skill:name` invocation continues to work for built-in skills.
12. Prompt-visible skill lists preserve current canonical skill-name ordering.
13. Docs are updated to explain source order, packaging, overrides, and AI readability.
14. Non-source-tree execution is covered by tests proving built-in skills remain discoverable and readable.

## Acceptance criteria for this design task

- The design remains design-only and does not require code changes.
- A follow-up implementer can act on the design without re-deriving the decision space.
- The design explicitly compares Options A, B, C, and D.
- The design explicitly compares Access 1, Access 2, and Access 3.
- The design chooses one preferred packaging/activation option and records concise reasons for rejecting the others.
- The design chooses one preferred AI-access option and records concise reasons for rejecting the others.
- The design defines the canonical location and file layout for psi-owned skills.
- The design defines discovery, enablement, precedence, and AI-consumption semantics.
- The design states how psi-owned skills should appear in runtime capability/introspection surfaces at a high level.
- The design reflects current `master` realities: root-registry-backed canonical skill definitions, session membership by skill id, and still-file-backed AI skill consumption.
- The design identifies the user-facing docs that will need updating, at minimum covering skill loading and the extension/workflow touchpoints that could otherwise imply the wrong model.
