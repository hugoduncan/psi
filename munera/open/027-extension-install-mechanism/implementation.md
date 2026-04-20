2026-04-19
- Task created from user request to add an extension install mechanism.
- Initial design bias captured:
  - simplicity first
  - pay only for what you use
  - support user + project scope
  - likely first slice: manifest-driven installs with `:local-fs` and `:github`
  - defer `:clojars` and marketplace until need is proven

2026-04-19
- Design direction updated after discussion of extensions as ordinary libraries.
- New current bias:
  - hybrid model: psi-specific install manifests + tools.deps loading substrate
  - extensions can bring their own dependencies
  - local installs should use tools.deps local dependency coordinates
  - git/GitHub installs should use git dependency coordinates
  - mvn/Clojars support is now a plausible early follow-on or low-cost inclusion if coherent
  - prefer startup/reload-based activation first over dynamic in-process dependency mutation

2026-04-19
- Design direction refined again.
- New current bias:
  - make the psi extension manifest deps.edn-shaped
  - use normal dependency coordinate keys directly in extension records
  - put psi metadata alongside them under `:psi/*` keys
  - prefer a single extension-centric map over splitting raw `:deps` and extension metadata across separate maps

2026-04-19
- Design direction simplified further.
- New current bias:
  - use a raw top-level `:deps` map rather than a custom `:extensions` map
  - use `:psi/init` as the required extension marker and activation contract
  - use optional `:psi/enabled` as the activation toggle
  - treat any dep entry with `:psi/init` as a psi extension
  - keep the manifest as close as practical to ordinary `deps.edn`

2026-04-19
- Design direction narrowed for slice one.
- New current bias:
  - direct manifest editing is the slice-one install/update/remove surface
  - dedicated command UX is not required initially
  - reload/apply plus validation/introspection are the key operational surfaces

2026-04-19
- Reload/apply stance sharpened.
- New current bias:
  - restart is the canonical guaranteed apply path
  - explicit reload/apply is optional and only desirable if it can be implemented conservatively
  - slice one should not promise arbitrary live in-process dependency graph mutation

2026-04-19
- Duplicate-init policy sharpened.
- New current bias:
  - duplicate effective `:psi/init` claims across distinct library keys are hard configuration errors
  - duplicate-init validation should run before activation begins
  - no claimant should activate while the conflict exists

2026-04-19
- Git dependency policy sharpened.
- New current bias:
  - git extension deps should follow standard pinned deps.edn expectations
  - require `:git/sha` in both user and project manifests for git extension deps
  - avoid psi-specific divergence between user and project git coordinate rules

2026-04-19
- Standard deps-form support clarified.
- New current bias:
  - local, git, and mvn dependency forms are part of the base model
  - mvn/Clojars should not be treated as a separate conceptual follow-on unless implementation reality forces a temporary restriction

2026-04-19
- Diagnostics/introspection surface clarified.
- New current bias:
  - structured extension validation, effective config, and load status should live on the existing canonical read/introspection surface
  - logs and human-readable summaries are secondary aids, not the primary diagnostic surface

2026-04-19
- Reload/apply importance updated.
- New current bias:
  - explicit reload/apply should be included in slice one because it matters for extension development
  - restart remains the guaranteed fallback apply path
  - reload/apply should remain conservative about runtime mutation guarantees and may degrade to restart-oriented apply when necessary
