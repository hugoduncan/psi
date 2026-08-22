# Design steps

- [x] Clarify the Design prose "The global footer remains as a fallback for commands that do not specify one." It contradicts the Rules, Acceptance, and example output, which all state the global footer is a single end-trailer and a command without `:footer` gets no per-section footer. Rewrite the sentence so it does not imply the global footer is emitted per-section as a substitute for a missing `:footer`.
- [x] Specify behavior for an empty-string `:footer` (e.g. `:footer ""`): is it treated as absent (no per-section footer emitted) or rendered as an empty line? The design only specifies "when absent."
