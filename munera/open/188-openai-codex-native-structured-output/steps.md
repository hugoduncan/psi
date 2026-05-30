# Slice 1 — Baseline evidence

- [x] Record in `implementation.md` the current authoritative Codex structured-output boundary, including fallback-only capability and the transport-resolved `gpt-5.5` runtime case.
- [x] Inspect `components/ai/src/psi/ai/structured_output.clj` and capture the current Codex capability/mechanism definitions relevant to this task.
- [x] Inspect `components/ai/src/psi/ai/model_registry.clj` and `components/ai/src/psi/ai/models.clj` to document how static model entries and OAuth-resolved runtime routing currently determine Codex structured-output behavior.
- [x] Inspect `components/ai/src/psi/ai/providers/openai/codex_responses.clj` and adjacent provider seams to inventory current request shaping, streaming extraction, result handoff, and non-streaming support.
- [x] Inspect the focused current tests in `components/ai/test/psi/ai/model_registry_test.clj` and `components/ai/test/psi/ai/providers/openai_structured_output_test.clj` to confirm the present fallback-only proofs.

# Slice 2 — Capability discovery

- [x] Determine whether `https://chatgpt.com/backend-api/codex/responses` supports native schema-constrained structured output for the transport Psi uses.
- [x] Run a guarded live probe that exercises a minimal schema contract against the ChatGPT/Codex endpoint and captures the observed request/response evidence.
- [x] Record the live evidence in `implementation.md`, including the accepted request shape and rejected alternatives.
- [x] Decide, based on evidence, that the finalized Codex capability outcome is native structured-output support for streaming requests.
- [x] Update `design.md` or task notes with the exact Codex request/response contract before code changes that depend on it.

# Slice 3 — Capability implementation

- [x] Add the finalized Codex structured-output capability/mechanism declaration without conflating it with the existing Chat Completions native mechanism unless the protocol is proven identical.
- [x] Update Codex structured-output strategy selection and request construction to send the exact verified native schema fields under Responses-style `text.format`.
- [x] Update Codex streaming extraction to emit structured-output strategy and result data from the native response surface.
- [x] Keep Codex non-streaming `:execute` unchanged unless separate verification establishes a supported non-streaming structured-output contract.
- [x] Ensure transport-resolved runtime models, including OAuth-routed `gpt-5.5`, receive the correct finalized Codex structured-output capability.
- [x] Verify the existing OpenAI Chat Completions native structured-output path remains distinct and unchanged except for any necessary shared helper extension.

# Slice 4 — Focused proof

- [x] Add or update model-registry tests covering the finalized Codex capability assignment for transport-resolved runtime models, including OAuth-routed `gpt-5.5`.
- [x] Add or update Codex provider tests covering structured-output request shaping for the finalized native streaming capability outcome.
- [x] Add or update Codex provider tests covering structured-output event extraction and top-level result surfaces for the finalized native streaming capability outcome.
- [x] Fix Codex native structured-output result extraction so valid non-object JSON payloads (for example string enum schemas such as `"DONE"`) are not reported as parse errors and have coherent top-level structured-output metadata/proof.
- [x] Add focused tests proving Chat Completions-style `response_format` is not used on the Codex endpoint while Responses-style `text.format` is.
- [ ] If non-streaming Codex support is later implemented, add focused tests for the `:execute` structured-output contract.
- [x] Add a focused workflow or turn-runtime regression proving loop-control schemas remain intact and coherent on the finalized Codex capability path.
- [x] Run focused verification for the touched Codex/model/workflow seams and record the results in `implementation.md`.

# Slice 5 — Documentation and closeout

- [x] Update `implementation.md` with the final capability decision, evidence, code/test changes, and verification results.
- [x] Update any user-facing or internal AI documentation only if the finalized Codex capability boundary is discoverable and relevant beyond task-local notes.
- [x] Review `plan.md` and `steps.md` for consistency with the final design intent before closing the task.
- [x] Remove the untracked live-probe scratch file `.tmp-codex-schema-probe.clj` before close; keep the recorded evidence in `implementation.md` rather than a credential-access helper in the worktree.
