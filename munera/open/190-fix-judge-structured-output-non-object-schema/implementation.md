# Implementation notes

2026-05-30 — Design ambiguity review: found two actionable ambiguities: whether JSON `null` must be preserved as `:payload nil`, and whether structured judge retries must re-send the structured-output opts/schema on every retry.

2026-05-30 — Ambiguity follow-up: clarified design that JSON `null` is a valid native structured-output payload and must be preserved as present `:payload nil` without parse error; clarified that structured-output judge retries must pass the original structured-output opts/schema to `execute-judge-turn!` on every retry. Marked both ambiguity design-steps complete.

2026-05-30 — Design inconsistency review: found two actionable inconsistencies: design says `parse-json-object` is still used elsewhere for non-structured-output purposes, but referenced code shows all current call sites are structured-output result extraction sites in scope; design also says the fix is minimal and only provider result-extraction sites change while the same design requires a workflow-judge retry-loop change.

2026-05-30 — Inconsistency follow-up: reconciled the `parse-json-object` constraint with current grep evidence by retaining the helper as an object-only API while no remaining non-helper call sites are expected after the three provider extraction sites move. Reconciled the minimal-fix constraint by defining minimality across both root-cause seams: three provider extraction sites plus only the workflow-judge structured-output validation-failure retry path. Marked both inconsistency design-steps complete.

2026-05-30 — Design ambiguity review: re-read design and referenced structured-output/provider/judge code. No new actionable ambiguities found; existing ambiguity follow-ups for JSON null preservation and structured retry opts retention remain resolved.

2026-05-30 — Ambiguity follow-up execution: re-read `design-steps.md`; there are no newly added unchecked ambiguity follow-up items to execute, so `design.md` needed no change and no blocking design-step remains.

2026-05-30 — Design inconsistency review: found one new actionable inconsistency: the design's enum-schema examples/acceptance use keyword enum values (`[:enum :REPEAT :DONE]` and expected `:DONE`), while the referenced reusable judge-routing schema/tests use string enum values (`[:enum "REPEAT" "DONE"]` and expected `"DONE"`).

2026-05-30 — Inconsistency follow-up: reconciled the non-object judge enum value type with referenced runtime schema/tests. `design.md` now names the judge routing schema as `[:enum "REPEAT" "DONE"]` and expects `:value "DONE"`, matching `psi.workflow-runtime.structured-output-schemas/judge-routing-result-schema` and existing tests. Marked the design-step complete.

2026-05-30 — Design ambiguity review: re-read current design after enum reconciliation plus referenced provider extraction, structured-output envelope, and workflow-judge retry code. No new actionable ambiguities found.

2026-05-30 — Ambiguity follow-up execution: re-read `design-steps.md` after the preceding ambiguity-review pass. No newly added unchecked ambiguity items are present; no `design.md` changes were needed and no blocking ambiguity follow-up remains.

2026-05-30 — Design inconsistency review: found one new actionable inconsistency: `design.md` describes the three provider extraction edits as native structured-output payload sites and frames the intent around provider-native output, but referenced Anthropic/OpenAI code shows the same `parse-json-object` extraction helpers also emit prompted-JSON structured-output results at those call sites.

2026-05-30 — Inconsistency follow-up: reconciled native-only wording with referenced provider code. `design.md` now states that the three provider structured-output extraction sites are shared seams and non-object `:payload` preservation applies to both provider-native and prompted-JSON structured-output results emitted by those sites. Marked the design-step complete.

2026-05-30 — Design ambiguity review: re-read current design after shared provider extraction seam clarification plus referenced provider extraction, structured-output envelope, schema, and workflow-judge retry code. No new actionable ambiguities found; existing ambiguity follow-ups remain resolved and `design-steps.md` needs no new unchecked items.

2026-05-30 — Ambiguity follow-up execution: re-read `design-steps.md` after the preceding ambiguity-review pass. No newly added unchecked ambiguity follow-up items are present; no `design.md` changes were needed and no blocking ambiguity follow-up remains.
