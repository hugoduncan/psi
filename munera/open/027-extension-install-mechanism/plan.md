Approach:
- Implement extension installation as psi-owned manifest reading plus tools.deps-backed dependency realization.
- Keep the user model close to ordinary `deps.edn`:
  - top-level `:deps`
  - extension dep identified by `:psi/init`
  - optional `:psi/enabled`
- Support standard dependency forms directly in slice one:
  - `:local/root`
  - git deps with required `:git/sha`
  - `:mvn/version`
- Treat direct manifest editing as the install/update/remove mechanism.
- Make explicit reload/apply available in slice one for extension development, with restart as the guaranteed fallback apply path.
- Surface validation, effective config, load status, and last-apply result on the existing canonical read/introspection surface.

Implementation slices:
1. manifest schema + validation
   - add/confirm schemas for user/project `extensions.edn`
   - validate:
     - top-level `:deps`
     - extension dep requires `:psi/init`
     - optional `:psi/enabled`
     - exactly one dependency coordinate family per extension dep
     - git extension deps require `:git/sha`
     - duplicate effective `:psi/init` claims are hard errors
   - classify diagnostics with stable categories and severities

2. manifest loading + effective resolution
   - read user manifest from `~/.psi/agent/extensions.edn` when present
   - read project manifest from `.psi/extensions.edn` when present
   - merge by library symbol with project-over-user precedence
   - preserve provenance for introspection
   - derive:
     - effective raw deps
     - effective filtered extension deps
     - normalized per-library entry view

3. diagnostics model
   - implement structured diagnostics for:
     - malformed entry
     - duplicate init
     - missing git sha
     - project local root non-reproducible
     - load failure
     - restart required
   - ensure each diagnostic includes severity, category, message, source/provenance, and affected lib/init context when applicable

4. activation + load state model
   - activate only effective deps with `:psi/init`
   - resolve and call enabled init vars from the realized state
   - validate/register returned extension values
   - record per-extension status:
     - `:not-loaded`
     - `:loaded`
     - `:failed`
     - `:not-applicable` for support deps in normalized entry projection if useful
   - ensure validation failures block activation before any conflicting claimant activates

5. explicit reload/apply operation
   - implement explicit reload/apply in slice one
   - operation always:
     - rereads manifests
     - validates
     - recomputes effective state
     - publishes diagnostics
   - operation returns narrow result semantics:
     - `:applied`
     - `:restart-required`
   - if in-process realization is safe, activate new state and report `:applied`
   - if not safe, keep current state unchanged, publish diagnostics/state, and report `:restart-required`
   - restart remains the guaranteed apply path for all valid configs

6. canonical read/introspection projection
   - expose one coherent extension projection on the existing canonical read surface containing:
     - raw user manifest
     - raw project manifest
     - effective merged raw deps
     - effective filtered extension deps
     - normalized entries by lib
     - diagnostics
     - last-apply result
   - implement the proposed projection shape from design.md, then settle final attr names and joins to fit existing graph conventions

7. documentation + workflows
   - document manifest locations and schema
   - document examples for local, git, and mvn extension deps
   - document duplicate-init and missing-git-sha failures
   - document developer workflow:
     - edit manifest
     - reload/apply
     - inspect structured state/diagnostics
     - restart when required

Concrete projection targets:
- raw manifests
- effective raw deps
- effective extension deps
- normalized per-library entry maps
- diagnostic vector
- last-apply map with status `:applied | :restart-required`

Constraints carried into implementation:
- no marketplace
- no custom package manager
- no file watching
- no requirement for arbitrary in-process dependency graph mutation
- no dedicated install/list/remove command UX in slice one

Risks:
- reload/apply may expose runtime limitations around in-process dependency realization
- extension loading remains in-process code execution and is not a security boundary
- projection naming must align cleanly with existing read-surface conventions without creating an awkward parallel model
