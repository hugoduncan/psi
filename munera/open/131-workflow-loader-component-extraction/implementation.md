2026-05-07

Task created from post-123/124/125/126/127/128/129/130 workflow extraction review.

Creation rationale:
- after the runtime, judge, step session-config, and step materialization extractions, the strongest remaining workflow-shaped area is authored-definition loading
- loader ownership is distinct from registry ownership and runtime ownership: it acquires and prepares definitions before they are stored or executed
- the intent is to give workflow discovery/loading/authored-definition preparation a more precise lower component home without folding it into registry, runtime, or higher `agent-session`/tool entrypoints

Initial boundary notes:
- likely owned responsibilities: workflow file discovery/loading, authored-definition ingestion, load-time preparation/normalization, workflow-file metadata loading context where applicable, and downstream handoff to registry/runtime consumers
- expected non-goal: do not silently absorb registry storage/query ownership or runtime execution ownership
- expected review point: current loader behavior may be spread across higher tool/entrypoint surfaces and lower helper namespaces, so implementation must identify the true authoritative loader owner rather than assuming one file maps one-to-one to the component

Architectural decisions agreed during refinement:
- choose component name `workflow-loader` and namespace family `psi.workflow-loader.*` unless implementation proves a materially narrower or broader owner is required
- treat the extraction as one coherent authored-definition loading owner with a small internal split for discovery, parsing, compilation, and authoring-preparation roles rather than as an arbitrary pre-runtime bundle
- expect workflow-file authoring compilation helpers to move with the loader when they are part of load-time preparation of canonical prepared definitions
- expected concrete review set includes `workflow-file-loader`, `workflow-file-parser`, `workflow-file-compiler`, `workflow-file-authoring-errors`, `workflow-file-authoring-session`, `workflow-file-authoring-preload`, `workflow-file-authoring-routing`, and `workflow-file-authoring-resolution`, each with an explicit move/stay/delete disposition
- prefer one small canonical lower loader API centered on `load-workflow-definitions`, while allowing parser/compiler/discovery/validation seams such as `validate-step-references`, `validate-no-name-collisions`, and `validate-judge-routing` to remain public if direct lower tests or extension callers intentionally need them
- preserve current caller-visible behavior at least for `load-workflow-definitions`, `scan-directory`, direct parser/compiler caller contracts, directory precedence semantics, duplicate-resolution behavior, source-path attachment behavior, and load-time error/warning shaping unless justified replacements are recorded and all affected callers are rewired
- prefer the downstream handoff artifact to be canonical prepared workflow definitions plus loader-owned metadata/diagnostics rather than raw authored sources or registry-owned state
- registration remains outside loader authoritative ownership even when higher callers immediately consume loader results for registry operations
- prefer direct rewiring and removal of old mixed `psi.agent-session.workflow-file-*` owners rather than long-lived forwarding façades
- preferred final state is removal of `workflow-file-authoring-resolution` after rewiring; if retained during implementation, treat it only as a short-lived move-sequencing façade
