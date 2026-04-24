Implementation plan

Approach

Implement this task as a small sequence of vertical slices that establish the launcher contract first, then make startup basis construction real, then migrate docs and operator setup. Keep startup-basis ownership in the launcher and avoid broad runtime redesign.

Key implementation decisions

- Use a babashka launcher as the primary implementation shape.
- Make the launcher `bbin` installable and expose the canonical command name `psi`.
- Treat launcher basis construction as the authoritative solution for startup dependency availability.
- Preserve runtime manifest introspection and existing reload/apply surfaces as secondary/runtime concerns.
- Restrict source/version/root defaulting and `:psi/init` inference to a launcher-owned explicit catalog of recognized psi-owned extension libs.

Slice 1 — Establish launcher skeleton and CLI contract

Goal:
- Create the launcher entrypoint and make the command surface explicit before changing startup basis logic.

Work:
- Add a babashka launcher entrypoint suitable for `bbin` installation.
- Implement launcher-only arg parsing for:
  - `--cwd <path>`
  - `--launcher-debug`
- Pass all remaining args through unchanged to psi runtime invocation.
- Decide and implement the exec handoff shape to Clojure CLI / `psi.main`.
- Add focused tests for arg separation, cwd selection, and pass-through behavior.

Proof:
- Running the launcher with ordinary psi flags forwards them unchanged.
- Launcher-only flags are consumed locally and not forwarded.
- The launcher can produce a `psi` command surface suitable for later `bbin` packaging.

Slice 2 — Implement manifest read/merge and expanded-entry model in the launcher

Goal:
- Move manifest interpretation into the launcher in a way that is deterministic and testable before basis synthesis.

Work:
- Read user and project manifests from the launcher.
- Implement project-over-user merge by lib key.
- Reuse or mirror canonical manifest validation semantics where appropriate.
- Introduce a launcher-local expanded-entry pipeline:
  - merged entry
  - psi-owned catalog lookup
  - default filling
  - `:psi/init` inference for recognized psi-owned libs
  - final validation
- Add clear error shaping for malformed manifests, incomplete defaults, and ambiguous init inference.

Proof:
- Focused tests cover:
  - user/project precedence
  - recognized psi-owned minimal `{}` syntax
  - explicit field override behavior
  - third-party entries staying explicit
  - clear failures for missing/ambiguous catalog data

Slice 3 — Define and wire the psi-owned extension catalog

Goal:
- Make psi-owned defaults and init inference explicit, versioned, and deterministic.

Work:
- Introduce one explicit catalog representation owned by psi.
- Populate the catalog for the psi-owned extensions covered by the task.
- Store explicit `:psi/init` values in catalog entries rather than deriving them from naming convention at runtime.
- Define the policy boundary between logical catalog identity and dev-vs-installed realization.
- Define one explicit launcher-owned source of truth for the default psi runtime/version identity used in installed mode.
- Ensure catalog entries are sufficient to expand minimal psi-owned manifest entries into concrete dep entries.
- Add focused tests for catalog completeness and deterministic inference.

Proof:
- A recognized psi-owned lib can expand from `{}` to one coherent dep entry.
- An unrecognized lib does not receive psi-owned defaults.
- Ambiguous or incomplete catalog entries fail clearly.

Slice 4 — Implement startup basis synthesis and exec integration

Goal:
- Turn expanded manifest entries into the actual startup basis used to launch psi.

Work:
- Define the launcher policy for psi self-basis construction.
- Make the dev-vs-installed realization policy explicit rather than heuristic.
- Synthesize the launch basis from:
  - psi self-basis
  - expanded manifest deps
- Materialize the basis through `-Sdeps` pre-startup Clojure CLI basis construction.
- Ensure coherent one-family dep entries only.
- Add launcher debug output summarizing effective cwd, merged manifest keys, psi-owned default usage, inferred init usage, and basis additions.
- Add focused tests for basis synthesis shape and failure conditions.
- Add focused end-to-end proof that a recognized psi-owned minimal `{}` entry expands into startup basis data successfully.
- Add focused end-to-end proof that an explicit third-party manifest entry participates in startup basis construction without psi-owned defaulting.
- Add focused end-to-end proof that conflicting coordinate-family data fails clearly before exec.

Proof:
- The launcher can construct one complete startup basis before exec.
- Invalid expanded entries fail before startup.
- Debug output explains the effective basis inputs.

Slice 5 — Connect startup runtime behavior to launcher-owned basis ownership

Goal:
- Align runtime expectations with the new startup contract without overextending into a large runtime redesign.

Work:
- Review startup docs/comments and any runtime-facing messaging that still implies alias-owned or runtime-owned startup dependency availability.
- Narrow any misleading runtime messaging where startup ownership is now the launcher.
- Keep manifest introspection intact.
- Ensure the design/story around `:restart-required` remains coherent for runtime convenience behavior while startup correctness is launcher-owned.

Proof:
- Startup ownership is described consistently in code comments and user-facing status/help text that touches this area.
- Runtime introspection remains available and coherent.

Slice 6 — BBIN packaging and installation surface

Goal:
- Make the launcher practically installable and usable as the canonical `psi` command.

Work:
- Add the `bbin` installable entrypoint/package shape.
- Ensure installation yields a `psi` command on `PATH`.
- Define/update any babashka package metadata needed for installation.
- Add a lightweight proof or testable check for the installable command contract where practical.
- Prove the `bbin`-installed `psi` command exercises the same launcher arg contract and startup-basis code path as direct/local launcher invocation.

Proof:
- The repository exposes a `bbin` installation path that yields the canonical `psi` command.
- The installed command is the same launcher contract tested in earlier slices.

Slice 7 — Documentation migration

Goal:
- Make the launcher the canonical documented startup path and explain the transition clearly.

Work:
- Update `README.md` quick start to use `bbin` installation + `psi` command.
- Update `doc/cli.md` to present launcher invocation as canonical and distinguish launcher-only flags from forwarded psi flags.
- Update `doc/extensions-install.md` to explain launcher-owned startup dependency availability and concise psi-owned manifest syntax.
- Update `doc/extensions.md` to stay consistent with the psi-owned extension catalog/defaulting story.
- Update other startup-facing docs that still present `clojure -M:psi` as primary.
- Add an explicit migration note mapping alias-based `clojure -M:psi ...` examples to `psi ...`.
- Mark any retained alias-based/dev-only flows as non-canonical.

Proof:
- A new user can discover install + startup from docs alone.
- An existing user can understand what changed and why.
- Docs consistently preserve the launcher/runtime ownership boundary.

Suggested execution order

1. Slice 1 — launcher skeleton and CLI contract
2. Slice 2 — manifest read/merge and expanded-entry model
3. Slice 3 — psi-owned extension catalog
4. Slice 4 — startup basis synthesis and exec integration
5. Slice 6 — `bbin` packaging/install surface
6. Slice 7 — documentation migration
7. Slice 5 — runtime wording/alignment cleanup, if still needed after startup path lands

Reason for this order:
- establish the launcher contract first
- make manifest semantics testable before basis synthesis
- make catalog/defaulting explicit before relying on it in launch behavior
- only then package/install and migrate docs around the finished startup path
- leave runtime wording cleanup late so it reflects the implemented contract rather than a predicted one

Risks and control points

1. Dev vs installed psi self-basis policy could sprawl.
- Control by keeping one explicit launcher policy boundary and one catalog identity per psi-owned lib.

2. Catalog/defaulting could become heuristic.
- Control by requiring explicit catalog entries and refusing inference for unrecognized or ambiguous cases.

3. Shelling out to Clojure CLI with synthesized basis data can become quoting/shape fragile.
- Control by preferring babashka/EDN-first construction and testing the exact exec argument shape.

4. Documentation could drift from actual launcher behavior.
- Control by landing docs after launcher contract and basis behavior are implemented and verified.

5. Runtime semantics could be accidentally broadened.
- Control by keeping this task focused on startup ownership and resisting unrelated reload/apply redesign.

Review checkpoints

- After Slice 1: launcher contract and arg surface are stable.
- After Slice 3: catalog/defaulting/inference rules are concrete and deterministic.
- After Slice 4: startup basis synthesis is real and explainable.
- After Slice 6: canonical install surface exists.
- After Slice 7: docs and operator story are coherent.

Definition of done

This task is done when psi can be installed as a `bbin` command named `psi`, started without a user-defined `:psi` alias, launched with startup basis data derived from user/project extension manifests before psi starts, use concise psi-owned manifest entries through launcher defaults and deterministic `:psi/init` inference, and document that launcher-owned startup path as the canonical operator workflow.