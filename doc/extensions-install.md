# Extension install manifests

Psi now treats launcher-owned startup basis construction as the canonical way to
make extension dependencies available at startup.

The launcher reads user and project extension manifests **before** `psi.main`
starts, expands recognized psi-owned defaults, validates the effective manifest,
and supplies the resulting dependency basis at launch time.

Runtime introspection and reload/apply still exist, but they are no longer the
primary startup dependency mechanism.

## Canonical startup relationship

- launcher owns startup dependency availability
- runtime owns post-startup extension behavior, introspection, and reload/apply convenience paths

This closes the phase-order problem where manifests discovered after JVM startup
could not reliably influence the initial classpath.

## Manifest locations

User scope:
- `~/.psi/agent/extensions.edn`

Project scope:
- `.psi/extensions.edn`

Merge semantics:
- user and project manifests are read independently
- effective entries merge by library symbol key
- project scope overrides user scope for the same lib key

## Canonical startup flow

1. run `psi ...`
2. launcher determines effective cwd/worktree
3. launcher reads user and project manifests
4. launcher merges them with project-over-user precedence by lib key
5. launcher expands recognized psi-owned defaults
6. launcher infers deterministic `:psi/init` for recognized psi-owned libs when omitted
7. launcher validates the expanded manifest
8. launcher constructs startup basis and launches psi

## Manifest shape

The manifest uses a top-level `:deps` map.

Any dep entry with `:psi/init` is treated as an extension dependency.
Entries without `:psi/init` are allowed as support deps and appear as
non-extension entries in introspection.

Example:

```clojure
{:deps
 {my/local-ext
  {:local/root "/Users/me/dev/my-local-ext"
   :psi/init my.local.ext/init
   :psi/enabled true}

  io.github.me/cool-ext
  {:git/url "https://github.com/me/cool-ext"
   :git/sha "abc123def456"
   :psi/init cool.ext/init}

  io.github.someone/packaged-ext
  {:mvn/version "0.2.1"
   :psi/init packaged.ext/init
   :psi/enabled false}

  ;; support dep, not an extension entry
  org.clojure/data.json
  {:mvn/version "2.5.1"}}}
```

## Minimal psi-owned manifest syntax

Recognized psi-owned extension libs can use concise manifest entries.

Canonical minimal form:

```clojure
{:deps
 {psi/mementum {}
  psi/munera {}
  psi/work-on {}
  psi/workflow-loader {}}}
```

For those recognized libs, the launcher supplies missing fields from its
explicit psi-owned extension catalog, including:
- source identity
- version/ref identity
- `:deps/root`
- `:psi/init`

Selective override remains valid:

```clojure
{:deps
 {psi/mementum {:git/sha "override-sha"}
  psi/munera   {:psi/init extensions.munera/init}}}
```

Important rules:
- minimal `{}` syntax is valid only for recognized psi-owned extension libs in the launcher catalog
- explicit manifest fields override launcher defaults field-by-field
- launcher must still validate the final entry as one coherent coordinate family
- launcher does **not** infer `:psi/init` for arbitrary third-party libraries

## Supported coordinate forms

Each expanded dep entry must use exactly one dependency coordinate family:
- `:local/root`
- git coordinates
- `:mvn/version`

Validation rules currently enforced by launcher expansion:
- top-level manifest must be a map
- `:deps` must be a map
- dependency entry must be a map
- effective dependency entry must resolve to one coherent coordinate family
- git dependency entries require `:git/url` and one of `:git/sha` or `:git/tag`
- minimal psi-owned syntax is allowed only for recognized catalogued psi-owned libs

## Startup vs runtime behavior

Startup:
- launcher builds the startup basis before psi starts
- recognized psi-owned extension deps can become concrete launch-time deps without repeated boilerplate
- third-party deps must remain explicit enough to validate and enter the startup basis

Runtime:
- introspection still reports user/project/effective extension state
- reload/apply remains useful as a convenience path after startup
- `:restart-required` remains a runtime convenience/recovery status, not the canonical startup contract

### Launcher/runtime boundary note

For recognized psi-owned minimal manifest entries such as:

```clojure
{:deps {psi/workflow-loader {}
        psi/mementum {}}}
```

ownership is split deliberately:
- launcher expands the concise manifest syntax into concrete startup deps and puts those extension namespaces on the JVM classpath
- runtime computes install state, activates extensions, reports diagnostics, and supports reload/apply after startup

This means launcher-started `psi` is the authoritative proof path for classpath-sensitive behavior.
Direct bootstrap or in-process test paths that do not cross the launcher boundary are useful for runtime activation testing, but they are **not** equivalent proofs of launcher-owned classpath construction.

For recognized psi-owned minimal entries, runtime activation is canonicalized through `:psi/init` and the live registry identity is stable `manifest:{lib}` rather than a source file path, for example:
- `manifest:psi/workflow-loader`
- `manifest:psi/mementum`

## Introspection

The canonical read surface exposes:
- `:psi.extensions/user-manifest`
- `:psi.extensions/project-manifest`
- `:psi.extensions/effective`
- `:psi.extensions/diagnostics`
- `:psi.extensions/last-apply`

Example query:

```clojure
{:action "query"
 :query "[:psi.extensions/user-manifest
          :psi.extensions/project-manifest
          :psi.extensions/effective
          :psi.extensions/diagnostics
          :psi.extensions/last-apply]"
 :entity "{:psi.agent-session/session-id \"sid\"}"}
```

## Diagnostics categories

Current categories include:
- `:malformed-entry`
- `:duplicate-init`
- `:missing-git-sha`
- `:project-local-root-nonreproducible`
- `:load-failure`
- `:restart-required`

## Registry identities

Manifest-installed non-file-backed extensions activated by `:psi/init` use
stable live-registry identities of the form:
- `manifest:{lib}`

Examples:
- `manifest:psi/mementum`
- `manifest:psi/workflow-loader`

## Recommended workflow

### Startup install

1. add entries to `~/.psi/agent/extensions.edn` or `.psi/extensions.edn`
2. start psi with `psi ...`
3. inspect extension state via introspection when needed

### Local development install

Use explicit `:local/root` plus `:psi/init` for non-catalog third-party work, or
minimal `{}` syntax for recognized psi-owned extensions when launcher defaults are desired.

### Git or mvn install

1. add a manifest entry with explicit git/mvn coordinates and `:psi/init`, or use recognized psi-owned minimal syntax
2. start psi through the launcher so the basis is built before startup
3. inspect `:psi.extensions/effective`, `:psi.extensions/diagnostics`, and `:psi.extensions/last-apply` as needed
