# 183 implementation notes

## 2026-05-25 ambiguity review pass 1

Reviewed design.md against actual code in `init.clj` and `child_session_state.clj`.

Field counts and per-path classification (common 17, prompt-state 4, model-identity 2) match the code exactly. The three-constant composition model is sound.

Found three actionable ambiguities:

1. **Child-session common-core inheritance gap undocumented**: Design says child-session "constructs fields explicitly" and proposes a docstring referencing the classification. But 9 of 17 common-core fields (`:prompt-templates`, `:extensions`, `:auto-retry-enabled`, `:auto-compaction-enabled`, `:scoped-models`, `:tool-output-overrides`, `:ui-type`, `:context-tokens`, `:context-window`) are NOT inherited from parent in child-session — they silently get `initial-session` defaults. The scope item "Document the child-session path's relationship" doesn't say whether this divergence is intentional-to-preserve or a gap-to-flag. The shared vocabulary must account for this or the classification docstring will be misleading.

2. **`:nucleus-prelude-override` dual role**: Classified as "Preference" in common core, but in child-session it feeds into `default-child-system-prompt-build-opts` as a prompt-building input alongside prompt-state fields. Design doesn't distinguish between "carried as-is" preferences and "consumed during prompt derivation" inputs. This matters for the classification comment.

3. **"Authoritative or derived" acceptance criterion undefined**: AC says "every field documented as authoritative or derived" but the design's classification only uses role categories. No definition of authoritative vs derived is given. E.g., `:context-tokens`/`:context-window` are arguably runtime-derived telemetry (set after model resolution), not authoritative session config, yet they're inherited and classified alongside authoritative preferences.
