# Plan

## Approach

Implement the first workflow structured-output surface as an extension of the existing workflow step-local `:outputs` contract, not as a new singular `:output` field.

The implementation should proceed vertically:

1. Extend workflow authoring/IR schemas so session steps and LLM judges can declare structured output entries in `:outputs`.
2. Normalize structured output entries into one canonical output-spec shape:
   - `:source :session/structured-output` for session steps;
   - `:source :judge/structured-output` for LLM judges;
   - `:mode :structured`;
   - `:schema-id`, `:schema-version`, and Malli `:schema`.
3. Add the canonical runtime structured-output result envelope:
   - `:raw-output` always retained;
   - `:structured-output` containing strategy, status, value or errors.
4. Validate parsed structured values before exposing them through downstream `{:from {:step ... :output ...} :path ...}` references.
5. Make invalid structured output fail fast for this first slice.
6. Record provider strategy in workflow run state; support prompted JSON fallback first if provider-native support is not already available.
7. Add the standard workflow judge review result schema as the first reusable example and test fixture.
8. Update workflow docs and grammar/IR documentation.

## Target files / areas

Likely areas to inspect and update:

- `.psi/workflows/` examples only if a minimal example is useful; do not broadly migrate workflows.
- `components/workflow-loader/` and related workflow grammar/IR validation code.
- `components/workflow-runtime/` structured result storage, output resolution, and failure behavior.
- `components/workflow-judge/` judge execution/result handling.
- `components/workflow-step-session-config/` if child-session request shaping needs provider structured-output options.
- `doc/workflow-grammar.md`, `doc/workflow-ir.md`, and `doc/workflows.md`.
- Focused workflow tests for loader normalization, runtime validation, downstream references, and judge output.

## Decisions

- Use `:outputs` as the authored and normalized structured-output declaration surface.
- Do not introduce top-level `:output {:mode :structured ...}`.
- Use existing source refs for downstream reads: `{:from {:step "step-name" :output :logical-key} :path [...]}`.
- The first standard schema is `:psi.workflow/judge-review-result`; it lands as reusable/tested schema plus docs, with no existing workflow migration required in this slice.
- Minimum validation policy is fail-fast; retry/repair is optional only if naturally supported and explicitly bounded.
- Preserve raw text even for valid structured output.

## Risks

- Provider-native structured output support may not exist uniformly; prompted JSON fallback must be visible in state.
- Runtime output resolution may currently assume text outputs; structured references must avoid prose parsing and fail clearly.
- Judge control flow may be tightly coupled to prose decisions; the first slice should add structured support without forcing all judges to migrate.
- Schema validation can create false confidence; tests and docs must emphasize shape validity, not semantic correctness.

## Verification

Run focused tests first, then broader workflow tests as practical:

- Loader/IR tests for structured `:outputs` normalization.
- Runtime tests for valid structured session output and valid structured judge output.
- Runtime tests for malformed/invalid output fail-fast behavior.
- Reference-resolution tests for valid path, missing path, invalid source output, and non-structured source output.
- Documentation examples remain consistent with grammar/IR docs.

Suggested commands should be finalized during implementation after locating existing aliases/test namespaces.
