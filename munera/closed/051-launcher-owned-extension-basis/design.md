Goal: make psi startup launcher-owned so extension dependency availability is decided before the JVM starts, eliminate reliance on a user-defined `:psi` alias, and allow concise psi-owned extension manifest entries with launcher-supplied defaults and deterministic `:psi/init` inference.

Intent:
- move startup dependency availability to the only phase that can solve it reliably: launch-time basis construction
- replace the current external alias bootstrap contract with a psi-owned startup contract
- make project/user extension manifests affect startup classpath deterministically
- reduce boilerplate for psi-owned extension entries by defaulting source/version/root metadata and inferring `:psi/init` where the mapping is canonical

Problem statement:
- psi is commonly launched today with `clojure -M:psi`
- that startup path depends on a user-maintained alias in `~/.clojure/deps.edn`, so psi does not own its own canonical startup contract
- the Clojure CLI computes the startup basis before `psi.main` runs
- psi reads `~/.psi/agent/extensions.edn` and `<worktree>/.psi/extensions.edn` only after startup, which is too late for those manifests to influence the initial classpath
- because manifest deps arrive too late, non-local extension deps (git/mvn/support deps) are visible in runtime introspection but not available on the startup classpath
- psi currently attempts an in-process fallback via `clojure.repl.deps/sync-deps`, but that path depends on REPL-only machinery and is not reliable in the ordinary launcher/runtime shape
- the gordian case demonstrates the failure mode clearly: configured extension deps are detected, but all non-local entries remain `:restart-required` because startup basis construction happened before manifest interpretation and the running process cannot reliably repair that afterward
- psi-owned extension entries are also unnecessarily verbose today because they repeat repo/version/root metadata and explicit `:psi/init` values that psi itself can know

Desired outcome:
- psi has one canonical launcher-owned startup path
- that launcher does not require the user to predefine a `:psi` alias
- the launcher reads user and project extension manifests before psi starts
- the launcher merges those manifests with project-over-user precedence by lib key
- the launcher expands psi-owned extension defaults for missing source/version/root fields
- the launcher infers `:psi/init` for psi-owned extension libs when the mapping is deterministic
- the launcher validates the expanded manifest before launch
- psi starts with the needed extension namespaces already on the classpath
- runtime introspection still reports extension install/effective state, but startup dependency correctness no longer depends on in-process mutation
- documentation points users to the launcher as the canonical startup method

Scope:
In scope:
- define the canonical launcher contract for starting psi
- define launcher ownership of startup basis construction
- define how the launcher determines the target cwd/worktree used for project manifest lookup
- define how the launcher reads:
  - `~/.psi/agent/extensions.edn`
  - `<worktree>/.psi/extensions.edn`
- define manifest merge semantics for launcher use
- define the expanded dependency basis assembled by the launcher before invoking psi
- define the psi-owned extension catalog needed for startup defaults
- define deterministic `:psi/init` inference for recognized psi-owned extension libs
- define field override semantics when manifests explicitly provide source/version/root/init values
- define validation and operator-facing failure modes for malformed manifests, ambiguous inference, and invalid expanded deps
- define the intended relationship between launcher-owned startup basis construction and the existing runtime manifest introspection/apply surfaces
- define the user-facing documentation changes needed to make the launcher canonical

Out of scope:
- implementing a full distribution/release/install system for psi
- redesigning general extension runtime activation behavior after startup
- removing runtime extension manifest introspection
- general `:psi/init` inference for arbitrary third-party extension libs
- redesigning the manifest file format beyond launcher-side expansion/defaulting rules
- solving every possible post-startup reload/apply scenario for non-local deps
- changing unrelated extension semantics or runtime architecture beyond startup ownership boundaries
- deciding the long-term packaging story for all installation modes beyond what the launcher needs to start psi coherently

Minimum concepts:
- launcher: the single startup authority that constructs the basis before JVM startup
- worktree/cwd: the project context used to locate `.psi/extensions.edn`
- manifest merge: project-over-user precedence by lib key
- expanded manifest entry: the fully resolved dep entry after launcher defaults and inference are applied
- concrete dependency entry: one valid tools.deps dependency map for a single lib, using exactly one coherent coordinate family and suitable for insertion into the startup basis
- psi-owned extension catalog: the recognized set of psi extension libs with canonical default metadata
- recognized psi-owned extension lib: a lib symbol present in the launcher-owned psi extension catalog
- deterministic init inference: launcher-owned mapping from recognized psi-owned lib to canonical `:psi/init`
- startup basis: psi’s own coordinates plus expanded extension deps supplied to the Clojure CLI before psi starts
- installed use: operator-facing use of psi via an installed `psi` command on `PATH`, with launcher behavior obtained through `bbin` installation rather than direct repo-local invocation
- override semantics: explicit manifest fields win; launcher fills only missing fields

Canonical behavior:
1. user invokes the psi launcher rather than `clojure -M:psi`
2. launcher determines the effective cwd/worktree
3. launcher reads user and project manifests
4. launcher merges manifests with project-over-user precedence by lib key
5. launcher expands recognized psi-owned extension defaults for missing source/version/root fields
6. launcher infers `:psi/init` for recognized psi-owned extension entries when omitted and the mapping is unique/canonical
7. launcher validates the fully expanded manifest
8. launcher constructs the startup basis, including psi itself and the expanded extension deps
9. launcher invokes psi main with that basis
10. psi starts with extension namespaces resolvable from the startup basis

Psi-owned extension defaulting:
- defaulting is keyed by recognized psi-owned extension library symbols, not by heuristics over arbitrary entries
- for each recognized psi-owned extension lib, the launcher may know:
  - canonical source identity
  - canonical version/ref identity
  - canonical `:deps/root`
  - canonical `:psi/init`
- launcher defaults fill only missing fields
- explicit manifest values override launcher defaults field-by-field
- if a recognized psi-owned extension does not have a unique canonical init mapping, launcher inference must refuse to guess and require an explicit `:psi/init`

Minimal psi-owned manifest ergonomics target:
- a psi-owned extension entry should be able to state intent without repeating known psi source metadata
- a psi-owned extension entry may omit `:psi/init` when the launcher has a deterministic catalog mapping for that lib
- explicit pinning, source overrides, and explicit init values remain valid for advanced use and experiments

Exact minimal manifest syntax for psi-owned extensions:
- for recognized psi-owned extension libs, the preferred minimal manifest form is an empty or nearly-empty dep map keyed by the library symbol
- canonical minimal form:
  ```clojure
  {:deps
   {psi/mementum {}
    psi/munera {}
    psi/work-on {}
    psi/workflow-loader {}}}
  ```
- in that form, the launcher supplies all recognized missing psi-owned fields, including:
  - source/version identity
  - `:deps/root`
  - `:psi/init`
- a minimally explicit form that still allows selective override is also valid, for example:
  ```clojure
  {:deps
   {psi/mementum {:git/sha "override-sha"}
    psi/munera   {:psi/init extensions.munera/init}}}
  ```
- field-by-field behavior for recognized psi-owned libs is:
  - omitted source/version/root/init fields are supplied by launcher defaults when the catalog provides them
  - explicitly provided fields override launcher defaults
- empty maps are valid only for recognized psi-owned extension libs that have a complete launcher-owned catalog entry
- empty maps are not a general extension rule and must not be treated as valid for arbitrary third-party entries
- if a recognized psi-owned extension lib lacks a complete launcher-owned default set, the launcher must fail clearly and require the missing field(s) explicitly rather than guessing
- if a manifest entry for a recognized psi-owned lib specifies one coordinate field family explicitly, that explicit family wins and the launcher must not silently mix in a conflicting alternative family

Third-party extension rule:
- third-party libs remain explicit in this task
- the launcher does not infer `:psi/init` for arbitrary third-party libraries
- if third-party inference is ever desired, it requires a separate task with explicit semantics and failure rules

Psi-owned extension catalog contract:
- the launcher must own a catalog of recognized psi-owned extension libs
- the catalog is the sole authority for launcher-supplied psi-owned defaults and init inference
- each catalog entry must be keyed by the extension lib symbol, for example `psi/mementum`
- each catalog entry must describe one unambiguous canonical startup shape for that lib
- catalog entries must store explicit `:psi/init` values; runtime naming conventions are not the source of truth for init inference in this task
- the minimum required fields of a complete catalog entry are:
  - library symbol
  - canonical coordinate family/source identity
  - canonical version/ref identity source
  - canonical `:deps/root` when applicable
  - canonical `:psi/init`
- the catalog may also carry operator-facing metadata, such as a human label or notes, but those are not part of the minimum launch contract
- the catalog must be complete enough that a recognized psi-owned entry written as `{}` can be expanded into one valid concrete dep entry
- the catalog must be deterministic:
  - one recognized lib maps to one canonical default startup shape
  - if there are multiple plausible startup shapes for a lib, the catalog must not guess; the manifest must become explicit
- the catalog must be closed over the set of psi-owned extension libs that support minimal manifest syntax in this task
- an extension lib not present in the catalog is not eligible for psi-owned defaulting or init inference

Catalog behavior:
- during manifest expansion, the launcher looks up each merged dep entry by lib symbol
- when the lib is present in the psi-owned catalog, the launcher may supply missing fields from that catalog entry
- when the lib is not present in the catalog, the launcher applies no psi-owned defaulting beyond ordinary manifest validation
- explicit manifest fields always override catalog-supplied fields
- catalog-supplied fields must never overwrite explicit manifest intent
- if explicit fields partially conflict with the catalog, the explicit fields win, but the resulting entry must still validate as one coherent coordinate family
- if explicit fields make the entry incoherent, the launcher must fail clearly rather than silently combining incompatible values

Catalog source-of-truth expectations:
- the catalog must have one explicit representation owned by psi
- the launcher must not infer the psi-owned catalog dynamically from source-tree guessing at launch time
- the representation may be data or code, but it must be auditable, reviewable, and versioned with psi
- the catalog should be shaped so that adding a new psi-owned extension is an explicit, local update rather than distributed convention

Development vs installed use:
- the catalog contract must support both development and installed startup modes
- the same library symbol should map to the same logical extension identity in both modes
- the exact source/version realization may vary by startup mode, but that variation must be controlled by launcher policy, not by per-project manifest repetition
- if development mode and installed mode need different coordinate materialization, that difference should be handled at the launcher/source-policy layer while preserving one catalog identity per psi-owned lib
- launcher policy must be explicit here:
  - installed use is the canonical operator path and must use one launcher-owned default psi source/version policy
  - development use may use a repo-local psi self-basis policy for contributors working in the psi repository
  - the launcher must not guess between development and installed realization based on vague environment heuristics
  - if multiple realization policies are supported, the selected policy must be explicit in launcher configuration or invocation semantics
- the default psi source/version used for psi self-basis and psi-owned extension defaults must come from one explicit launcher-owned source of truth
- launcher versioning policy must determine the default psi runtime/version identity used in installed mode unless the user explicitly overrides it

Failure rules:
- if a manifest uses minimal psi-owned syntax for a lib that is missing from the catalog, launcher expansion must fail clearly
- if a catalog entry lacks any field required to build one coherent startup dep entry, launcher expansion must fail clearly
- if catalog-supplied init inference is absent or ambiguous for a lib using omitted `:psi/init`, launcher expansion must fail clearly
- failure messages should identify the lib and the missing or ambiguous catalog responsibility

Acceptance additions for the catalog:
- the design defines a launcher-owned psi-owned extension catalog as the authority for defaults and init inference
- the design makes clear that only recognized catalog entries qualify for minimal psi-owned manifest syntax
- the design makes clear that the catalog must be explicit, versioned, and deterministic
- the design makes clear that explicit manifest fields override catalog defaults but do not permit incoherent mixed coordinate families

Override semantics:
- project manifest overrides user manifest by lib key
- after merge, launcher defaults apply only to recognized psi-owned entries and only to missing fields
- explicit manifest values always win over launcher defaults
- validation runs on the fully expanded result, not the pre-expansion manifest

Validation expectations:
- malformed manifest structure fails before launch with a clear parse/shape error
- unsupported or incomplete expanded dep entries fail before launch
- ambiguous or unavailable psi-owned init inference fails before launch
- startup failure messages should identify which stage failed:
  - manifest read/parse
  - merge
  - psi-owned default expansion
  - init inference
  - basis construction / launch invocation
- the launcher should make it possible to understand whether startup failed because a manifest was malformed, a default could not be supplied, an init var could not be inferred, or the Clojure CLI basis could not be built

Architecture alignment:
- startup dependency availability becomes launcher-owned, not runtime-owned
- runtime remains responsible for behavior after startup, including extension state introspection and any convenience reload/apply surfaces
- launcher and runtime responsibilities should be explicitly separated in docs and implementation shape
- this task should preserve the current runtime introspection surfaces rather than replacing them
- the launcher should be self-sufficient and should not depend on a user-defined `:psi` alias
- the canonical startup command should become psi-owned rather than delegated to user configuration

Launcher CLI contract:
- psi should expose one canonical launcher command owned by the project
- the launcher command becomes the primary documented startup surface for console, TUI, and RPC use
- the launcher must preserve ordinary psi runtime flags and pass them through to `psi.main` after launch-time basis construction

Preferred external surface:
- canonical command name: `psi`
- canonical invocation shape:
  - `psi`
  - `psi --tui`
  - `psi --rpc-edn`
  - `psi --nrepl`
  - `psi <existing psi flags ...>`
- the launcher should feel like a thin startup wrapper around psi itself, not like a second independent tool with a different runtime interface
- the launcher should be installable via `bbin` so users can obtain the canonical `psi` command without first hand-authoring shell wrappers or Clojure CLI aliases

Launcher-specific flags:
- the launcher may define a small launcher-only flag surface when needed for startup basis construction and operator debugging
- preferred launcher-only flags are:
  - `--cwd <path>`
    - override the working directory used for project manifest lookup and startup process execution
    - when omitted, launcher uses the current process working directory
  - `--launcher-debug`
    - print or otherwise expose the resolved manifest merge, psi-owned default expansion, and effective startup basis summary before exec
    - intended for operator diagnosis of startup-basis issues
- launcher-only flags must be consumed by the launcher and must not be forwarded to `psi.main`
- all other flags are forwarded unchanged to `psi.main`

BBIN packaging/install contract:
- the canonical `psi` launcher should be distributed as a `bbin`-installable babashka command
- `bbin` installation is the preferred operator-facing way to obtain the `psi` command
- installation should give the user a directly invokable `psi` executable on `PATH`
- the `bbin`-installed `psi` command must implement the same canonical launcher contract described in this task
- the launcher must remain self-sufficient after installation and must not require the user to create a `:psi` alias manually
- `bbin` packaging should own only launcher installation and entrypoint exposure; psi runtime semantics remain owned by psi itself

BBIN source contract:
- the launcher must have one explicit babashka entrypoint suitable for `bbin` installation
- that entrypoint must be versioned with the psi repository and reviewed as part of psi changes
- the `bbin` install target should produce the canonical command name `psi`
- the design should avoid requiring post-install manual renaming or wrapper creation by the user

BBIN installation ergonomics:
- the preferred user story is:
  1. install via `bbin`
  2. run `psi ...`
- installation docs should not require the user to hand-author shell scripts or Clojure CLI aliases as part of the normal path
- if development contributors need alternate local invocation paths, those may remain documented as development-only, but they are not the primary operator path

BBIN/update expectations:
- the design should account for normal `bbin` upgrade/reinstall workflows for getting newer launcher behavior
- updating the launcher should not require users to revisit alias configuration because alias configuration is no longer canonical
- launcher installation/update should be conceptually separate from per-project extension manifest authoring

BBIN responsibility boundary:
- `bbin` installation provides the executable command surface
- the launcher executable provides startup basis construction and psi invocation
- the launched psi process provides runtime behavior after startup
- docs and implementation should preserve these boundaries clearly

Argument handling rules:
- launcher argument parsing must cleanly separate launcher-owned flags from psi runtime flags
- launcher-owned flags must have deterministic parsing and clear errors for missing values
- after launcher-owned flags are consumed, remaining args are passed through to `psi.main` unchanged and in order
- the launcher must not reinterpret ordinary psi runtime flags such as `--tui`, `--rpc-edn`, `--nrepl`, `--model`, or other existing psi CLI flags

Working-directory contract:
- by default, the launcher uses the current working directory as both:
  - the project manifest lookup root
  - the process working directory for the launched psi process
- when `--cwd <path>` is provided, the launcher uses that path as both:
  - the project manifest lookup root
  - the process working directory for the launched psi process
- the launcher must not use one cwd for manifest expansion and a different cwd for the launched process, because that would make startup behavior non-obvious

Exec contract:
- the launcher computes the full startup basis before psi starts
- the launcher then performs one exec-style handoff into the Clojure CLI / psi main process
- after handoff, psi runtime behavior belongs to `psi.main` and existing runtime CLI semantics
- the launcher should avoid becoming a long-running supervisory process unless a separate future task explicitly requires that

Debug contract:
- `--launcher-debug` should provide enough information to diagnose startup-basis behavior without requiring source inspection
- `--launcher-debug` is observational only: it does not switch the launcher into dry-run mode and does not alter launch semantics
- debug output should be emitted on a consistent operator-facing stream before exec
- the minimum contract for this task is a human-readable summary; structured debug output is optional and may be a follow-on enhancement
- the debug output should identify at least:
  - effective cwd
  - user/project manifest presence
  - merged manifest lib keys
  - which entries used psi-owned defaults
  - which entries used inferred `:psi/init`
  - a summary of the effective startup dep basis additions
- debug output is informational only and must not change launch semantics

Documentation contract:
- docs should present `psi` as the canonical startup command
- docs should preserve mapping from old startup examples to new launcher usage so operators can understand the transition
- if legacy `clojure -M:psi` examples remain temporarily for development or backward compatibility, docs must clearly mark them as non-canonical

Startup basis construction contract:
- the launcher owns construction of the startup basis supplied to the Clojure CLI before psi starts
- startup basis construction must be deterministic from:
  - launcher policy
  - psi-owned extension catalog
  - effective cwd/worktree
  - merged user/project extension manifests
- startup basis construction must not depend on post-startup runtime mutation

Basis contents:
- the startup basis must include psi itself
- the startup basis must include the deps required to start `psi.main`
- the startup basis must include expanded extension deps derived from the merged manifest
- the startup basis may include additional launcher-owned support deps only when they are required for coherent startup and are part of explicit launcher policy
- basis construction for this task is about dependency availability; it is not a separate runtime configuration channel

Psi self-basis:
- the launcher must supply the coordinates/paths needed to launch psi without relying on a user-defined `:psi` alias
- the launcher must own one explicit policy for how psi itself is materialized in the startup basis
- that policy may differ between development and installed use, but it must be launcher-owned and documented
- psi self-basis construction is part of the launcher contract, not part of per-project manifest authoring

Expanded manifest basis additions:
- after manifest merge, psi-owned defaulting, and init inference, each extension manifest entry contributes either:
  - a concrete dependency entry included in the startup basis, or
  - a validation/startup error that prevents launch
- recognized psi-owned extension entries using minimal syntax must expand into concrete basis entries without requiring repeated per-project boilerplate
- third-party entries must already provide enough explicit coordinate data to become concrete basis entries after validation

What belongs in launch-time basis data:
- dependency coordinates and source identity belong in the launch-time basis
- values that determine classpath/code availability belong in the launch-time basis
- canonical extension dependency coordinates belong in the launch-time basis
- the launcher may synthesize these via `-Sdeps` or an equivalent pre-startup basis mechanism

What does not belong in launch-time basis data:
- runtime-only session flags and psi UI/mode selection do not become part of dependency basis synthesis
- extension activation results do not belong in the startup basis; they remain runtime concerns after startup
- runtime introspection state does not belong in the startup basis
- convenience reload/apply semantics do not belong in the startup basis contract

Coordinate-family rules for basis synthesis:
- each expanded dep entry must resolve to exactly one coherent coordinate family before being added to the basis
- the launcher must not synthesize mixed or conflicting coordinate families for one lib
- explicit manifest coordinate fields win over launcher defaults, but the final entry must still be coherent
- if the final entry is not one coherent coordinate family, launch must fail clearly before basis construction completes

Support dependencies:
- support deps declared in extension manifests remain part of the effective dependency basis when valid
- support deps do not imply extension activation by themselves
- support deps affect startup classpath availability and therefore belong in launch-time basis synthesis
- support deps follow the same validation/coherence rules as extension deps, except they do not participate in `:psi/init` inference or extension activation semantics

Mode-policy boundary:
- the launcher may have a policy distinction between development startup and installed startup
- that policy boundary may influence how psi itself and psi-owned extension defaults are materialized
- mode policy must not change the logical meaning of a manifest entry
- the same merged manifest should describe the same intended extensions across modes; only the launcher’s realization strategy may differ

Basis construction output contract:
- before exec, the launcher must have one complete, concrete startup basis description ready for handoff to the Clojure CLI
- that basis description must be explainable in operator/debug output
- the launcher should be able to summarize which deps came from:
  - psi self-basis policy
  - user/project manifest entries
  - psi-owned default expansion
- if `--launcher-debug` is enabled, this summary should be surfaced before exec

Failure rules for basis construction:
- if psi self-basis cannot be constructed, launch fails before exec
- if merged manifest expansion yields invalid dependency data, launch fails before exec
- if the launcher cannot convert expanded manifest entries into one coherent startup basis, launch fails before exec
- if support deps or extension deps conflict structurally at basis-construction time in a way the launcher cannot represent coherently, launch fails clearly rather than silently dropping entries
- failure messages should distinguish basis-construction failures from post-startup runtime activation failures

Non-goals within basis construction:
- this task does not require the launcher to solve every downstream dependency conflict automatically
- this task does not require the launcher to mutate an already-running psi process
- this task does not require a second runtime dependency model beyond the startup basis the Clojure CLI already understands

Documentation migration contract:
- this task changes psi’s canonical startup story, so documentation migration is part of the task rather than follow-on polish
- docs must make the launcher-owned startup contract discoverable from the highest-traffic entry points
- docs must explain both the new canonical path and the relationship to the old alias-based path during any transition period
- docs must explain how to install the launcher via `bbin` and treat that installation path as the preferred operator-facing setup path

Documentation surfaces that must be aligned:
- operator-facing docs
  - `README.md`
    - must present `psi` as the primary startup command
    - must stop requiring a user-defined `:psi` alias as the primary quick-start path
  - `doc/cli.md`
    - must describe launcher invocation as the canonical CLI surface
    - must distinguish launcher-only flags from forwarded psi runtime flags
  - `doc/extensions-install.md`
    - must explain that startup dependency availability for manifest deps is launcher-owned
    - must explain concise psi-owned manifest entries, launcher defaults, and inferred `:psi/init` behavior
  - `doc/extensions.md`
    - must remain coherent with the launcher-owned psi-owned extension catalog story
- contributor/development docs
  - any retained repo-local or alias-based startup flows may be documented only as development/non-canonical alternatives
- any other startup-facing docs that currently instruct users to launch psi via `clojure -M:psi`
  - must either be updated to `psi ...` or clearly marked as development/legacy/non-canonical

Migration messaging requirements:
- docs must preserve operator comprehension of the old startup path long enough to avoid confusion during transition
- when old examples remain for dev/backward-compatibility reasons, docs must label them explicitly as non-canonical
- docs must explain why the launcher is now canonical:
  - startup basis is built before psi starts
  - project/user extension manifests need to influence that basis
  - runtime in-process dependency repair is not the canonical startup mechanism

Documentation content requirements:
- quick-start docs must tell a user how to install and start psi without editing `~/.clojure/deps.edn` to create a `:psi` alias
- startup docs must show canonical examples for:
  - `bbin install ...`
  - `psi`
  - `psi --tui`
  - `psi --rpc-edn`
  - any launcher-only diagnostic or cwd flags adopted in this task
- extension install docs must show the concise psi-owned manifest syntax supported by launcher defaults
- extension install docs must explain the boundary between:
  - launcher-owned startup dependency availability
  - runtime extension introspection
  - any remaining runtime reload/apply convenience behavior
- docs must explain that minimal `{}` entries are valid only for recognized psi-owned extension libs covered by the launcher catalog

Discoverability requirements:
- a new or updated user should be able to find the canonical launcher story from `README.md` without reading source code
- a user investigating extension startup behavior should be able to find the launcher/manifest relationship from extension docs without reading task history or implementation code
- a user familiar with the old alias-based startup path should be able to understand what changed and why

Consistency requirements:
- examples in docs must be internally consistent with the launcher contract defined in this task
- docs must not simultaneously present alias-based startup and launcher-owned startup as equally canonical
- wording across docs must preserve one clear ownership story:
  - launcher owns startup dependency availability
  - runtime owns behavior after startup

Acceptance additions for documentation migration:
- top-level startup documentation presents `psi` as the canonical startup command
- docs no longer require creation of a user-defined `:psi` alias as the primary setup path
- docs explain the transition from alias-based startup to launcher-owned startup clearly enough for existing users
- docs covering extension install/startup explain launcher-owned startup basis construction and concise psi-owned manifest syntax
- docs consistently distinguish launcher-only flags from forwarded psi runtime flags when the launcher contract is described

Possible implementation approaches/shapes:
1. Babashka launcher
   - reads manifests as EDN
   - expands psi-owned defaults/inferred init vars
   - constructs `clojure -Sdeps ... -M -m psi.main ...`
   - consumes launcher-only flags and forwards remaining psi flags unchanged
   - can be packaged as a `bbin`-installable command named `psi`
   - pros: robust EDN manipulation, clear basis synthesis, natural fit for a `bbin`-installable launcher, good fit for wrapper logic
   - cons: introduces/depends on a launcher implementation layer outside core runtime

2. Shell script launcher with helper EDN generation
   - lightweight wrapper around Clojure CLI
   - consumes launcher-only flags and forwards remaining psi flags unchanged
   - pros: simple external surface
   - cons: weaker EDN manipulation ergonomics, more quoting/portability complexity

3. Another self-contained wrapper mechanism
   - acceptable if it preserves one launcher-owned startup contract, clear flag separation, and basis synthesis before psi starts

Implementation constraint for this task:
- the intended implementation path is the babashka launcher shape using Clojure CLI pre-startup basis construction via `-Sdeps`
- references to an "equivalent" basis mechanism describe acceptable semantic equivalence, not a second preferred implementation path for this task

Current design bias:
- prefer a launcher implementation that can manipulate EDN and basis data cleanly
- prefer a launcher that is explicit and deterministic over clever reuse of the current alias story
- do not make runtime startup correctness depend on `sync-deps` or other in-process repair mechanisms

Relationship to existing runtime behavior:
- startup basis construction becomes the canonical way to make startup extension deps available
- runtime manifest introspection remains useful and should continue to expose effective state/diagnostics
- runtime reload/apply may remain as a convenience path, but it is no longer the primary answer to startup dependency correctness
- docs should clearly distinguish:
  - launcher-owned startup dependency availability
  - runtime extension state inspection
  - optional post-startup reload/apply behavior

Acceptance criteria:
1. Canonical startup ownership
   - psi has one clearly defined canonical launcher-owned startup path
   - that startup path is psi-owned rather than delegated to a user-maintained `:psi` alias
   - launcher basis construction happens before `psi.main` starts
   - the canonical launcher path is designed to be installable as a `bbin` command named `psi`
   - the preferred operator-facing setup path is `bbin` installation of the launcher

2. Launcher CLI contract
   - canonical startup examples use `psi ...` rather than `clojure -M:psi`
   - launcher-only flags are explicitly defined and separated from forwarded psi runtime flags
   - forwarded psi runtime flags preserve existing psi CLI semantics
   - working-directory behavior is explicit and unambiguous for both default cwd and `--cwd <path>` use

3. Manifest participation in startup basis construction
   - user and project extension manifests are part of launcher startup basis construction
   - project-over-user precedence by lib key is explicit
   - merged manifests are expanded and validated before launch
   - startup dependency availability no longer depends on post-startup in-process mutation as the primary mechanism

4. Psi-owned extension ergonomics
   - recognized psi-owned extension entries can omit source/version/root fields and receive launcher defaults
   - recognized psi-owned extension entries can omit `:psi/init` when launcher inference is deterministic for that lib
   - minimal `{}` syntax is clearly defined as valid only for recognized psi-owned catalog entries
   - explicit manifest fields override launcher defaults field-by-field
   - conflicting or incoherent explicit/default combinations fail clearly rather than being silently merged

5. Catalog contract
   - the design defines a launcher-owned psi-owned extension catalog as the sole authority for defaults and init inference
   - only recognized catalog entries qualify for minimal psi-owned manifest syntax
   - the catalog is explicit, versioned, and deterministic
   - missing or ambiguous catalog responsibilities fail clearly before launch

6. Startup basis construction contract
   - the design specifies what belongs in launch-time basis data and what remains runtime-only behavior
   - expanded manifest entries must become one coherent dependency entry per lib or fail before launch
   - support deps participate in startup basis construction when valid but do not imply extension activation
   - the design supports a concrete self-basis policy for psi without requiring a user alias

7. Runtime boundary clarity
   - the design clearly separates launcher responsibilities from runtime responsibilities
   - launcher owns startup dependency availability
   - runtime owns post-startup behavior, introspection, and any remaining convenience reload/apply semantics

8. Documentation migration
   - top-level startup documentation presents `psi` as the canonical startup command
   - docs no longer require creation of a user-defined `:psi` alias as the primary setup path
   - docs explain the transition from alias-based startup to launcher-owned startup clearly enough for existing users
   - docs explain `bbin` installation of the launcher as the preferred setup path
   - docs include a canonical installation story that yields a `psi` command on `PATH`
   - docs covering extension install/startup explain launcher-owned startup basis construction and concise psi-owned manifest syntax
   - docs consistently distinguish launcher-only flags from forwarded psi runtime flags

9. Gordian failure-mode closure
   - the design provides a concrete path to eliminate the gordian-style failure mode where extensions are configured and discovered but remain `:restart-required` solely because manifests were read after startup basis construction

Open design questions that remain legitimate to settle during implementation:
- exact launcher implementation medium
- exact source of truth for psi’s own source/version identity in development vs installed use
- exact psi-owned extension catalog contents and representation
- exact minimal manifest form allowed for psi-owned extension entries when source metadata and `:psi/init` are omitted
- whether the launcher should expose a debug surface for the fully expanded manifest/basis

Why this design is complete enough for planning:
- it states the task’s intent, problem, scope, and acceptance criteria explicitly
- it identifies the phase-order issue rather than treating the symptom as a runtime loading bug
- it assigns ownership of startup dependency availability to the correct boundary: the launcher
- it narrows convenience inference/defaulting to recognized psi-owned extension libs so behavior stays deterministic and explainable
- it leaves unrelated runtime behavior and broader packaging questions out of scope, which keeps the task implementable as one coherent vertical slice
