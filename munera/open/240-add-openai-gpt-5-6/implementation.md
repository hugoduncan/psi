# Implementation notes

- architectural review: no feedback — design fits `ai`-component ownership,
  single-source-of-truth catalog, and data-shaped extension (no new branching).
- ambiguity review: no feedback — material ambiguities already captured in the
  design's "Open questions (resolve before plan.md)" section.
- inconsistency review: added 1 design step — design's "second nearby set"
  (open question #4) does not exist; `:gpt-5.5` is in exactly one set
  (`openai-chat-completions-native-model-keys`, models.clj 610–623). The adjacent
  set (625–635) is Anthropic-only.
