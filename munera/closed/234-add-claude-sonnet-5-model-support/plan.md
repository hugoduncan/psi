# 234 — Plan

## Approach

Implement Claude Sonnet 5 as an additive Anthropic built-in model, following the
catalog/test/docs pattern established by task 223 while resolving this task's
model facts before any catalog edit. The design is stable, but the actual
Claude Sonnet 5 metadata is intentionally not guessed; Slice 1 records the
authoritative facts in `implementation.md` and stops before implementation if
any required value cannot be resolved confidently.

Key decisions:

- Treat the built-in catalog in `components/ai/src/psi/ai/models.clj` as the
  source of truth. Add one new Anthropic entry using the existing model shape;
  do not change defaults, existing ids, or selection ordering except inserting
  the new model in the Anthropic built-in list.
- Use the catalog key that matches existing Anthropic naming conventions after
  discovery confirms the canonical id and display name. The expected key is
  `:sonnet-5`, but implementation must verify rather than assume it.
- Keep the API family `:anthropic-messages` unless provider documentation or the
  live Models API proves a different psi API value is required. If a new
  protocol family is required, stop because that is out of the planned additive
  scope.
- Wire native JSON-Schema structured-output support through
  `anthropic-json-schema-native-model-keys` only if the authoritative facts say
  Claude Sonnet 5 supports it.
- Add non-live coverage in `components/ai/test/psi/ai/model_registry_test.clj`
  for catalog membership, `model-registry/find-model` resolution by canonical
  string id, catalog field values, pricing, and effective structured-output
  capability.
- Extend the existing opt-in live Anthropic Models API coverage in
  `components/ai/test/psi/ai/providers/anthropic_models_api_test.clj` by adding
  Claude Sonnet 5 to the target id set, preserving existing target coverage and
  the current environment-variable gating.
- Update `CHANGELOG.md` with a user-visible `[Unreleased]` `Added` entry, and
  update only README/doc prose that contains definitive supported-model or
  capability enumerations made incomplete by Claude Sonnet 5 support.

Verification target: focused AI/model tests after each relevant slice, then the
project-standard full test/lint commands when practical (`bb test` or focused
standard set plus `clj-kondo --lint src test components`). The live Anthropic
Models API test remains opt-in and is not required for default CI green.

## Risks

- **Unresolved provider facts**: canonical id, output limit, pricing, native
  structured-output support, and adaptive-thinking details may not be available
  from local state. Mitigation: perform discovery first, record sources, and
  stop before implementation if any required fact is uncertain.
- **Provider API mismatch**: Claude Sonnet 5 may require an API/protocol feature
  not represented by existing psi catalog fields. Mitigation: stop rather than
  smuggling a protocol change into an additive model task.
- **Native structured-output over/under-claiming**: adding the key-set entry
  without proof would advertise unsupported behaviour; omitting it when
  supported would underuse provider capability. Mitigation: test the public
  `structured-output/effective-capability` surface against the resolved fact.
- **Documentation churn**: many docs contain illustrative model examples.
  Mitigation: edit only definitive enumerations that would become misleading.
- **Live test gating**: default test runs cannot prove live provider listing or
  retrieval. Mitigation: keep opt-in gating and ensure skip path remains green;
  record whether live verification was run.

## Slice order

1. **Discovery and fact recording** — resolve all required Claude Sonnet 5 facts
   from Anthropic-authoritative sources or explicit user-provided values; record
   the source-backed values in `implementation.md`; stop if any required fact is
   uncertain.
2. **Catalog and native capability wiring** — add the built-in Anthropic catalog
   entry and structured-output key-set membership if supported.
3. **Non-live catalog/registry tests** — prove catalog membership, registry
   resolution, catalog field/pricing values, and effective structured-output
   behaviour.
4. **Opt-in live Models API test** — add Claude Sonnet 5 to the existing gated
   Anthropic Models API list/retrieve target coverage without replacing existing
   targets.
5. **Docs and changelog** — add the user-visible changelog entry and minimal
   definitive documentation updates.
6. **Verification and final coherence** — run focused/full tests and lint as
   practical, check consistency across catalog/tests/docs/changelog, and commit
   the implementation.
