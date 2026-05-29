# Slice 1 — Baseline evidence

- [x] Record in `implementation.md` the current authoritative Codex structured-output boundary, including fallback-only capability and the transport-resolved `gpt-5.5` runtime case.
- [x] Inspect `components/ai/src/psi/ai/structured_output.clj` and capture the current Codex capability/mechanism definitions relevant to this task.
- [x] Inspect `components/ai/src/psi/ai/model_registry.clj` and `components/ai/src/psi/ai/models.clj` to document how static model entries and OAuth-resolved runtime routing currently determine Codex structured-output behavior.
- [x] Inspect `components/ai/src/psi/ai/providers/openai/codex_responses.clj` and adjacent provider seams to inventory current request shaping, streaming extraction, result handoff, and non-streaming support.
- [x] Inspect the focused current tests in `components/ai/test/psi/ai/model_registry_test.clj` and `components/ai/test/psi/ai/providers/openai_structured_output_test.clj` to confirm the present fallback-only proofs.

# Slice 2 — Capability discovery

- [ ] Determine whether `https://chatgpt.com/backend-api/codex/responses` supports native schema-constrained structured output for the transport Psi uses.
- [ ] If live probing is feasible, add or run a guarded probe that exercises a minimal schema contract against the ChatGPT/Codex endpoint and captures the observed request/response evidence.
- [x] If live probing is not feasible, gather the strongest available code-path or fixture evidence and record the limitation explicitly in `implementation.md`.
- [ ] Decide, based on evidence, whether the finalized Codex capability outcome is native support or explicit fallback-only.
- [ ] If native support is verified and the exact contract is newly discovered, update `design.md` or task notes with the exact Codex request/response contract before code changes that depend on it.

# Slice 3 — Capability implementation

- [ ] If native support is verified, add the finalized Codex structured-output capability/mechanism declaration without conflating it with the existing Chat Completions native mechanism unless the protocol is proven identical.
- [ ] If native support is verified, update Codex structured-output strategy selection and request construction to send the exact verified native schema fields.
- [ ] If native support is verified, update Codex streaming extraction to emit structured-output strategy and result data from the native response surface.
- [ ] If native support is verified and warranted by the verified backend contract, implement Codex non-streaming `:execute` support for structured output.
- [ ] If native support is not verified, keep Codex explicitly fallback-only and tighten the capability/model/provider code so that fallback status is evidence-backed and unambiguous.
- [ ] Ensure transport-resolved runtime models, including OAuth-routed `gpt-5.5`, receive the correct finalized Codex structured-output capability.
- [ ] Verify the existing OpenAI Chat Completions native structured-output path remains distinct and unchanged except for any necessary shared helper extension.

# Slice 4 — Focused proof

- [ ] Add or update model-registry tests covering the finalized Codex capability assignment for transport-resolved runtime models, including OAuth-routed `gpt-5.5`.
- [ ] Add or update Codex provider tests covering structured-output request shaping for the finalized capability outcome.
- [ ] Add or update Codex provider tests covering structured-output event extraction and top-level result surfaces for the finalized capability outcome.
- [ ] If non-streaming Codex support is implemented, add focused tests for the `:execute` structured-output contract.
- [ ] Add a focused workflow or turn-runtime regression proving loop-control schemas remain intact and coherent on the finalized Codex capability path.
- [ ] Run focused verification for the touched Codex/model/workflow seams and record the results in `implementation.md`.

# Slice 5 — Documentation and closeout

- [ ] Update `implementation.md` with the final capability decision, evidence, code/test changes, and verification results.
- [ ] Update any user-facing or internal AI documentation only if the finalized Codex capability boundary is discoverable and relevant beyond task-local notes.
- [ ] Review `plan.md` and `steps.md` for consistency with the final design intent before closing the task.
