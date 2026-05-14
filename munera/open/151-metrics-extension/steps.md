# Steps — 151 Metrics Extension

## Implementation

- [ ] Create `extensions/metrics/deps.edn` with malli dep and test alias
- [ ] Create `psi.metrics.schema` — malli schemas for metrics data shape
- [ ] Create `psi.metrics.counters` — pure counter update functions (increment tool, increment error, add token delta)
- [ ] Create `psi.metrics.persistence` — load/save EDN with atomic writes, schema validation on load
- [ ] Create `psi.metrics.extension` — init, event handlers, operation handler, command handler
- [ ] Wire into `extensions/deps.edn` (add `psi/metrics` dep and test paths)
- [ ] Add catalog entry in `extension_installs.clj` (`psi-owned-extension-catalog`)
- [ ] Add `psi/metrics {}` to `.psi/extensions.edn`

## Tests

- [ ] `psi.metrics.schema-test` — schema validation for valid/invalid metrics maps
- [ ] `psi.metrics.counters-test` — pure counter increment functions
- [ ] `psi.metrics.persistence-test` — round-trip load/save, corrupt file handling, missing directory creation
- [ ] `psi.metrics.extension-test` — init registration, event handler behavior, operation handler, command rendering

## Verification

- [ ] All tests green
- [ ] Lint clean (`clj-kondo --lint extensions/metrics/src`)
- [ ] Schema conformance validated on load and in operation response
