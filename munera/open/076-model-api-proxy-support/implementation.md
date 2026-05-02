Issue provenance
- GitHub issue: #27
- URL: https://github.com/hugoduncan/psi/issues/27

2026-05-01/02 — refinement notes
- created task `076-model-api-proxy-support`
- anchored design on issue #27 and the existing triage comment intent/scope
- made configuration-surface choice an explicit required design decision so implementation will not proceed with ambiguous env-vs-config semantics
- made request-path applicability explicit because proxy support is only meaningful if the canonical outbound model transport boundary is named
- constrained scope away from general enterprise networking and unrelated provider changes
- declared ambiguity status clear for design-level refinement: no remaining ambiguity about the task’s intent, scope, acceptance surface, or required design decisions
