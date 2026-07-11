# Design follow-up steps

- [ ] Resolve inconsistency in Context/open-question-#4: design describes
      `:gpt-5.5` as belonging to two sets ("`openai-chat-completions-native-model-keys`"
      plus a "second nearby set (lines ~612–623)"), but code has only one such set —
      `openai-chat-completions-native-model-keys` (models.clj 610–623) is the only set
      containing `:gpt-5.5`; the adjacent set (625–635) is `anthropic-json-schema-native-model-keys`
      (Anthropic-only, does not contain `:gpt-5.5`). Correct the design to reference the
      single native-key set and drop the "second key set" premise from open question #4.
