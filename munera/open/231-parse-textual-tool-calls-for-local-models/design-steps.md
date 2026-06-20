# Design follow-up steps

- [ ] ARCHITECTURE: Clarify that textual tool-call capability lookup/normalization must not introduce a `turn-runtime` → `agent-session` component dependency; use the already-resolved turn/prepared-request model or place pure capability/parser helpers in a lower-level component so the component graph stays acyclic.
- [ ] ARCHITECTURE: Clarify the normalization boundary so textual tool-call recovery is applied once for both streaming final assembly and non-streaming assistant responses, rather than living only in the streaming accumulator or being duplicated across execution paths.
