Goal: add a simple extension install mechanism so extensions can be published and then installed by users or projects instead of requiring direct namespace links under `.psi/extensions`.

Context:
- today, extension activation is effectively local/manual: link a namespace in `.psi/extensions`
- that works for local development but is not a distribution mechanism
- we want third parties to be able to publish extensions that others can install
- installation should support at least two scopes:
  - user-level install
  - project-level install
- candidate sources discussed so far:
  - local filesystem
  - GitHub/git
  - Clojars or another Maven/package source
  - a marketplace
- an important new direction is to consider extensions as ordinary Clojure libraries so they can bring their own dependencies
- the design should prioritize simplicity and a pay-only-for-what-you-use model
- the current direction also prefers maximum closeness to ordinary `deps.edn`
- command UX is not required for the first slice; direct manifest editing is acceptable
- explicit reload/apply matters for extension development, even if restart remains the fallback correctness path

Problem statement:
- current extension adoption is too coupled to local source layout and manual configuration
- there is no canonical install/uninstall/update story for extensions
- there is no clear distinction between globally available extensions and project-pinned extensions
- extensions that need additional dependencies do not have a clear first-class installation/loading mechanism
- distribution, versioning, trust, and dependency boundaries are not yet modeled explicitly

Desired outcome:
- a canonical install model for extensions
- support for both user and project scope without forcing heavyweight infrastructure
- a design that can start simple and grow only when needed
- a model that allows extensions to carry their own library dependencies when required
- a manifest shape that is as close as practical to ordinary `deps.edn`
- a clear reload/apply story after manifest edits
- strong validation and introspection even without a dedicated install command UX
- a practical development loop for extension authors without requiring full psi restart on every edit when a conservative reload path is possible

Core constraints:
- simplicity first
- pay only for what you use
- no mandatory marketplace for the first slice
- no mandatory centralized registry for the first slice if direct sources are sufficient
- installed extensions should remain explicit and introspectable
- project-level installs should be reproducible from project files committed to git
- user-level installs should avoid leaking into project-level reproducibility semantics
- psi should avoid inventing a custom package manager if standard Clojure dependency machinery is sufficient
- git dependency policy should follow normal deps.edn expectations rather than inventing scope-specific git coordinate rules
- standard deps forms should be accepted by default unless implementation reality forces a narrower temporary restriction

Primary design direction now favored:
1. hybrid model: psi-specific install manifests + tools.deps library loading
   - psi owns install manifests, scope semantics, precedence, introspection, validation, and activation semantics
   - tools.deps owns dependency coordinates and library resolution/loading substrate
   - extensions are distributed as ordinary Clojure libraries
   - this allows extensions to depend on additional libraries without psi inventing a new dependency mechanism

2. make manifests as close as practical to ordinary `deps.edn`
   - the manifest should use a top-level `:deps` map rather than a custom top-level `:extensions` map
   - dependency coordinates should use normal deps.edn forms directly
   - the only required psi-specific metadata should be `:psi/init`
   - optional `:psi/enabled` may control activation
   - any dependency entry with `:psi/init` is a psi extension dependency

3. reuse the extension init-function contract directly
   - do not introduce a new extension entrypoint naming convention if the runtime already thinks in terms of an init function
   - `:psi/init` is the var psi resolves and calls to initialize the extension
   - this is clearer and simpler than introducing `:psi/entrypoint`, `:psi/ns`, or a new `-extension` convention in slice one

4. define two installation scopes
   - user scope
     - installed under a user-owned psi directory
     - available across projects for that user
     - good for personal tools and reusable helpers
   - project scope
     - declared under project-local `.psi/` config
     - reproducible and shareable with the repository
     - should be the preferred scope for team/project behavior

5. define extension installs in terms of standard library coordinates
   - local development/install via tools.deps local dependency coordinates
   - git/GitHub via git dependency coordinates
   - Maven/Clojars via standard mvn dependency coordinates
   - a future marketplace can remain a discovery layer that resolves to one of these coordinate forms rather than a new loading substrate

6. keep install metadata separate from runtime activation/loading
   - manifests record what extension libraries are installed and how they should be activated
   - runtime computes the effective extension set from user + project manifests
   - loading then resolves and invokes the declared `:psi/init` vars from the effective dependency basis
   - install and activation remain explicit rather than being inferred from incidental files

7. keep slice one lightweight
   - likely first slice should avoid a marketplace
   - likely first slice should avoid fully live/dynamic dependency graph mutation in a long-running runtime
   - likely first slice should avoid general extension-on-extension dependency semantics beyond what normal library transitive deps already provide
   - likely first slice should avoid a dedicated install/list/remove command UX unless later experience proves it valuable
   - likely first slice should prefer a conservative reload/apply model over arbitrary live in-process dependency mutation

Why this direction is attractive:
- local filesystem installs become normal local deps rather than a bespoke path-loader
- GitHub/git installs become normal git deps rather than psi-specific clone management
- Maven/Clojars support falls naturally out of the same manifest model rather than needing a separate concept
- extensions can bring extra dependencies naturally
- psi can keep a small explicit model while delegating dependency resolution to standard Clojure tooling
- a future marketplace can be layered on as discovery, not as mandatory infrastructure
- a raw `:deps` map makes the manifest feel like ordinary `deps.edn`
- `:psi/init` is a very small and clear extension-specific contract
- direct EDN editing keeps the first slice small and aligned with pay-only-for-what-you-use

Canonical manifest locations:
- user manifest
  - `~/.psi/agent/extensions.edn`
  - records user-installed extensions
- project manifest
  - `.psi/extensions.edn`
  - records project-installed extensions
  - commit this file for reproducibility

Recommended manifest shape:
- the manifest should be psi-owned, but structurally as close as practical to ordinary `deps.edn`
- use a top-level `:deps` map
- dependency records should use standard coordinate keys directly
- psi metadata should stay minimal and namespaced
- preferred shape:

```clojure
{:deps
 {my/local-ext
  {:local/root "/Users/me/dev/my-local-ext"
   :psi/init my.local.ext/init
   :psi/enabled true}

  io.github.me/cool-ext
  {:git/url "https://github.com/me/cool-ext"
   :git/sha "abc123def456..."
   :psi/init cool.ext/init
   :psi/enabled true}

  io.github.someone/packaged-ext
  {:mvn/version "0.2.1"
   :psi/init packaged.ext/init
   :psi/enabled false}}}
```

Canonical slice-one schema rules:
- top-level map must contain `:deps`
- `:deps` value is a map keyed by library symbols
- any dep entry with `:psi/init` is a psi extension dependency
- each extension dep entry must contain:
  - exactly one dependency coordinate family:
    - local dependency: `:local/root`
    - git dependency: standard deps.edn git coordinates, with `:git/sha` as the canonical pinned form for slice one
    - mvn dependency: `:mvn/version`
  - required psi metadata:
    - `:psi/init` symbol naming a callable init var
  - optional psi metadata:
    - `:psi/enabled` boolean, default `true`
- slice one should reject records that mix coordinate families
- slice one should reject extension dep entries missing `:psi/init`
- slice one should require `:git/sha` for git extension deps in both user and project manifests
- slice one may allow project-scope `:local/root` only as explicitly development-oriented and non-reproducible

Support for standard deps forms:
- standard deps forms should be considered part of the base model rather than optional follow-on concepts
- in particular, local, git, and mvn dependency forms are all part of the same extension manifest model
- if runtime/apply implementation cannot yet realize one of these forms cleanly, that should be treated as an implementation limitation to document explicitly, not as a separate conceptual model decision
- the design should therefore document mvn/Clojars examples in slice one unless implementation reality forces a temporary narrower support statement

Policy for ordinary non-extension deps in extension manifests:
- slice one should allow ordinary dep entries without `:psi/init` in `extensions.edn`
- activation only considers entries that contain `:psi/init`
- non-extension deps are treated as support deps for installed extensions or user/project-local convenience deps
- introspection should expose both:
  - raw dep entries in the manifest
  - the filtered effective extension set derived from dep entries with `:psi/init`
- if a non-extension dep is present only to support an extension dep, this is acceptable and should not be considered invalid configuration
- slice one may emit a mild note or diagnostic when a manifest contains only non-extension deps, but this should not be a hard error

Why this policy is preferred:
- it keeps the file maximally close to ordinary `deps.edn`
- it avoids forcing users to split supporting deps into a separate file
- it preserves a clean extension activation rule: extension = dep entry with `:psi/init`
- it avoids unnecessary schema restrictions in slice one
- it leaves room for future tooling to explain or warn about unusual manifest contents without making the base model brittle

Minimal slice-one psi metadata set:
- required:
  - `:psi/init`
- optional:
  - `:psi/enabled`
- explicitly deferred metadata:
  - `:psi/description`
  - `:psi/capabilities`
  - `:psi/compatibility`
  - `:psi/source`
  - resolved-version bookkeeping beyond what can be derived from the dependency coordinates

Why prefer `:psi/init`:
- clearer than `:psi/entrypoint`
- more explicit than `:psi/ns`
- avoids needing a hidden naming convention like `init` or `-extension`
- reuses the existing runtime concept directly
- keeps the manifest very close to ordinary `deps.edn`

Alternative considered: explicit namespace plus fixed function naming convention
- e.g. `:psi/ns` with an implicit `init` function name
- not currently recommended because `:psi/init` is simpler, more explicit, and less magical

Alternative considered: custom top-level `:extensions` map
- not currently recommended because a raw `:deps` map with minimal namespaced psi metadata is closer to normal Clojure practice and simpler to translate into an effective dependency basis

Canonical merge and precedence semantics:
1. manifests are read independently
   - load user manifest if present
   - load project manifest if present
2. extension identity is the library symbol key under `:deps`
3. effective extension set is computed by key merge with project precedence
   - project record overrides user record for the same library key
4. when a key exists only at one scope, that record is used directly
5. effective metadata should preserve provenance for introspection
   - effective scope: `:user` or `:project`
   - overridden records should remain inspectable even if inactive
6. if the same library key is declared at both scopes with incompatible coords, project still wins for activation, but introspection should surface the override condition clearly
7. if different library keys point to the same `:psi/init`, slice one should treat that as a configuration error rather than a warning
   - duplicate effective init vars are not allowed in slice one
   - this should be validated before activation begins
   - no duplicate claimant should be activated when the conflict is present
   - diagnostics should report:
     - the duplicate `:psi/init` symbol
     - the competing library keys
     - their scopes/provenance
     - whether one record overrode another by key or whether the conflict is across distinct keys
   - this is preferred over warning because extension identity and ownership become ambiguous otherwise

Why hard-error on duplicate `:psi/init`:
- the runtime contract says `:psi/init` is the activation identity surface
- two distinct libraries claiming the same init var is almost certainly a configuration mistake
- warning-and-continue would make activation order matter implicitly and would undermine determinism
- hard failure keeps the effective extension set unambiguous and easier to debug

Canonical reproducibility rules:
- project manifest is the reproducibility surface
- git extension deps should follow normal deps.edn pinned expectations in both scopes, using `:git/sha`
- mvn extension deps follow normal versioned deps.edn practice
- project-scope local installs are allowed only as development-oriented/non-reproducible and should be surfaced as such in introspection and diagnostics
- user manifest is also expected to use `:git/sha` for git extension deps, to stay aligned with ordinary deps.edn practice and avoid psi-specific divergence

Canonical reload/apply model:
- slice one should provide an explicit reload/apply path because it materially improves extension development workflow
- slice one should still remain restart-safe: restarting psi must always pick up the current manifests and realize the same effective extension state
- editing manifests never implies immediate live mutation of the running dependency graph
- instead, manifest edits stage a desired configuration; explicit reload/apply or restart realizes it

Narrowed slice-one reload/apply semantics:
1. explicit reload/apply always does these steps
   - reread user and project manifests
   - validate manifest structure
   - compute effective raw deps and effective extension deps
   - validate duplicate `:psi/init` absence and git-sha requirements
   - produce structured diagnostics on the canonical read surface
2. explicit reload/apply has two allowed outcomes
   - `:applied`
     - the runtime successfully realized the needed code/dependency state and activated the new extension set without requiring full process restart
   - `:restart-required`
     - the desired configuration is valid, but the runtime cannot safely realize the dependency/code changes in-process
     - diagnostics remain available and should indicate that restart is required to finish applying the new state
3. explicit reload/apply must not have a silent partial-success mode
   - if the desired configuration is invalid, outcome is effectively blocked/failed and current active extension state remains unchanged
   - if restart is required, the system should say so explicitly rather than pretending the new extension set is live
4. explicit reload/apply should favor dev ergonomics for local extension iteration
   - when only extension code/init can be safely reloaded without a dependency-basis change, in-process apply may succeed
   - when dependency-basis change is involved and not safely realizable, outcome should be `:restart-required`
5. restart remains the canonical guaranteed apply path for all valid configs

Recommended slice-one behavior:
1. baseline guarantee
   - restart is always a valid apply path
   - restart remains the fallback correctness path even if explicit reload exists
2. required developer-facing convenience in slice one
   - provide an explicit reload/apply operation that:
     - performs the deterministic validation/projection steps above
     - attempts conservative in-process apply when safe
     - otherwise returns `:restart-required`
3. failure semantics
   - invalid manifests do not silently partially activate new extension state
   - reload/apply should surface errors clearly and leave current active extension state unchanged if the new config cannot be realized coherently
4. safety bias
   - avoid promising arbitrary in-process dependency graph mutation if the runtime cannot do that robustly
   - explicit reload remains important even if its implementation sometimes resolves to a restart-oriented apply path

Recommended slice-one decision:
- define restart as the canonical guaranteed apply path
- include an explicit reload/apply operation in slice one because it is important for extension development
- constrain explicit reload/apply outcomes to a small clear set centered on `:applied` and `:restart-required`
- do not require file watching in slice one
- do not require hot-swapping arbitrary library graphs in-process in slice one

Why this reload/apply model is preferred:
- it matches the real complexity boundary: dependency-basis changes are harder than manifest parsing
- it still acknowledges real developer workflow needs for extension iteration
- it keeps correctness and clarity ahead of convenience while still providing a first-class apply action
- it avoids overpromising on classloader/runtime behavior
- it preserves a simple user story: edit manifest, then reload/apply or restart
- it fits pay-only-for-what-you-use by making richer live reload a later optimization rather than a prerequisite

Canonical activation semantics:
- manifest editing is the install/update/remove surface in slice one
- activation is a separate phase
- slice one default behavior:
  - user edits `extensions.edn`
  - psi validates and reports manifest issues clearly
  - extension availability changes take effect on explicit apply via reload/apply or restart
- the explicit reload/apply operation is part of slice one, but should remain conservative about runtime mutation guarantees
- slice one should avoid promising fully dynamic in-process dependency graph mutation

Canonical extension loading model:
1. read user and project extension manifests
2. validate manifest structure and record diagnostics
3. compute the effective extension install set using the precedence rules above
4. validate duplicate `:psi/init` absence across the effective extension set
5. derive an effective deps map directly from the dep entries
6. realize that dependency basis through the existing Clojure/tools.deps substrate when safe/applicable
7. for each enabled effective extension dep in the realized state, resolve and call its `:psi/init` var
8. validate and register the returned extension definition/value
9. record load success/failure and provenance for introspection

Canonical diagnostics and introspection surface:
- slice one should prefer exposing validation/load/introspection through the runtime’s existing canonical read surface rather than inventing a new bespoke operator UX
- the primary home should be the existing introspection/query surface
- diagnostics should be queryable as structured data, not only emitted as log text
- logs may mirror key failures, but logs are secondary to structured introspection

Proposed canonical read/introspection projection shape:
- top-level extension config/read surface should expose one coherent map with these conceptual fields:
  - raw manifests
  - effective state
  - diagnostics
  - apply status
- suggested shape:

```clojure
{:psi.extensions/user-manifest
 {:deps {...}}
 :psi.extensions/project-manifest
 {:deps {...}}
 :psi.extensions/effective
 {:raw-deps {...}
  :extension-deps {...}
  :entries-by-lib
  {my/local-ext
   {:dep {...}
    :extension? true
    :enabled? true
    :scope :user
    :overridden? false
    :status :loaded}}
  :active? true}
 :psi.extensions/diagnostics
 [{:severity :error
   :category :duplicate-init
   :message "Duplicate :psi/init claim for my.ext/init"
   :libs [foo/ext bar/ext]
   :scopes [:user :project]}]
 :psi.extensions/last-apply
 {:status :applied
  :at "2026-04-19T21:00:00Z"
  :restart-required? false}}
```

Notes on the projection:
1. raw manifests
   - preserve close fidelity to file contents for debugging
   - user and project manifests should be separately visible
2. effective view
   - expose both effective raw deps and filtered extension deps
   - also provide a per-library normalized entry view so adapters/queries do not have to reconstruct provenance/status repeatedly
3. diagnostics
   - a flat vector of structured diagnostic maps is preferred for simple filtering/rendering
4. last-apply
   - should reflect the most recent explicit reload/apply attempt
   - restart-only activation may leave this absent or mark a startup-derived state separately if useful later

Proposed normalized per-library entry shape:
```clojure
{:dep {:git/url "..." :git/sha "..." :psi/init foo/init}
 :extension? true
 :support-dep? false
 :enabled? true
 :scope :project
 :source-manifests [:project]
 :overridden? false
 :effective? true
 :status :loaded
 :load-error nil
 :init-var 'foo/init}
```

For a support dep:
```clojure
{:dep {:mvn/version "1.2.3"}
 :extension? false
 :support-dep? true
 :enabled? false
 :scope :project
 :source-manifests [:project]
 :overridden? false
 :effective? true
 :status :not-applicable}
```

Proposed diagnostic shape:
```clojure
{:severity :error | :warning | :info
 :category keyword
 :message string
 :libs [lib*]
 :init-var symbol-or-nil
 :scopes [scope*]
 :source :user-manifest | :project-manifest | :effective
 :data {...}}
```

Recommended diagnostic categories for slice one:
- `:malformed-entry`
- `:duplicate-init`
- `:missing-git-sha`
- `:project-local-root-nonreproducible`
- `:load-failure`
- `:restart-required`

Proposed apply-result projection:
```clojure
{:status :applied | :restart-required
 :restart-required? boolean
 :summary string
 :diagnostic-count integer
 :at instant-string}
```

Recommended surface strategy for slice one:
- primary: introspection/query surface returns structured extension config + diagnostics + load status + last-apply result
- secondary: startup/restart/apply should also surface a concise human-readable summary when validation or activation fails
- tertiary: documentation should show users how to inspect effective extension state after editing manifests
- the explicit reload/apply operation should report the same structured diagnostics rather than inventing a separate result model

Why this surface strategy is preferred:
- aligns with the rest of psi’s introspectable-runtime design
- avoids creating a one-off management UX just for extension config
- makes failures machine-readable and adapter-agnostic
- supports TUI, Emacs, RPC, and debugging flows from one canonical data source

Required slice-one surfaces:
1. manifest/documentation surface
   - clear file locations and schema docs
   - examples for local, git, and mvn deps
2. validation/introspection surface
   - structured diagnostics and effective-state queries as described above
   - clear hard errors for malformed entries, duplicate `:psi/init`, and missing `:git/sha`
3. reload/apply surface
   - restart is always supported as the canonical apply path
   - explicit reload/apply is included in slice one for development workflow support
   - reload/apply remains conservative about runtime mutation guarantees
   - reload/apply reports whether the result was applied in-process or requires restart
   - no need for full install/list/remove command UX in slice one

Suggested minimal operator workflow:
1. edit `~/.psi/agent/extensions.edn` or `.psi/extensions.edn`
2. run explicit reload/apply, or restart psi
3. inspect structured diagnostics/effective state through the canonical introspection surface
4. use logs only as a supplemental failure summary

Example workflows:
1. add local user extension by editing user manifest
   - add dep entry with `:local/root` and `:psi/init`
   - run explicit reload/apply or restart psi
   - inspect effective extension state
2. add pinned project git extension by editing project manifest
   - add dep entry with `:git/url`, `:git/sha`, and `:psi/init`
   - commit `.psi/extensions.edn`
   - teammates get reproducible extension config on pull
   - run explicit reload/apply or restart psi
3. add mvn/Clojars extension by editing either manifest
   - add dep entry with `:mvn/version` and `:psi/init`
   - run explicit reload/apply or restart psi
   - inspect effective extension state
4. disable extension by editing `:psi/enabled false`
   - run explicit reload/apply or restart psi
   - inspect effective extension state
5. add a support dep to the extension manifest
   - dep entry is allowed even without `:psi/init`
   - it participates in dependency realization
   - it does not participate in extension activation
6. conflicting duplicate init var
   - two distinct library keys declare the same `:psi/init`
   - validation fails before activation
   - no claimant is activated until the conflict is removed
7. git dep without sha
   - git extension dep omits `:git/sha`
   - validation fails before activation
   - user updates the manifest to the standard pinned git form
8. dev reload with restart-required outcome
   - manifest change is valid but requires dependency/code state that cannot be safely realized in-process
   - explicit reload/apply reports `:restart-required`
   - user restarts psi to complete activation

Key remaining design choices:
1. exact canonical attr names and joins
   - final EQL/pathom-facing attribute names for the proposed projection

Current recommendation:
- adopt the hybrid model: psi-specific install manifests backed by deps.edn-shaped tools.deps library coordinates
- use a raw top-level `:deps` map
- use `:psi/init` as the required extension marker/activation contract
- use `:psi/enabled` as the optional activation toggle
- allow ordinary non-extension deps in `extensions.edn`, but activate only entries with `:psi/init`
- require `:git/sha` for git extension deps in both user and project manifests
- treat local, git, and mvn dependency forms as part of the base model
- hard-error on duplicate effective `:psi/init` claims across distinct library keys
- use direct manifest editing as the slice-one install/update/remove mechanism
- make restart the canonical guaranteed apply path
- also include explicit reload/apply in slice one because it matters for extension development
- narrow explicit reload/apply results to a small clear set centered on `:applied` and `:restart-required`
- make validation/introspection on the existing canonical read surface the key operational surface alongside apply
- use the canonical manifest locations above
- use project-over-user precedence on the same library key
- make the first slice support at least:
  - local dependency installs via manifest editing
  - git/GitHub installs via manifest editing using pinned `:git/sha`
  - mvn/Clojars installs via manifest editing using standard `:mvn/version`
  - explicit reload/apply plus restart-based fallback semantics
  - structured validation/introspection
- do not build a marketplace yet
- do not build a custom fetch/materialization pipeline if tools.deps coordinates are sufficient
- keep activation separate from install metadata
- treat command UX and richer live reload as possible later convenience layers, not prerequisites

Alternative approaches still worth considering:
1. source-fetch/materialization-first approach
   - install from local paths or repos into psi-managed stores/caches and load from there
   - pros: psi owns the whole install story
   - cons: recreates dependency/package management and still has to solve extra library dependencies
2. pure deps-first with no psi-specific install layer
   - users edit deps/config directly and psi only discovers init vars
   - pros: minimal psi-specific implementation
   - cons: weak scope UX, weak introspection, weaker explicit install semantics
3. registry-first approach
   - define a central marketplace/registry now
   - pros: cleaner discovery story
   - cons: highest complexity, requires governance/infrastructure too early
4. status-quo plus better docs
   - keep namespace-link model and document conventions
   - pros: zero implementation cost
   - cons: does not solve publishing/install/distribution meaningfully and does not address extra deps well

Non-goals for the first slice:
- full marketplace/discovery ecosystem
- dependency conflict isolation across all extensions
- file watching or automatic manifest reapplication
- dynamic unload/reload of arbitrary library graphs in-process as a required feature
- automatic trust scoring/signing ecosystem
- broad extension UX beyond manifest editing, restart/apply, validation, and introspection
- supporting every possible source kind up front

Acceptance:
- a concrete first-slice install model is chosen
- user and project scope semantics are explicit
- the hybrid psi-manifest + tools.deps direction is either confirmed or rejected explicitly
- the deps.edn-shaped manifest schema is either confirmed or revised explicitly
- `:psi/init` as the activation contract is either confirmed or revised explicitly
- policy for ordinary non-extension deps in extension manifests is explicit
- duplicate `:psi/init` hard-error semantics are explicit
- git dependency policy using `:git/sha` in both scopes is explicit
- standard deps-form support including mvn deps is explicit
- merge/precedence semantics are explicit
- explicit reload/apply plus restart fallback expectations are explicit
- narrowed reload/apply result semantics are explicit
- structured validation/introspection expectations on the canonical read surface are explicit
- the intended projection shape for effective state, diagnostics, and last-apply result is explicit enough to implement
- reproducibility expectations for project installs are defined
- open follow-on work (marketplace, stronger isolation, richer trust model, command UX, richer live reload) is clearly separated from slice one
