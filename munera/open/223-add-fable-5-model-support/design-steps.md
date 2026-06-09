# Design follow-up steps

## Ambiguity review (ψ)

- [x] Resolve docs scope: decide and state which user-facing docs (if any)
      must be updated for Fable 5. In particular, decide whether
      `doc/configuration.md`'s adaptive-thinking model list (currently names
      Opus 4.7/4.8 for `:xhigh`) must add Claude Fable 5, since Fable 5 is
      also adaptive-thinking. Replace the conditional "if documented" with a
      concrete decision.
      → Resolved: no prose doc changes required; existing mentions are
      illustrative "such as" examples, catalog SoT is `models.clj`. See
      design.md "Resolved ambiguities → Docs scope".
- [x] Resolve changelog obligation: state explicitly whether a CHANGELOG
      `[Unreleased]` entry is required (a new selectable built-in model is
      user-visible per the changelog protocol) or genuinely optional, instead
      of leaving it conditional on "if model availability is documented".
      → Resolved: mandatory `[Unreleased] → Added` entry; draft text in
      design.md "Resolved ambiguities → Changelog obligation".
- [x] Disambiguate the test extension: specify the intended structure for
      asserting `"claude-fable-5"` in `anthropic_models_api_test.clj` —
      whether opus-4.8 assertions are retained, whether the target ids are
      parameterized over a set, or whether parallel fable-5 deftests are
      added. The current test hardcodes a single `target-model-id` and two
      opus-named deftests.
      → Resolved: parameterize over a set `target-model-ids`, retain Opus 4.8,
      `doseq`/`testing` per id, no parallel deftests. See design.md "Resolved
      ambiguities → Test extension shape".

## Inconsistency review (ψ)

- [x] Fix dangling cross-reference in Scope "Out of scope": the parenthetical
      "unless Fable 5 requires a genuinely new protocol — see open questions"
      points to a non-existent "Open questions" section, and "Resolved facts"
      already settles `:api :anthropic-messages` (an existing protocol). Remove
      or rephrase the parenthetical so it no longer references open questions
      and is consistent with the resolved no-new-protocol fact.
      → Resolved: rephrased the "Out of scope" bullet to drop the open-questions
      reference and instead state that resolved facts fix
      `:api :anthropic-messages`, so no new protocol work is in scope.
- [x] Resolve the `pdf_input` capability fact: "Resolved facts" lists
      `pdf_input` supported but it maps to no catalog field (none exists in
      `components/ai/src`) and is absent from the "Final catalog entry" and
      acceptance criteria. State explicitly that pdf has no catalog
      representation and is intentionally omitted (or drop it from the facts),
      so the discovered capabilities and the target entry agree.
      → Resolved: design.md "Resolved facts → Capabilities" now states pdf is
      provider-supported but has no catalog field (verified: no pdf/document
      refs in `components/ai/src`), so it is intentionally omitted from the
      entry and acceptance criteria.
- [ ] Fix the `doc/extension-api.md` docs-scope inconsistency: design "Resolved
      ambiguities → Docs scope" characterizes `doc/extension-api.md` as a
      "single-model worked example of `/model` selection and mid-system-message
      support, not catalog inventory" to justify no prose-doc changes. But the
      referenced text (`doc/extension-api.md:217-220`) is a capability-support
      *enumeration*: "Support is true for Claude Opus 4.8 and for OpenAI
      chat-completions models … older Anthropic models are reported
      unsupported." Fable 5 sets
      `:supports-mid-conversation-system-messages true` and will report
      supported, so the enumeration becomes incomplete/misleading. Either
      correct the characterization in the design and decide explicitly whether
      `doc/extension-api.md` must add Fable 5 to the mid-system-message support
      enumeration, or document why the enumeration stays accurate without it.
      (Previously raised in the 2nd-pass inconsistency review, which referenced
      design-steps.md but added no follow-up step.)
