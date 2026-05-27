# 185 implement built-in packaged skills

## Intent

Implement the recommended design from task `184-package-skills-inside-psi-easiest-path` so psi can ship psi-owned skills as built-in packaged resources while preserving the current AI-facing readable-artifact model.

This task is implementation work. The design decisions are already made in task `184`; this task should implement them with minimal additional invention.

## Context

Task `184` chose:

- **Packaging/activation**: Option C — dedicated minimal built-in skill path
- **AI access**: Access 1 — materialize packaged skills to readable disk

Task `184` also fixed the intended precedence contract:

1. `:extra-paths`
2. project skills
3. user-global skills
4. psi-owned built-in packaged skills

And it fixed the implementation direction:

- psi-owned built-in skills are packaged as resources under `resources/psi/skills/<skill-name>/...`
- runtime materializes them to a psi-owned user-global cache directory under `~/.psi/agent/`, using deterministic version/content-addressed snapshots that are reused when unchanged and refreshed into a new snapshot when packaged build/resource contents change
- materialized skill directories are parsed into the same runtime skill shape with ordinary `:file-path` and `:base-dir`
- canonical runtime definition authority remains `skill-registry` / root-registry-backed storage
- built-in skills remain ordinary skills with stable provenance metadata in capability/introspection surfaces

## Scope

In scope:

- add the canonical resource layout for psi-owned built-in skills
- package psi-owned built-in skill resources into distributable artifacts
- add runtime discovery of built-in packaged skills
- add resource materialization to readable filesystem paths
- preserve ordinary skill parsing/invocation semantics using materialized `:file-path` and `:base-dir`
- implement explicit precedence-aware collision resolution across built-in, user-global, project, and `:extra-paths`
- emit collision diagnostics naming the winner and shadowed candidates
- register resulting skills through the canonical skill-registry/root-storage path
- expose built-in skill provenance in capability/introspection surfaces as needed
- update user-facing docs for built-in skill shipping and precedence
- add focused and end-to-end verification, including non-source-tree proof

Out of scope:

- redesigning skill semantics
- migrating every existing project skill unless needed to prove the mechanism
- changing extension lifecycle semantics
- changing workflow semantics beyond any necessary docs touchpoint
- inventing a non-file AI access model

## Acceptance criteria

1. Psi-owned built-in skills live under a canonical packaged resource path: `resources/psi/skills/<skill-name>/...`.
2. The build includes those resources in distributable artifacts used for non-source-tree execution.
3. Startup discovers psi-owned built-in packaged skills without requiring a source checkout.
4. Runtime materializes built-in packaged skill directories to stable readable filesystem paths under a psi-owned `~/.psi/agent/` cache subtree.
4a. Materialization uses deterministic version/content-addressed snapshots: reuse existing snapshots when the packaged build/resources match, and refresh by selecting a new snapshot path when they differ.
5. Materialized built-in skills preserve ordinary `:file-path` and `:base-dir` semantics.
6. Relative references from built-in `SKILL.md` files resolve the same way as for existing filesystem-backed skills.
7. Resulting skill definitions register through canonical skill-registry/root-storage authority rather than a parallel ad hoc store.
8. Collision resolution is explicit and deterministic: `:extra-paths` > project > user-global > built-in.
9. Within the same source class, tie handling is deterministic and documented.
10. Collision diagnostics identify both the winning and shadowed skill definitions and their sources.
11. `/skill:name` continues to work for built-in skills.
12. Prompt-visible skill lists preserve canonical exact skill-name ordering.
13. Capability/introspection surfaces can distinguish built-in skill provenance at a high level.
14. Docs explain built-in skill shipping, source precedence, overrides, and AI-readable materialized paths.
15. Verification includes non-source-tree proof that built-in packaged skills remain discoverable and readable.

## Constraints

- Follow task `184` rather than reopening the design space.
- Prefer the smallest implementation that fits the current architecture.
- Reuse existing skill parsing/loading behavior wherever possible.
- Preserve the ordinary file-based AI skill consumption model.
- Keep skill definition authority unified through the existing root-registry-backed path.
