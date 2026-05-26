# Steps — 151 Metrics Extension

## Implementation

- [x] Create `extensions/metrics/deps.edn` with malli dep and test alias
- [x] Create `psi.metrics.schema` — malli schemas for metrics data shape
- [x] Create `psi.metrics.counters` — pure counter update functions (increment tool, increment error, add per-model token delta)
- [x] Create `psi.metrics.persistence` — load/save EDN with atomic writes, write-coalescing via `writing?` CAS gate, schema validation on load
- [x] Create `psi.metrics.extension` — init, event handlers, operation handler, command handler
- [x] Add catalog entry in `extension_installs.clj` (`psi-owned-extension-catalog`)
- [x] Add `psi/metrics {}` to `.psi/extensions.edn`

## Tests

- [x] `psi.metrics.schema-test` — schema validation for valid/invalid metrics maps
- [x] `psi.metrics.counters-test` — pure counter increment functions, per-model token delta
- [x] `psi.metrics.persistence-test` — round-trip load/save, corrupt file handling, missing directory creation, write-coalescing under concurrent mutations
- [x] `psi.metrics.extension-test` — init registration, event handler behavior, per-model token accumulation, operation handler, command rendering

## Verification

- [x] All tests green (50 tests, 87 assertions, 0 failures)
- [x] Lint clean (`clj-kondo --lint extensions/metrics/src`)
- [x] Schema conformance validated on load and in operation response
