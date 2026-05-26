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

## 2026-05-26 inconsistency review pass 1

Reviewed design.md, design-steps.md, implementation.md against actual code in `init.clj` and `child_session_state.clj`. Verified field counts, set compositions, and cross-artifact consistency.

Found two actionable inconsistencies:

1. **Child-session inherited field count mismatch (header says 8, body lists 7)**: The "Inherited from parent" section header says "(8 fields)" but lists only 7 bullet points. The `:nucleus-prelude-override` note explicitly states it is "NOT set as a standalone field on the child session data map" — so it is consumed, not inherited as a field. Either the header should say "7 fields" (and the "Not inherited" section should become 10 fields, adding `:nucleus-prelude-override`), or `:nucleus-prelude-override` should be promoted to a bullet point if it counts as inherited. The same "8 of 17" count appears in scope (line 40) and child-session documentation requirement (line 170) — all three must be aligned.

2. **Child-session documentation scope covers only common-core, ignores prompt-state and model-identity**: Scope says "Document the child-session path's relationship to the shared vocabulary: which common-core fields it inherits from parent (8 of 17)." The child-session documentation requirement (lines 170–172) only asks to list common-core inherited/defaulted fields. But child-session also inherits/derives all 4 prompt-state fields (`:base-system-prompt`, `:system-prompt`, `:system-prompt-build-opts`, `:prompt-component-selection` — via `derive-child-prompt-state`) and both model-identity fields (`:model`, `:thinking-level` — via explicit opts with parent fallback). The documentation requirement is incomplete: it should cover the child-session's relationship to all three constant groups, not just common-core.

## 2026-05-26 inconsistency follow-up — resolving design-steps

### Step 4: Fix child-session inherited field count

**Resolution: corrected to 7 inherited / 10 not-inherited.** Verified in code: `child-session-base-state*` explicitly sets 7 common-core fields from parent (`:skill-ids`, `:tool-ids`, `:prompt-contribution-ids`, `:prompt-mode`, `:developer-prompt`, `:developer-prompt-source`, `:cache-breakpoints`). `:nucleus-prelude-override` is consumed inside `default-child-system-prompt-build-opts` during prompt derivation but is NOT set as a standalone field — moved to the "not inherited" list. Updated all three locations in design.md: scope (line 40), inheritance divergence section headers, and child-session documentation requirement.

### Step 5: Expand child-session documentation scope

**Resolution: expanded to all three constant groups.** Added prompt-state and model-identity subsections to the child-session inheritance divergence section, documenting how each field is derived or resolved. Updated scope to add a bullet for prompt-state and model-identity coverage. Updated child-session documentation requirement to include items 4 and 5 covering these groups. Updated acceptance criterion to require documentation of all three constant groups.

## 2026-05-25 ambiguity review pass 2

Reviewed design.md, plan.md, steps.md, design-steps.md, and implementation.md against actual code in `init.clj` and `child_session_state.clj`. Verified field counts, set compositions, constant group boundaries, child-session inheritance paths, and cross-artifact consistency.

No new actionable ambiguities found:
- Field counts (common 17, prompt-state 4, model-identity 2) match code `select-keys` vectors exactly; compositions produce correct totals (new=23, resume=21, fork=19).
- Child-session 7-inherited/10-defaulted classification verified against `child-session-base-state*` code; `:nucleus-prelude-override` correctly classified as consumed-not-inherited.
- No overlaps between the three constant groups; `into`+`concat` composition is sound.
- All 9 steps cover all 7 acceptance criteria; plan decisions (constants in `init.clj`, `^:private`, documentation-only for child-session) align with design.
- All 5 prior design-steps resolved and reflected in current design.md.

## 2026-05-26 ambiguity follow-up pass 2 — no-op

Preloaded ambiguity review pass 2 found no new actionable ambiguities. All 5 design-steps in design-steps.md were already checked from prior follow-up passes. No new unchecked items were added. No task artifact changes required.

## 2026-05-26 inconsistency review pass 2

Reviewed design.md, plan.md, steps.md, design-steps.md, and implementation.md against actual code in `init.clj` and `child_session_state.clj`. Verified all field counts, set compositions, constant group boundaries, child-session inheritance paths, scope/AC/plan/steps alignment, and cross-artifact consistency.

Found one actionable inconsistency:

1. **Child-session model-identity header claims "both inherited with parent fallback" but `:thinking-level` defaults to `:off`**: The scope line says `model-identity-fields (both inherited with parent fallback)` and the child-session section header says `**Model-identity fields** (both inherited with parent fallback):`. But the body correctly documents `:thinking-level — (or thinking-level :off) (explicit opts, not direct parent inheritance)` and documentation requirement #5 correctly says "`:model` falls back to parent, `:thinking-level` defaults to `:off`". The header/scope parenthetical contradicts the body and the documentation requirement. Only `:model` uses parent fallback; `:thinking-level` uses `:off` as default. Two locations need correction: scope bullet and child-session model-identity section header.

## 2026-05-26 inconsistency follow-up pass 2 — resolving design-steps

### Step 6: Fix child-session model-identity "both inherited with parent fallback" wording

**Resolution: corrected both locations.** Changed scope bullet parenthetical from `(both inherited with parent fallback)` to `(`:model` falls back to parent, `:thinking-level` defaults to `:off`)`. Changed child-session section header from `**Model-identity fields** (both inherited with parent fallback):` to `**Model-identity fields** (`:model` falls back to parent, `:thinking-level` defaults to `:off`):`. Both now match the body text and documentation requirement #5.
