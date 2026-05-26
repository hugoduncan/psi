# 183 implementation notes

## 2026-05-25 ambiguity review pass 1

Reviewed design.md against actual code in `init.clj` and `child_session_state.clj`.

Field counts and per-path classification (common 17, prompt-state 4, model-identity 2) match the code exactly. The three-constant composition model is sound.

Found three actionable ambiguities:

1. **Child-session common-core inheritance gap undocumented**: Design says child-session "constructs fields explicitly" and proposes a docstring referencing the classification. But 9 of 17 common-core fields (`:prompt-templates`, `:extensions`, `:auto-retry-enabled`, `:auto-compaction-enabled`, `:scoped-models`, `:tool-output-overrides`, `:ui-type`, `:context-tokens`, `:context-window`) are NOT inherited from parent in child-session — they silently get `initial-session` defaults. The scope item "Document the child-session path's relationship" doesn't say whether this divergence is intentional-to-preserve or a gap-to-flag. The shared vocabulary must account for this or the classification docstring will be misleading.

2. **`:nucleus-prelude-override` dual role**: Classified as "Preference" in common core, but in child-session it feeds into `default-child-system-prompt-build-opts` as a prompt-building input alongside prompt-state fields. Design doesn't distinguish between "carried as-is" preferences and "consumed during prompt derivation" inputs. This matters for the classification comment.

3. **"Authoritative or derived" acceptance criterion undefined**: AC says "every field documented as authoritative or derived" but the design's classification only uses role categories. No definition of authoritative vs derived is given. E.g., `:context-tokens`/`:context-window` are arguably runtime-derived telemetry (set after model resolution), not authoritative session config, yet they're inherited and classified alongside authoritative preferences.

## 2026-05-25 ambiguity follow-up — resolving design-steps

### Step 1: Child-session common-core inheritance gap

**Resolution: intentional-to-preserve.** Verified in code: child sessions are ephemeral agent-spawned sessions. The 9 non-inherited fields fall into clear categories:
- **Not meaningful for child sessions**: `:extensions` (child doesn't register extensions), `:prompt-templates` (child doesn't offer user-invokable prompts), `:ui-type` (agent-driven, always `:console`), `:scoped-models` (child uses explicit model from opts/parent), `:tool-output-overrides` (child uses defaults)
- **Policy resets**: `:auto-retry-enabled` (child uses config default), `:auto-compaction-enabled` (ephemeral, no compaction needed)
- **Runtime-derived, starts fresh**: `:context-tokens`, `:context-window` (set after model resolution, no point inheriting stale values)

Updated design.md: added "Child-session inheritance divergence" section documenting all 8 inherited and 9 intentionally-defaulted fields with rationale. Updated scope and AC to require this documentation.

### Step 2: `:nucleus-prelude-override` dual classification

**Resolution: annotated.** In init.clj paths, `:nucleus-prelude-override` is carried as-is via `select-keys` — it's a preference field. In child-session, it's read from `parent-sd` inside `default-child-system-prompt-build-opts` and consumed during prompt derivation — it flows into the child's `:system-prompt-build-opts` but is NOT set as a standalone field on the child session-data map. This is consistent: the child doesn't need the raw preference because it has already been consumed into the derived prompt state.

Updated design.md: annotated `:nucleus-prelude-override` in the common-core classification and in the shared constant docstring. Added explicit note in child-session documentation requirements.

### Step 3: "Authoritative vs derived" AC

**Resolution: integrated into role-category classification.** Rather than adding a separate authoritative/derived axis, annotated each role category as authoritative or runtime-derived directly in the classification. Capability membership, preferences, and UI are authoritative (user/config-set). Telemetry/context is runtime-derived (set after model resolution). Updated the AC to reference role-category groups with authoritative/derived annotations, replacing the undefined standalone criterion.
