# Design follow-up steps

## Ambiguity review (ψ)

- [ ] Resolve docs scope: decide and state which user-facing docs (if any)
      must be updated for Fable 5. In particular, decide whether
      `doc/configuration.md`'s adaptive-thinking model list (currently names
      Opus 4.7/4.8 for `:xhigh`) must add Claude Fable 5, since Fable 5 is
      also adaptive-thinking. Replace the conditional "if documented" with a
      concrete decision.
- [ ] Resolve changelog obligation: state explicitly whether a CHANGELOG
      `[Unreleased]` entry is required (a new selectable built-in model is
      user-visible per the changelog protocol) or genuinely optional, instead
      of leaving it conditional on "if model availability is documented".
- [ ] Disambiguate the test extension: specify the intended structure for
      asserting `"claude-fable-5"` in `anthropic_models_api_test.clj` —
      whether opus-4.8 assertions are retained, whether the target ids are
      parameterized over a set, or whether parallel fable-5 deftests are
      added. The current test hardcodes a single `target-model-id` and two
      opus-named deftests.
