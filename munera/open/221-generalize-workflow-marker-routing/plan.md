# Plan — Generalize workflow marker routing

## Approach

Replace the two workflow-specific deterministic routing operations with one generic exact-marker routing operation while preserving the simplification workflow topology and route labels.

Key decisions:

- Runtime code owns only a parameterized exact-marker parser and the registered `workflow/exact-marker-routing` operation.
- Authored workflow EDNs own marker labels (`PROOF_SYNC_ROUTE`, `VALIDATION_CAPTURE_ROUTE`) and allowed route vectors.
- Invalid operation args are validated before parsing and return the exact `:invalid-route-marker-args` diagnostic shape from `design.md`, accumulating all arg errors.
- Marker candidate classification happens across the whole reply before result selection; any multi-candidate reply returns `:ambiguous-route-marker` with all candidate lines and per-candidate classifications.
- The old specialized operation ids are removed without compatibility aliases.
- Behaviour proof comes from generic parser/operation tests plus workflow-loader/content-lock tests proving the authored EDNs now supply the workflow-specific policy.

## Risks

- **Resolved-arg boundary drift:** workflow source EDN uses `{:from ...}` arg references, while the registered operation receives resolved strings at runtime. Tests should distinguish authored EDN shape from live invocation args.
- **Candidate classification regressions:** malformed marker attempts must not be mistaken for prose, but prose merely mentioning a marker label must remain ignored.
- **Ambiguity diagnostics:** existing duplicate-marker tests only checked candidate lines; the new contract also requires complete candidate classifications for mixed candidate cases.
- **Operation surface breakage:** `/operations`, `/operation`, and `psi-tool operation` expose registered operation ids, so registration tests and CHANGELOG must be updated together with removal of old ids.
- **Workflow content-lock churn:** tasks 209, 218, and 220 tests all lock the old operation ids and must be updated without changing workflow topology.

## Slice order

1. **Preflight and source mapping** — inspect current routing code, operation registration, simplification workflow disposition steps, tests, docs, and CHANGELOG references.
2. **Generic exact-marker parser** — make `parse-exact-marker-routing` public and parameterized; add explicit arg validation; implement full candidate classification, precedence, and diagnostics.
3. **Parser test net** — replace proof-sync/validation-specific parser tests with arbitrary marker/route parser tests covering valid, missing, duplicate, malformed, unsupported, prose, whitespace, trailing text, invalid args, and mixed-candidate precedence.
4. **Operation registration cleanup** — register `workflow/exact-marker-routing`, remove specialized route constants/wrappers/registrations, and update built-in operation smoke tests for registration, valid invocation, invalid-arg invocation, and old-id absence.
5. **Workflow EDN migration** — update `reduce-architectural-complexity.edn` and `reduce-incidental-complexity.edn` disposition steps to invoke the generic operation with workflow-owned `:marker-label` and `:allowed-routes` args while preserving `:on` topology.
6. **Workflow-loader/content-lock updates** — update task 209, 218, and 220 workflow-loader tests to assert the generic operation id and exact authored marker policy args for both simplification workflows.
7. **Docs/changelog and verification** — add the required CHANGELOG entry, update docs only if explicit operation ids are mentioned, then run EDN parse checks, relevant focused Scry suites, targeted clj-kondo, paren repair/format checks, and `git diff --check`.
