# Design follow-up steps — 190-add-opus-4-8-model

## From design review pass 2026-05-30

- [x] **Resolve `/speed` scope syntax** — Decide: does `/speed` accept an optional scope token (`/speed fast project`)? If yes, update step 8 to document the three-token form and add an acceptance criterion for scoped persistence. If no, remove the `/speed fast project` example from step 11.

- [x] **Clarify `:runtime/agent-set-speed-mode` effect** — Either (a) remove it from the design (speed-mode is read from session-data via `session->request-options`, so no agent-core sync is needed), or (b) add the missing `execute-effect!` handler in `dispatch_effects.clj` and `agent/set-speed-mode-in!` in `agent-core/core.clj` to the scope.

- [x] **Specify Anthropic beta header for `speed: "fast"`** — Confirm with Anthropic docs whether the fast-mode research preview requires an `anthropic-beta` header string. If yes, add the beta constant and wire it into `beta-header` in `providers/anthropic.clj`; update step 6 accordingly.

- [x] **Clarify `:fast` cross-provider semantics** — Document the intended user-facing meaning of `/speed fast` given the provider asymmetry (Anthropic = faster+expensive, OpenAI = lower-priority+cheaper). Either rename the value to reflect the actual shared abstraction, or add a provider-specific note to the `/speed` help text.

- [x] **Specify `:xhigh` → `"highest"` fallback behaviour** — Choose one: (a) always send `"highest"` and let the 400 surface as a user-visible error (remove "with warning" from the table), or (b) design an explicit fallback that catches the 400 and retries with `"high"` while emitting a warning. Update the effort table and architecture section to be consistent.

- [x] **Specify `:mid-system` compaction preservation mechanism** — Clarify whether `entry->message` in `compaction.clj` must be extended to handle `:mid-system` entries (returning a `{:role "system" ...}` message), or whether preservation is achieved via a different path. Update step 11 with the concrete change required.

- [x] **Document `journal->provider-messages` ↔ `append-msg` contract** — Add a note to steps 4 and 10 specifying the exact message map shape that step 10 must produce so that step 4's `append-msg` `"system"` case can consume it without ambiguity (role key type: keyword vs string; content format).

## From inconsistency review pass 2026-05-30

- [x] **Align `/effort` persistence with command syntax** — Decide whether `/effort` accepts an optional scope token (`/effort xhigh project|user|session`). If yes, update command syntax/help and acceptance criteria; if no, remove project/user persistence effects and shared-config scope from this task.

- [x] **Remove remaining `:xhigh` fallback wording** — Update the Part 3 effort table so Anthropic adaptive `:xhigh` unambiguously maps to `"highest"` with no fallback/warning in this slice, matching the architecture section.

- [x] **Resolve mid-system placement versus next-request inclusion** — Make the Anthropic placement rule, dispatch insertion point, provider validation, and acceptance criteria agree. In particular, specify whether an injected mid-system message may be final in the next generation request; if not, design how it is retained until a valid non-final position exists instead of being dropped.

## From ambiguity review pass 2026-05-30

- [x] **Decide `/effort` behaviour for OpenAI Codex/responses** — Specify whether `/effort` and `:effort-override` affect OpenAI Codex/responses models. If yes, add Codex request shaping/tests; if no, document the exclusion and ensure acceptance criteria only cover supported OpenAI transports.

- [x] **Define invalid-placement handling for mid-system injection** — Specify what `:session/inject-mid-system-message` / `inject-mid-system-message!` returns and stores when called before any user turn, after an assistant turn, or after another pending mid-system entry. Choose reject, queue, or append-and-drop-later semantics.

- [x] **Clarify compaction lifetime of pre-cut mid-system entries** — Decide whether mid-system instructions before the compaction cut point remain active after compaction. If they do, specify the preservation mechanism; if they do not, state that compaction expires them.

## From ambiguity review pass 2026-05-30 (second pass)

- [x] **Allow or reroute Anthropic `"highest"` validation** — Update the design to specify how `output_config.effort = "highest"` passes request validation for adaptive Anthropic requests (`request_schema.clj` enum update or an explicit alternative), so provider rejection can surface as intended.

- [x] **Define mid-system AI conversation representation** — Decide how a projected mid-system provider-style map is represented inside `psi.ai.conversation`: add/describe a `:system` message path compatible with `Message` schema, or require `append-msg` to normalize `{:type :text}` provider blocks into schema-valid content before appending.

- [x] **Specify persisted clear semantics for speed/effort overrides** — Clarify what `/speed normal project|user` and `/effort none project|user` write or delete in shared-config, including how clears interact with lower-precedence user/project settings and the current merge-only config update helpers.

## From inconsistency review pass 2026-05-30 (second pass)

- [x] **Align `/speed` resolver default with nil session state** — Decide whether `:psi.agent-session/speed-mode` resolver coerces nil to `:normal` (matching acceptance criteria and display semantics) or whether acceptance should allow nil. Update the resolver/design text accordingly, including `/speed normal session` behaviour.

- [x] **Specify mid-system capability flag schema/default semantics** — Decide whether `:supports-mid-conversation-system-messages` is optional in `Model` with absent treated as false, or required on every model map. Align the schema step with the acceptance criterion that unsupported models may have the flag false or absent.

## From ambiguity review pass 2026-05-30 (third pass)

- [x] **Allow Anthropic inline system messages through request validation** — Update the design to specify how `request_schema.clj` admits inline `{"role": "system", ...}` messages in the Anthropic `messages` array, or explicitly state a different validation/normalization path, so mid-system requests are not rejected locally.

- [x] **Define pending mid-system handling during current-user replacement** — Specify how prepared-turn assembly updates/replaces the current user message when a pending `:mid-system` entry follows it in the projected journal, preserving the valid provider order `user → system` rather than treating the system message as an unrelated tail.

## From ambiguity review pass 2026-05-30 (third pass)

- [x] **Specify persisted speed/effort startup wiring** — Add the config-resolution accessors and app-runtime/session-default application path for persisted `:speed-mode` and `:effort-override`, including how explicit `:normal` and nil values mask lower-precedence settings when a new session starts.

- [x] **Define mid-system capability lookup source** — Decide whether `model-supports-mid-system-messages` and injection gating read the reduced session model, catalog model, or runtime-resolved model with auth context; document the rule so OpenAI chat-completions and Codex/responses (including OAuth runtime overrides) are classified correctly.

- [x] **Define the `:mid-system` source/provenance contract** — Specify what value is stored in `{:source ...}` for injected mid-system journal entries, and whether the extension API infers it from `ext-path`, accepts it from callers, or omits it.

## From inconsistency review pass 2026-05-30 (latest pass)

- [x] **Align adaptive Anthropic `:xhigh` thinking-level differentiation** — Decide whether plain `thinking-level :xhigh` on adaptive Anthropic models should use `"highest"` when no `/effort` override is set (satisfying the task goal), or narrow the goal/acceptance criteria so only `/effort xhigh` is distinct. Update the mapping/resolution prose and tests accordingly.

## From ambiguity review pass 2026-05-30 (latest pass)

- [x] **Specify explicit nil effort config resolution** — Define how `resolved-effort-override` distinguishes missing/invalid config from an explicit persisted nil after user/project config merging. State whether `:effort-override` is omitted from `system-defaults`, whether key presence/provenance is tracked before merge, or another mechanism preserves explicit nil masks.

- [x] **Define mid-system capability for custom OpenAI chat-completions models** — Decide whether `:supports-mid-conversation-system-messages` is inferred from runtime model API `:openai-completions`, or whether every OpenAI chat-completions model map (including custom/runtime-loaded models) must explicitly carry `true`; update resolver/dispatch expectations accordingly.

## From ambiguity review pass 2026-05-30 (latest pass)

- [x] **Specify extension mutation surface for mid-system injection** — Add the `psi.extension/inject-mid-system-message` Pathom mutation/routing details that bridge the extension API call to `:session/inject-mid-system-message`, including params, output shape, and session-scoped extension mutation registration.

- [x] **Specify explicit `:normal` speed config resolution** — Define how `resolved-speed-mode` distinguishes missing/invalid config from an explicit persisted `:normal` after user/project config merging. State whether `:speed-mode` is omitted from `system-defaults`, whether key presence/provenance is tracked before merge, or another mechanism preserves explicit `:normal` masks.

- [x] **Clarify current-session state for scoped `/speed normal`** — Decide whether `/speed normal project|user` stores nil or `:normal` in the current session state after persisting the scoped explicit default, and align command, handler, resolver, request-options, and tests with that decision.

## From inconsistency review pass 2026-05-30 (compaction boundary)

- [x] **Resolve compaction boundary consecutive mid-system case** — Specify how compaction handles a retained post-cut `:mid-system` immediately after the summary boundary when pre-cut active mid-system instructions are also coalesced. Either merge/coalesce the boundary messages, forbid/snap cut points that split a user→mid-system pair, or otherwise guarantee rebuilt provider messages never contain consecutive inline system messages.

## From inconsistency review pass 2026-05-30 (source provenance pass)

- [x] **Align mid-system source inference with mutation params** — The design says omitted `:source` is inferred from extension provenance (`ext-path`/extension id), but the specified `psi.extension/inject-mid-system-message` Pathom mutation params omit `:ext-path`. Add optional `:ext-path` to the mutation params and define inference there, or require the extension API helper to materialize `:source` before calling the mutation.

## From inconsistency review pass 2026-05-30 (OpenAI placement surface)

- [x] **Align OpenAI mid-system placement with shared injection API** — The design says OpenAI chat-completions accepts inline system messages at any position, but `:session/inject-mid-system-message` only accepts the Anthropic-safe tail shape after the latest user turn. Decide whether psi intentionally exposes the Anthropic-compatible subset for all providers, or whether dispatch/extension injection has provider-specific placement behaviour for OpenAI; update the background, handler rules, resolver/API expectations, and tests accordingly.

## From ambiguity review pass 2026-05-30 (speed schema)

- [x] **Allow Anthropic `speed` body key through request validation** — Part 2 step 6 adds `speed: "fast"` to the Anthropic request body, but `anthropic-request-body-schema` in `request_schema.clj` is `:closed true` and has no `:speed` key. Specify that `request_schema.clj` must include `[:speed {:optional true} [:enum "fast"]]` (or equivalent) in the request body schema, matching the pattern used for `"highest"` effort and inline system messages, so valid fast-mode requests are not rejected by local validation before the HTTP request.

## From inconsistency review pass 2026-05-30 (effort override extended-thinking)

- [x] **Resolve effort override for extended-thinking models and dead `thinking-level->effort-default` rename** — Part 3 step 5 specifies an effort-override mapping for extended-thinking models (`:xhigh` → `"high"` for extended) and renames `thinking-level->effort` to `thinking-level->effort-default`, but extended-thinking models never send `output_config.effort` (current code: `(when (and thinking adaptive?) ...)`). After the rename, adaptive models use `thinking-level->effort-xhigh` exclusively, leaving `thinking-level->effort-default` with no consumer. Either (a) remove the extended-thinking effort-override mapping and drop the rename (keep only the new xhigh table for adaptive), or (b) specify a concrete extended-thinking effect for effort-override (budget multiplier, warning, etc.), or (c) explicitly state that effort-override is silently ignored on extended-thinking models and remove the dead table.

## From ambiguity review pass 2026-05-30 (effort display)

- [x] **Update `effective-reasoning-effort` resolver for adaptive `:xhigh` and effort override** — The existing `effective-reasoning-effort` resolver in `resolvers/session.clj` (used by footer and `/status`) has its own `thinking-level->reasoning-effort` map where `:xhigh` → `"high"`. The design changes the actual Anthropic adaptive effort for `:xhigh` to `"highest"` and adds an effort override, but does not specify updating this display resolver. Decide whether the resolver should (a) incorporate the effort override and adaptive `:xhigh` → `"highest"`, becoming provider/model-aware, or (b) remain a simple thinking-level display where the `• effort:xhigh` suffix is the only override signal.

## From inconsistency review pass 2026-05-30 (independent)

- [x] **Align Codex/responses effort override with actual code path** — Part 3 step 6 says to update `reasoning-effort` in `reasoning.clj` to accept `:effort-override`, and says Codex/responses must use the same mapping instead of reading `thinking-level->effort` directly. But `codex_responses.clj/codex-reasoning` reads `reasoning/thinking-level->effort` directly via `(get reasoning/thinking-level->effort ...)` and does NOT call `reasoning/reasoning-effort`. Specify that `codex-reasoning` must be changed to call the updated `reasoning-effort` function (or an equivalent shared function incorporating the effort override) rather than reading the map directly, so the override actually reaches the Codex request path.

- [x] **Move `/speed fast` provider-semantics paragraph from Part 3 to Part 2** — The paragraph defining the canonical user-facing meaning of `/speed fast` ("use the provider's non-default alternate throughput tier") and noting the Anthropic/OpenAI semantic difference is in Part 3 (effort section). Move or duplicate it into Part 2 (speed section) so Part 2 is self-contained for the speed feature.

## From ambiguity review pass 2026-05-30 (session resume/journal gap)

- [x] **Specify speed/effort restoration on session resume** — Session resume restores model and thinking-level from journal entries, but the design adds no journal entry kinds for speed/effort changes and specifies startup config application only for newly created root sessions. Decide whether (a) speed/effort changes are recorded as journal entries so resume restores them like thinking-level, (b) the resume path re-reads shared-config for speed/effort, or (c) speed/effort are intentionally session-transient and lost on resume. Update the design with the chosen approach and align session lifecycle, journal schema, and acceptance criteria accordingly.

## From inconsistency review pass 2026-05-30 (compaction placement verification)

- [x] **Align compaction mid-system preservation with latest-user placement** — The design says Anthropic-safe mid-system messages are attached to the most recent user turn before the next assistant generation, but compaction currently coalesces pre-cut active mid-system instructions after the synthetic summary user and then carries retained post-cut history normally. If retained history starts with a user, the sequence becomes `summary user → system → retained user`, violating the latest-user tail placement contract. Specify whether this sequence is intentionally valid, whether the coalesced instruction should be reattached to the latest retained user before generation, or whether compaction should choose/merge cut boundaries to preserve the invariant.

## From ambiguity review pass 2026-05-30 (compaction acceptance verification)

- [x] **Align compaction acceptance criterion with conditional attachment rule** — Part 4 step 11 conditionally attaches preserved pre-cut `:mid-system` instructions after the summary user or after the first retained user, but the acceptance criterion still only says "coalesced after the summary user turn". Update the acceptance criterion/tests to require both conditional cases, or simplify the detailed compaction rule so acceptance has one unambiguous attachment point.

## From ambiguity review pass 2026-05-30 (journal metadata placement)

- [x] **Define mid-system placement across non-conversational journal entries** — Decide whether `:session/inject-mid-system-message` placement validation ignores non-conversational journal entries after the latest user turn (for example `:model`, `:thinking-level`, `:label`, `:logprobs`) or requires the literal journal tail to be a user / pending `:mid-system` entry. Specify where the new `:mid-system` entry is appended and how provider-message projection preserves `user → system` ordering when metadata entries sit between them.
## From inconsistency review pass 2026-05-30 (compaction retained-history placement)

- [x] **Align compaction mid-system reattachment with next-generation placement** — The current conditional rule reattaches pre-cut active `:mid-system` instructions after the first retained user when retained history begins with a user, which can place the instruction before an already-retained assistant response. Specify whether compaction may retroactively insert mid-system instructions into retained history, should instead attach them to the latest retained user / next-generation boundary, should alter cut/merge rules, or should explicitly relax the placement contract for compacted history.


## From inconsistency review pass 2026-05-30 (OpenAI resolver acceptance)

- [x] **Align mid-system resolver acceptance with OpenAI support** — Part 4 requires OpenAI chat-completions models to report mid-conversation system support via explicit metadata or runtime `:provider :openai` + `:api :openai-completions` inference, but the resolver acceptance criterion says the query is true for opus-4.8 and false otherwise. Update the acceptance criterion/tests to require true for opus-4.8 and OpenAI chat-completions, and false for Codex/responses and unsupported Anthropic models.

## From ambiguity review pass 2026-05-30 (plan/steps verification)

- [x] **Align plan slice count with slice list** — `plan.md` says the task will be implemented as four vertical slices, but the slice order and `steps.md` define five slices including the integration/coherence pass. Decide whether integration/coherence is a fifth slice or a non-slice final pass, and update `plan.md`/`steps.md` wording so implementors have one unambiguous execution structure.

## From inconsistency review pass 2026-05-30 (plan/steps docs ownership)

- [x] **Align documentation ownership between plan and steps** — `plan.md` lists docs work inside Slice 2 (`/speed`), Slice 3 (`/effort`), and Slice 4 (mid-system), but `steps.md` only schedules docs in Slice 5 integration/coherence. Decide whether docs belong to the feature slices or to Slice 5, and update `plan.md` / `steps.md` so implementors have one consistent execution structure.

## From inconsistency review pass 2026-05-30 (steps/test-status alignment)

- [ ] **Align Slice 4 test step completion with implementation notes** — `implementation.md` says focused tests were added for journal projection/current-user replacement/conversation normalization, Anthropic inline system transform/schema acceptance, and OpenAI system-role transformation, but the corresponding Slice 4 items in `steps.md` remain unchecked. Verify the tests exist/pass and mark those `steps.md` items complete, or revise the implementation note to accurately describe remaining work.
