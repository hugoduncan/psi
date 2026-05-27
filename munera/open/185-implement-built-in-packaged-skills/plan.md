# Plan

## Approach

Implement the task in small vertical slices that preserve current skill semantics while adding built-in packaged skills.

1. Add the packaged skill asset layout and ensure build/distribution includes it.
2. Add runtime resource discovery and materialization to readable disk paths.
3. Route materialized built-in skills through the existing skill parsing/discovery flow.
4. Add explicit precedence-aware collision selection and diagnostics across all sources.
5. Ensure canonical registration/provenance/introspection behavior stays coherent.
6. Update docs and add verification, including non-source-tree proof.

## Slice decisions

### Slice 1 — packaged resource baseline
- Introduce canonical built-in skill resource path.
- Add at least one representative built-in skill fixture/proof asset.
- Prove the build includes the resource path in packaged artifacts.

### Slice 2 — materialization seam
- Add a minimal runtime seam that can enumerate built-in skill resources and materialize them to a managed readable cache path.
- Make the authoritative cache root a psi-owned user-global path under `~/.psi/agent/`, with a dedicated built-in-skills subtree rather than source-tree or temp-dir output.
- Use deterministic version/content-addressed snapshot directories, reusing an existing snapshot when the packaged build/resources match and creating a new snapshot when they differ.
- Preserve stable absolute skill paths within one runtime by binding each loaded built-in skill to the selected snapshot path for that run.
- Preserve directory-relative support files.
- Keep the output compatible with existing parser expectations.

### Slice 3 — discovery integration
- Integrate built-in materialized skill directories into startup/discovery.
- Preserve current runtime skill map shape.
- Ensure resulting skills flow into canonical skill-registry/root-storage authority.

### Slice 4 — precedence and diagnostics
- Replace accidental source-order-only collision behavior with explicit precedence-aware selection over ordinary skill `:source` classes: `:path` > `:project` > `:user` > `:built-in`.
- Decouple final winner selection from raw discovery/bootstrap traversal order so implementation can load sources in any convenient sequence while preserving the canonical precedence contract.
- Keep `:disabled true` / `--no-skills` behavior coherent by suppressing built-in, user-global, and project sources together while still loading `:extra-paths`.
- Use deterministic same-source tie handling: for equal skill names inside one source class, earlier configured source-container order wins; if multiple candidates arise within the same container class, lexicographically earlier canonical absolute `SKILL.md` path wins.
- Add diagnostics that identify both winner and shadowed definitions.

### Slice 5 — introspection and docs
- Expose built-in provenance/source through existing skill read-models by representing built-ins as ordinary skills with `:source :built-in`.
- Keep `skill-summary`, detail/enriched skill maps, `skills-by-source`, EQL/grouped projections, and collision diagnostics aligned around that provenance surface.
- Update docs for shipping, overrides, same-source tie handling, and readable materialized paths.

### Slice 6 — verification
- Add focused unit/integration tests for resource discovery, materialization, precedence, diagnostics, and `/skill:name` behavior.
- Add non-source-tree verification proving built-in skills remain available from packaged execution.

## Risks

- Overcomplicating resource discovery when a small materialization seam is sufficient.
- Accidentally splitting skill authority between resource metadata and canonical registered skill definitions.
- Regressing existing source precedence/collision behavior for project/user/extra-path skills.
- Making docs imply built-in skills are extensions or hidden prompt-only state.

## Out of scope

- Large-scale migration of all repo skills in the first slice unless necessary.
- A new generic asset system.
- A runtime-only non-file read surface for skills.
