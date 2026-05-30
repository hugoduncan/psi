# Design follow-up steps — 190-add-opus-4-8-model

## From design review pass 2026-05-30

- [ ] **Resolve `/speed` scope syntax** — Decide: does `/speed` accept an optional scope token (`/speed fast project`)? If yes, update step 8 to document the three-token form and add an acceptance criterion for scoped persistence. If no, remove the `/speed fast project` example from step 11.

- [ ] **Clarify `:runtime/agent-set-speed-mode` effect** — Either (a) remove it from the design (speed-mode is read from session-data via `session->request-options`, so no agent-core sync is needed), or (b) add the missing `execute-effect!` handler in `dispatch_effects.clj` and `agent/set-speed-mode-in!` in `agent-core/core.clj` to the scope.

- [ ] **Specify Anthropic beta header for `speed: "fast"`** — Confirm with Anthropic docs whether the fast-mode research preview requires an `anthropic-beta` header string. If yes, add the beta constant and wire it into `beta-header` in `providers/anthropic.clj`; update step 6 accordingly.

- [ ] **Clarify `:fast` cross-provider semantics** — Document the intended user-facing meaning of `/speed fast` given the provider asymmetry (Anthropic = faster+expensive, OpenAI = lower-priority+cheaper). Either rename the value to reflect the actual shared abstraction, or add a provider-specific note to the `/speed` help text.

- [ ] **Specify `:xhigh` → `"highest"` fallback behaviour** — Choose one: (a) always send `"highest"` and let the 400 surface as a user-visible error (remove "with warning" from the table), or (b) design an explicit fallback that catches the 400 and retries with `"high"` while emitting a warning. Update the effort table and architecture section to be consistent.

- [ ] **Specify `:mid-system` compaction preservation mechanism** — Clarify whether `entry->message` in `compaction.clj` must be extended to handle `:mid-system` entries (returning a `{:role "system" ...}` message), or whether preservation is achieved via a different path. Update step 11 with the concrete change required.

- [ ] **Document `journal->provider-messages` ↔ `append-msg` contract** — Add a note to steps 4 and 10 specifying the exact message map shape that step 10 must produce so that step 4's `append-msg` `"system"` case can consume it without ambiguity (role key type: keyword vs string; content format).
