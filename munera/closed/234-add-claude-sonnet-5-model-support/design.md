# 234 — Add built-in support for Claude Sonnet 5

## Intent

Add first-class built-in support for Anthropic's **Claude Sonnet 5** model so a
user can select and run it through psi like any other built-in Anthropic model,
with correct provider metadata, capability flags, pricing, structured-output
behaviour, tests, changelog, and any required user-facing documentation updates.

## Problem / context

psi's built-in model catalog is the source of truth for selectable models
(`components/ai/src/psi/ai/models.clj`), with lookup and selection behaviour
provided through the model registry. Built-in model entries encode the provider
API family, canonical provider id, display name, capability flags, context and
output limits, and pricing. Some capabilities, including native JSON-Schema
structured output support, are also wired through catalog-level model-key sets in
`models.clj`.

The Anthropic provider already has a Messages API integration and existing
built-in Claude models. A previous additive model task (`223-add-fable-5-model-support`)
established the expected shape for adding a new Anthropic model: catalog entry,
structured-output key-set membership when supported, opt-in live Anthropic
Models API verification, changelog entry, and targeted documentation updates for
any definitive capability enumerations.

Claude Sonnet 5 is not yet represented in the built-in catalog. Users therefore
cannot select it by the normal built-in model paths even if the provider makes it
available.

## Scope

In scope:

- Add Claude Sonnet 5 as a built-in Anthropic model in the model catalog.
- Use the canonical model id, display name, context window, output limit,
  modality support, thinking/reasoning support, structured-output support, and
  pricing from authoritative sources.
- Wire any native structured-output support through the existing native-capability
  mechanism if the provider reports/supports it.
- Ensure the model resolves through `model-registry/find-model` and normal model
  selection/cycling paths.
- Extend the existing opt-in Anthropic Models API live verification test to
  assert that Claude Sonnet 5's canonical id is listed and retrievable.
- Add focused non-live tests proving the catalog/registry/capability behaviour.
- Add a `CHANGELOG.md` `[Unreleased]` → `Added` entry because a new selectable
  built-in model is user-visible.
- Update user-facing docs only where existing prose makes a definitive capability
  enumeration that becomes incomplete or misleading once Claude Sonnet 5 is
  supported.

Out of scope:

- Changing the default model.
- Adding a new provider transport or Anthropic protocol family unless Anthropic
  requires one and no existing psi API value can represent the model.
- Custom user/project `models.edn` behaviour.
- Adding new catalog fields for provider capabilities that psi does not currently
  model, unless separately designed.

## Acceptance criteria

- Claude Sonnet 5 appears in `all-models` with the agreed built-in model key,
  canonical provider id, provider/API fields, capability flags, context/output
  limits, and pricing.
- `model-registry/find-model` resolves Claude Sonnet 5 by its canonical id and it
  participates in the normal built-in model selection path.
- Structured-output capability resolution is correct for Claude Sonnet 5: native
  JSON-Schema support is enabled if and only if the authoritative model facts say
  it is supported.
- The opt-in Anthropic Models API test checks Claude Sonnet 5 alongside the
  existing target Anthropic ids rather than replacing existing coverage.
- Focused non-live tests cover the catalog entry, registry resolution, and any
  structured-output/native-capability wiring.
- `CHANGELOG.md` includes a user-facing `[Unreleased]` `Added` entry for Claude
  Sonnet 5.
- Any definitive capability enumeration in `README.md` or `doc/` that would be
  made incomplete by this support is updated; illustrative examples that remain
  true are not churned.
- `bb test` (or the project-standard focused test set plus full test command when
  practical) is green and `clj-kondo --lint src test components` or the
  repository-standard lint command is clean.

## Required discovery before planning/implementation

Before implementation, resolve and record the authoritative Claude Sonnet 5 facts
in this task or its plan:

- Canonical Anthropic model id (expected shape: `claude-sonnet-5`, but do not
  assume without verification).
- Display name.
- Existing psi catalog key to use (for example `:sonnet-5`, if consistent with
  naming conventions).
- Anthropic API family (`:anthropic-messages` unless evidence requires a new
  value).
- Context window and maximum output tokens.
- Text/image/document modality support, limited to fields psi can currently
  represent.
- Reasoning/thinking support and whether it is adaptive.
- Mid-conversation system-message support.
- Native structured-output / JSON-Schema support.
- Pricing: input, output, cache read, and cache write costs in the same units and
  ratio conventions as existing Anthropic built-ins.

Preferred discovery sources, in order:

1. Live Anthropic Models API or other provider-authoritative endpoint available
   to the session.
2. Official Anthropic model/pricing documentation.
3. Explicit user-provided values, when provider metadata is unavailable or
   pricing is not exposed by the API.

If any required fact cannot be resolved confidently, stop before implementation
and ask for the missing value rather than guessing.

## Design constraints

- The change is additive: no existing model id, built-in key, default, or
  selection ordering changes except those necessary to include the new model.
- The catalog remains the single source of truth for built-in model metadata;
  tests should assert derived behaviour rather than duplicating independent model
  lists.
- Live provider tests remain opt-in and gated by the existing Anthropic live-test
  environment variables.
- Documentation changes are factual and minimal: update definitive support lists,
  not every illustrative model mention.
