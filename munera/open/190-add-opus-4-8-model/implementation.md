# Implementation Notes — 190-add-opus-4-8-model

---

## Design review pass — 2026-05-30

**Ambiguities found:**

1. **`/speed` scope argument contradiction** — Part 2 step 8 specifies the command takes exactly one arg (`normal`|`fast`), but Part 2 step 11 references `/speed fast project` as a user-facing invocation implying a scope token. The command spec and the persistence prose are inconsistent. Does `/speed` accept an optional scope token (like `/model`) or not?

2. **`:runtime/agent-set-speed-mode` effect — no execute-effect! handler** — Part 2 step 3 adds the effect type to `dispatch_schema.clj`, but no corresponding `execute-effect!` multimethod in `dispatch_effects.clj` is specified, and no `agent/set-speed-mode-in!` in `agent-core` is mentioned. The parallel for `:runtime/agent-set-thinking-level` includes all three. Either this effect is unneeded (speed-mode is read from session-data, not agent-core) and should be removed from the design, or the missing handler/agent-core function must be added.

3. **`speed: "fast"` Anthropic beta header** — The design describes this as a "research preview" but does not specify whether a beta header is required (as with `interleaved-thinking-beta`, `prompt-caching-beta`, etc.). The `beta-header` function must be updated if a beta string is required; omitting this would cause 400 errors in production.

4. **`:fast` semantic mismatch across providers** — Anthropic `speed: "fast"` = higher throughput, premium pricing. OpenAI `service_tier: "flex"` = lower priority, cheaper. The design acknowledges the difference in prose but maps them to the same `:fast` value. The user-facing meaning of `/speed fast` is ambiguous: is it "faster" (Anthropic) or "cheaper/lower-priority" (OpenAI)?

5. **`:xhigh` → `"highest"` fallback mechanism unspecified** — The effort table says `:xhigh` maps to `"highest"` (when supported; else `"high"` with warning), but the architecture section says "no special retry logic is needed." There is no design for how the warning is emitted: the 400 error surface returns an error to the user, not a transparent fallback with a warning. The table and the architecture section contradict each other.

6. **Compaction preservation mechanism for `:mid-system` entries** — Part 4 step 11 states mid-system messages "must be preserved across compaction boundaries" but does not specify the mechanism. `rebuild-messages-from-entries` calls `entry->message`, which returns `nil` for `:mid-system` (not handled). Post-compaction, the agent message list is rebuilt from `entry->message` results, so `:mid-system` entries would be silently dropped. Either `entry->message` must handle `:mid-system`, or the design must explain an alternative preservation path.

7. **`journal->provider-messages` vs `agent-messages->ai-conversation` dual-path for `:mid-system`** — Part 4 step 4 modifies `append-msg` in `conversation.clj` (handles `"system"` role in agent message maps), and step 10 modifies `journal->provider-messages` (projects `:mid-system` journal entries into `{:role "system" ...}` maps). These two steps are complementary and both needed, but the design does not make their interaction explicit. The implementation must ensure the projected message format from step 10 is compatible with the `append-msg` handler added in step 4.

---

## Ambiguity follow-up — 2026-05-30

Completed all newly added ambiguity follow-up items in `design-steps.md` by refining `design.md` only.

Decisions recorded:
- `/speed` accepts optional scope syntax: `<mode> [session|project|user]`, matching the existing `/model` persistence pattern.
- No `:runtime/agent-set-speed-mode` effect belongs in scope; speed mode is request-built from canonical session data, unlike thinking/model agent-core mirrors.
- Anthropic fast mode requires beta header token `fast-mode-2026-02-01`; step 6 now explicitly includes wiring that token through `beta-header` / `request-headers` alongside `speed: "fast"`.
- `/speed fast` is defined as selecting the provider's alternate non-default throughput tier, with provider-specific help/docs required because Anthropic and OpenAI semantics differ.
- `:xhigh` adaptive effort always sends `"highest"`; unsupported-provider 400s surface directly, with no retry/fallback in this slice.
- Mid-system compaction preservation is concretized via `compaction.clj/entry->message` returning a provider-style `{"role": "system", ...}` shape for `:mid-system` entries.
- The exact `journal->provider-messages` → `append-msg` contract is now documented as `{:role "system" :content [{:type :text :text ...}]}`.

---

## Design inconsistency review pass — 2026-05-30

**New actionable inconsistencies found:**

1. **`/effort` persistence has no command surface** — Part 3 says `:session/set-effort-override` emits project/user persistence effects and shared-config should store `effort-override`, but `/effort` command syntax only accepts one value arg (`low|medium|high|xhigh|none`) and the acceptance criteria do not include scoped persistence. Either add optional scope syntax like `/speed`, or remove persistence from this slice.

2. **`:xhigh` fallback contradiction remains in the effort table** — The Part 3 effort table still says Anthropic adaptive `:xhigh` maps to `"highest"` “when supported; else `"high"` with warning”, while the architecture section later says psi always sends `"highest"` with no transparent retry/fallback and provider 400s surface as-is. The earlier checked follow-up did not fully remove the contradictory table text.

3. **Anthropic mid-system placement rules contradict validation/acceptance** — The background says inline system messages may not appear immediately after an assistant message and may not be the last message, but provider validation only drops final or consecutive system messages, and the acceptance criterion requires the next prepared Anthropic request to include the injected system message. If `inject-mid-system-message!` appends after the latest user turn before the next assistant response, that system message is final in the request and would be dropped by the stated validation rule.

---

## Design ambiguity review pass — 2026-05-30

**New actionable ambiguities found:**

1. **`/effort` scope for OpenAI Codex/responses models** — The goal says `/effort` directly controls the provider reasoning-effort string, and `codex_responses.clj` currently has its own effort request shaping, but Part 3 only specifies OpenAI chat-completions `reasoning.clj` behaviour and acceptance. Decide whether `/effort` applies to OpenAI Codex/responses models; if yes, specify the request shaping and tests, and if no, document that exclusion.

2. **Invalid-time `inject-mid-system-message!` behaviour** — The design now allows final inline system messages after a user turn, but does not define what happens if an extension injects after an assistant turn, before any user turn, or after another pending mid-system entry. Provider validation would later drop the message while the extension API may already have returned `{:ok true}`. Decide whether the dispatch handler rejects these placements, queues until a valid user turn, or appends and accepts later provider drop.

3. **Compaction lifetime for older `:mid-system` instructions** — Part 4 says mid-system messages are instructions that remain valid for the remainder of the session, but the concrete preservation rule only carries forward `:mid-system` entries after the compaction cut point. Decide whether pre-cut mid-system instructions remain active after compaction; if yes, specify how they are preserved outside summarization, and if no, state that compaction intentionally expires them.

---

## Ambiguity follow-up — 2026-05-30

Completed all newly added ambiguity follow-up items in `design-steps.md` by refining `design.md`.

Decisions recorded:
- `/effort` applies to OpenAI Codex/responses as well as OpenAI chat-completions. Codex request shaping must use the same effort override mapping, with `:xhigh` capped to `"high"`, instead of reading only `thinking-level->effort` directly.
- `inject-mid-system-message!` rejects invalid placements before mutating the journal. It only accepts injection after the latest user turn and before any pending assistant response or pending mid-system entry; invalid timing returns `{:ok false :error :invalid-placement :reason ...}`.
- Pre-cut mid-system instructions remain active after compaction. Compaction must preserve them by coalescing their text, in order, into one retained `:mid-system` entry immediately after the compaction summary user turn; post-cut entries are carried forward normally.


---

## Design inconsistency review pass — 2026-05-30

No new actionable inconsistencies found beyond the existing unchecked `design-steps.md` follow-ups. Re-read `design.md` against the referenced model/provider/session-state/request/compaction command surfaces; remaining concerns are already captured and were not duplicated.

---

## Inconsistency follow-up — 2026-05-30

Completed the newly added inconsistency follow-up items in `design-steps.md` by verifying the current `design.md` already contains the required refinements from the preceding follow-up passes:

- `/effort` accepts optional scope syntax `<value> [session|project|user]`; command help, persistence scope, shared-config, and acceptance criteria are aligned.
- Anthropic adaptive `:xhigh` maps to `"highest"` with no transparent fallback or warning in this slice; provider 400s surface as-is.
- Mid-system placement is aligned across the design: dispatch accepts injection only after the latest user turn with no pending mid-system entry, the next request may include that system message as the final message, Anthropic validation allows final system-after-user messages, and invalid placements are rejected before journal mutation.

---

## Design ambiguity review pass — 2026-05-30

**New actionable ambiguities found:**

1. **Anthropic request schema for `"highest"` effort** — The design requires adaptive Anthropic `:xhigh` / `/effort xhigh` to send `output_config.effort = "highest"`, but the existing Anthropic request schema only accepts `"low"|"medium"|"high"`. Specify that `request_schema.clj` must allow `"highest"`, or choose a different validation path, otherwise psi rejects the request before the provider can surface any 400.

2. **Mid-system conversation message representation** — The design says `journal->provider-messages` emits provider-style `{:role "system" :content [{:type :text :text ...}]}` and `append-msg` should append it into the AI conversation, while the AI `Message` schema currently uses keyword roles and normalized `MessageContent` (`:kind`, not provider `:type`). Specify whether the conversation layer gets a `:system` message constructor/schema extension, or whether `append-msg` normalizes provider-style mid-system content before adding it.

3. **Scoped clearing semantics for persisted `/speed normal` and `/effort none`** — The design says `/speed normal` clears the speed override and `/effort none` clears the effort override, while also supporting `project`/`user` persistence scopes. Decide whether scoped clears write explicit nil/normal values that mask lower-precedence config, delete the key to reveal lower layers, or set `:normal`/nil as persistent values; existing config update helpers merge keys and do not delete them.

---

## Ambiguity follow-up — 2026-05-30

Completed the three newly added ambiguity follow-up items in `design-steps.md` by refining `design.md`.

Decisions recorded:
- Anthropic adaptive `"highest"` effort is admitted by local request schema validation; unsupported-value rejection, if any, must come from the provider HTTP response rather than psi preflight.
- Mid-system provider-style journal projection is normalized inside conversation assembly: `append-msg` converts `{:type :text}` blocks to canonical AI `{:kind :text}` content and appends a schema-valid `:system` message after `MessageRole` is extended.
- Scoped clears use explicit persisted defaults compatible with merge-only config helpers: `/speed normal project|user` writes `:speed-mode :normal`; `/effort none project|user` writes `:effort-override nil`; these higher-precedence explicit values mask lower-precedence settings.

---

## Design inconsistency review pass — 2026-05-30

**New actionable inconsistencies found:**

1. **`/speed` resolver/default semantics conflict** — Session state intentionally stores `:speed-mode nil` for provider default and `/speed normal session` clears back to nil, but the acceptance criteria require `(session/query-in ... [:psi.agent-session/speed-mode])` to return `:normal` when unset. The resolver section only says to project from session state, which would expose nil unless it explicitly coerces nil to `:normal`.

2. **Mid-system capability flag requiredness is inconsistent** — Part 4 says `:supports-mid-conversation-system-messages` defaults false and acceptance allows Codex/responses and pre-4.8 Anthropic models to have the flag `false` or absent, but the model-schema step says to add a boolean model attribute to the closed `Model` schema. Specify whether the schema key is optional with absent-as-false semantics, or required on every model map.

---

## Inconsistency follow-up — 2026-05-30 (second pass)

Completed the two newly added inconsistency follow-up items in `design-steps.md` by refining `design.md`:

- `:psi.agent-session/speed-mode` is now explicitly a display/effective resolver: nil session state is coerced to `:normal`, including after `/speed normal session`, while request shaping still treats nil/`:normal` as provider default.
- `:supports-mid-conversation-system-messages` is now optional model metadata; absent is semantically false at schema/capability resolver surfaces, so unsupported models may omit it or set `false`.

---

## Design ambiguity review pass — 2026-05-30 (third pass)

**New actionable ambiguities found:**

1. **Anthropic inline system request schema validation** — Part 4 adds `:system` to the AI `MessageRole` and requires Anthropic `transform-messages` to emit inline `{"role": "system", ...}` entries, but the local Anthropic request schema currently admits only `"user"` and `"assistant"` message roles. Specify that `request_schema.clj` must include an inline system message schema, or choose an explicit route that bypasses/normalizes validation, otherwise valid mid-system requests will be rejected before HTTP.

2. **Mid-system projection versus current-user replacement** — `build-prepared-request` replaces the current persisted user message only when the last projected base message is a user. A valid pending `:mid-system` entry is appended after that user, so the base tail becomes system and the current user replacement/expansion path may no longer update the intended user turn. Specify how `replace-current-user-message` / prepared-turn assembly handles a pending mid-system entry while preserving order `user → system`.
---

## Ambiguity follow-up — 2026-05-30 (third pass)

Completed the two newly added ambiguity follow-up items in `design-steps.md` by refining `design.md`:

- Anthropic local request validation must now admit inline `{"role": "system", ...}` entries in the `messages` array via an explicit system-message schema, so valid mid-system requests are not rejected before HTTP/provider placement handling.
- Prepared-turn assembly must treat a pending tail `... user, system` as an attached mid-system instruction: replace the preceding current user message, then preserve the system message after it, maintaining provider order `user → system`.

---

## Design ambiguity review pass — 2026-05-30 (third pass)

**New actionable ambiguities found:**

1. **Persisted speed/effort startup application path** — The design adds `speed-mode` and `effort-override` to shared-config and says project/user values persist across sessions, but the existing startup path only resolves/applies model, thinking level, prompt mode, and nucleus prelude override into `:session-defaults`. Specify the config-resolution accessors and runtime/session creation wiring that apply persisted `:speed-mode` and `:effort-override` to new sessions, including explicit `:normal` / nil masking semantics.

2. **Mid-system capability lookup source under runtime model overrides** — The design says the resolver and dispatch handler derive support from the active model's `:supports-mid-conversation-system-messages` flag, but session state currently stores a reduced model map and runtime resolution can change OpenAI transport/capabilities (for example OAuth-backed `gpt-5.5` becoming Codex/responses). Specify whether capability checks use the stored session model, catalog lookup by provider/id, or runtime `resolve-runtime-model` with auth context so OpenAI chat-completions versus Codex/responses are classified correctly.

3. **Mid-system journal `:source` contract** — The dispatch handler stores `{:text text :source source}` for `:mid-system` entries and the extension API exposes `(inject-mid-system-message! text)`, but no source value or provenance rule is defined. Specify whether `source` is optional, inferred from `ext-path`, caller-supplied via an arity/options map, or omitted from the journal schema/tests.

---

## Design inconsistency review pass — 2026-05-30

No new actionable inconsistencies found. Re-read `design.md` against referenced config startup, runtime model resolution, request assembly, schema, and persistence artifacts; the remaining unresolved items in `design-steps.md` are ambiguity/specification gaps rather than internal contradictions, so no duplicate follow-up steps were added.

---

## Ambiguity follow-up — 2026-05-30 (third pass, startup/capability/provenance)

Completed the three newly added ambiguity follow-up items in `design-steps.md` by refining `design.md`:

- Persisted `speed-mode` / `effort-override` now have explicit shared-config accessor and app-runtime/session-default startup wiring. Explicit `:normal` speed and explicit nil effort are higher-precedence masks, while request shaping still treats provider-default values as omitted native params.
- Mid-system capability checks now use the runtime-resolved active model (with OAuth/auth context where available), falling back to the stored session model only if runtime resolution fails, so OpenAI chat-completions and Codex/responses runtime overrides classify correctly.
- `:mid-system` journal provenance is optional but expected: the extension API infers it from extension provenance when omitted, trusted/internal callers may pass `{:source ...}`, and dispatch stores the resulting source with a `:extension` fallback.

---

## Inconsistency follow-up — 2026-05-30 (latest pass)

Read `design-steps.md` for newly added inconsistency-review follow-up items. No unchecked inconsistency follow-up items were present; the latest inconsistency review recorded no new actionable inconsistencies. No design changes were required for this pass.

---

## Design inconsistency review pass — 2026-05-30

**New actionable inconsistency found:**

1. **Adaptive Anthropic `thinking-level :xhigh` remains indistinct without `/effort` override** — The task goal requires `:xhigh` thinking level to be genuinely distinct from `:high` on providers that support it, and Part 3 introduces `thinking-level->effort-xhigh` mapping `:xhigh` to `"highest"` for adaptive Anthropic. But the request-resolution prose says that when no `:effort-override` is set, adaptive Anthropic falls back to the existing `thinking-level->effort-default` mapping, where `:xhigh` remains `"high"`. As written, only `/effort xhigh` becomes distinct; plain `/thinking xhigh` on Opus 4.7/4.8 does not.

---

## Inconsistency follow-up — 2026-05-30 (adaptive xhigh differentiation)

Completed the newly added inconsistency follow-up item in `design-steps.md` by refining `design.md`:

- Plain adaptive Anthropic `thinking-level :xhigh` now satisfies the task goal without requiring `/effort xhigh`: when no effort override is set, adaptive-thinking models use the xhigh-aware level-derived mapping so `:xhigh` sends `output_config.effort = "highest"` while `:high` sends `"high"`.
- `/effort xhigh` remains an explicit override path to the same adaptive Anthropic `"highest"` value; unsupported provider rejection still surfaces directly with no fallback.
- Acceptance criteria now explicitly require the nil-override adaptive Anthropic case to distinguish `thinking-level :xhigh` from `:high`.

---

## Design ambiguity review pass — 2026-05-30 (latest pass)

**New actionable ambiguities found:**

1. **Explicit nil effort config through merged resolution** — The design requires `resolved-effort-override` to distinguish missing/invalid from explicit persisted nil after shared-config merging, but the current config resolver uses a flat `merge` over `system-defaults`, user, and project maps. Specify whether `:effort-override` is intentionally omitted from `system-defaults`, whether resolution tracks key presence/provenance before merge, or another mechanism preserves explicit nil masks; otherwise every resolved config may look explicitly nil or lower-precedence nil masks may be indistinguishable from absence.

2. **Mid-system capability for custom/runtime OpenAI chat-completions models** — The design says all OpenAI chat-completions models support mid-conversation system messages while also making absent `:supports-mid-conversation-system-messages` mean false. Specify whether capability is inferred from runtime model API `:openai-completions`, or every built-in/custom/runtime OpenAI chat-completions model map must be explicitly annotated. Without this, custom OpenAI chat-completions models can be falsely gated off.

---

## Design ambiguity review pass — 2026-05-30 (latest pass)

**New actionable ambiguities found:**

1. **Extension mutation surface for `inject-mid-system-message!`** — The extension API is specified to call `mutate-ext-required` with `psi.extension/inject-mid-system-message`, and the design adds a dispatch handler, but it does not specify the Pathom mutation that bridges that extension op to dispatch, its params/output shape, or adding the op to the session-scoped extension mutation routing set. Without that surface the API function has no registered mutation to invoke.

2. **Explicit `:normal` speed config through merged resolution** — Startup rules distinguish missing/invalid speed config from explicit persisted `:normal` speed masks, but the design does not specify the exact `resolved-speed-mode` return shape or whether `:speed-mode` is omitted from `system-defaults`. If a system default of `:normal` is introduced, the startup path cannot tell missing from explicit persisted `:normal` and may incorrectly store a session override.

3. **Current-session state after scoped default clears** — `/speed normal project|user` is specified both as clearing the speed override and as persisting explicit `:normal` that masks lower-precedence layers; the session mutation also says it stores `:speed-mode` on the session. Specify whether the current session stores nil or `:normal` after scoped `/speed normal`, so resolver/state tests and request-option propagation assert the intended canonical shape.

---

## Ambiguity follow-up — 2026-05-30 (latest pass)

Completed the two newly added ambiguity follow-up items in `design-steps.md` by refining `design.md`:

- `resolved-effort-override` now preserves explicit persisted nil masks without extra provenance by omitting `:effort-override` from `shared-config.resolution/system-defaults` and using key presence after the normal user/project merge to distinguish absence from explicit nil. Invalid present values are treated as missing at startup.
- Mid-system support for OpenAI chat-completions is now inferred from the runtime-resolved model API shape (`:provider :openai`, `:api :openai-completions`) as well as explicit capability metadata. Resolver and dispatch gating must use the same predicate, so custom/runtime-loaded OpenAI chat-completions models are not falsely gated off, while Codex/responses remain unsupported.

Additional latest-pass follow-ups completed after re-reading the current `design-steps.md`:

- Specified the extension mutation bridge for `inject-mid-system-message!`: Pathom op name, params/output, dispatch mapping, `all-mutations` registration, and `runtime_eql` session-scoped routing.
- Specified `resolved-speed-mode` presence semantics: omit `:speed-mode` from `system-defaults`, use key presence after merge, and return a presence-aware accessor result so explicit persisted `:normal` masks are distinguishable from absence.
- Clarified scoped `/speed normal project|user` current-session state: it stores explicit `:normal` in the current session after persisting the scoped default, while unscoped/session `/speed normal` clears to nil; both shapes omit provider speed params and resolve/display as `:normal`.

---

## Design ambiguity review pass — 2026-05-30 (verification pass)

No new actionable ambiguities found. Re-read `design.md`, referenced extension mutation/runtime EQL surfaces, shared-config startup resolution, current-session speed clearing semantics, provider request assembly, and existing `design-steps.md`; the actionable ambiguity concerns are already captured in existing follow-up items and have no unduplicated additions for this pass.

---

## Ambiguity follow-up — 2026-05-30 (no-op pass)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items. No unchecked ambiguity follow-up items were present, so no `design.md` changes were required. `plan.md` and `steps.md` were not touched.

---

## Design inconsistency review pass — 2026-05-30

**New actionable inconsistency found:**

1. **Compaction can create consecutive mid-system messages at the summary boundary** — Part 4 says compaction preserves pre-cut active `:mid-system` entries by coalescing them into one retained `:mid-system` immediately after the compaction summary user turn, and also carries post-cut `:mid-system` entries forward normally. If the cut leaves a pending post-cut `:mid-system` at the beginning of retained history while older pre-cut mid-system instructions are coalesced, the rebuilt request can become `summary user → coalesced system → post-cut system`, contradicting both the “avoids consecutive inline system messages” preservation claim and the Anthropic placement validator that drops consecutive system messages.

---

## Inconsistency follow-up — 2026-05-30 (compaction boundary)

Completed the newly added inconsistency follow-up item in `design-steps.md` by refining `design.md`:

- Compaction now has an explicit boundary merge rule for retained post-cut `:mid-system` entries. If pre-cut active mid-system instructions are coalesced after the summary user turn and the retained post-cut history begins with one or more `:mid-system` entries, those boundary entries are merged into the same summary-boundary `:mid-system` entry, preserving pre-cut text first and post-cut boundary text next. This guarantees the rebuilt provider message sequence never creates `summary user → system → system` at the compaction boundary.

---

## Design inconsistency review pass — 2026-05-30

**New actionable inconsistency found:**

1. **Mid-system source inference needs `ext-path` but mutation params omit it** — Part 4 requires omitted `:source` to be inferred from extension provenance (`ext-path`/extension id), and `create-extension-api`/`mutate-ext-required` normally adds `:ext-path` to extension mutations. But the specified `psi.extension/inject-mid-system-message` Pathom mutation params list only `[:psi/agent-session-ctx :session-id :text]` plus optional `:source`, so the mutation surface as written has no declared provenance input to infer from. Align the API/mutation contract by accepting optional `:ext-path` (or by having the API helper materialize `:source` before mutation) and specifying which layer performs the inference.

---

## Design ambiguity review pass — 2026-05-30 (latest verification pass)

No new actionable ambiguities found. Re-read `design.md` against the referenced shared-config resolution/startup path, extension mutation/runtime EQL bridge, session-state/model schemas, provider message transforms, conversation assembly, and existing `design-steps.md`; all actionable ambiguity concerns identified in this pass are already resolved in the current design or previously captured and checked.

---

## Inconsistency follow-up — 2026-05-30 (source provenance mutation params)

Completed the newly added inconsistency follow-up item in `design-steps.md` by refining `design.md`:

- `psi.extension/inject-mid-system-message` Pathom mutation params now include optional `:ext-path` as well as optional `:source`.
- Provenance inference is explicitly owned by the extension mutation surface: explicit `:source` wins, otherwise `:ext-path` becomes the source, otherwise the mutation passes `:extension` to dispatch.
- Dispatch continues to store the already-derived source value; tests should assert provenance presence without depending on a provider-specific source string.

---

## Ambiguity follow-up execution — 2026-05-30

Checked `design-steps.md` for newly added unchecked ambiguity follow-up items. None were present; all ambiguity follow-ups are already marked complete. No `design.md` changes were required.

---

## Design ambiguity review pass — 2026-05-30 (independent verification)

No new actionable ambiguities found. Re-read `design.md` and checked the referenced shared-config resolution/startup wiring, session-state schemas/defaults, provider request shaping, conversation/journal projection, compaction rebuild path, extension mutation bridge, and existing `design-steps.md`; all ambiguity concerns identified in this pass are already resolved or already captured in checked follow-up items. No duplicate `design-steps.md` items were added.

---

## Ambiguity follow-up execution — 2026-05-30 (latest pass)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items. No unchecked ambiguity follow-up items were present; all ambiguity follow-ups are already marked complete. No `design.md` changes were required, and `plan.md` / `steps.md` were not touched.

---

## Design ambiguity review pass — 2026-05-30 (independent verification, speed schema)

**New actionable ambiguity found:**

1. **Anthropic request schema for `speed` body key** — Part 2 step 6 says to add `speed: "fast"` to the Anthropic request body, but `anthropic-request-body-schema` in `request_schema.clj` is `:closed true` and does not include a `:speed` key. The design already specifies schema updates for `"highest"` effort and inline system messages but omits the analogous update for the `speed` body key. Without adding `[:speed {:optional true} [:enum "fast"]]` (or equivalent) to the request schema, valid fast-mode requests will be rejected by `validate-request-body!` before the HTTP request is attempted.

---

## Design inconsistency review pass — 2026-05-30

**New actionable inconsistency found:**

1. **OpenAI mid-system placement support conflicts with global injection restriction** — Part 4 states OpenAI chat-completions accepts inline `system` messages at any position and needs no special handling beyond pass-through, but the shared `:session/inject-mid-system-message` handler rejects injections unless the journal tail is the latest user turn with no pending `:mid-system`. As written, OpenAI-capable sessions are advertised as supporting a broader placement surface than the only first-class extension API permits. Align the design by either defining psi's extension API as the safe Anthropic-compatible subset for all providers, or by specifying provider-specific placement rules for OpenAI versus Anthropic.

---

## Ambiguity follow-up — 2026-05-30 (OpenAI placement + speed schema)

Completed the two newly added follow-up items in `design-steps.md` by refining `design.md`.

Decisions recorded:
- Psi intentionally exposes the Anthropic-compatible placement subset for all providers. The shared `:session/inject-mid-system-message` handler enforces one set of placement rules regardless of provider. OpenAI's broader placement is a strict superset; the Anthropic-safe subset is always valid for both. No provider-specific placement relaxation is exposed through the extension API in this slice. Updated background, OpenAI provider section (step 6), and verified acceptance criteria are consistent.
- Anthropic `request_schema.clj` must include `[:speed {:optional true} [:enum "fast"]]` in `anthropic-request-body-schema`, following the same pattern used for `"highest"` effort and inline system messages. Added the schema requirement to Part 2 step 6 and a matching acceptance criterion.

---

## Design inconsistency review pass — 2026-05-30

**New actionable inconsistency found:**

1. **Effort override for extended-thinking models contradicts no-effort-sending rule** — Part 3 step 5 specifies an effort-override mapping for extended-thinking models (`:xhigh` → `"high"` for extended) and renames the current `thinking-level->effort` to `thinking-level->effort-default`. But the same section states extended-thinking models "do not send adaptive `output_config.effort`", and the current code only computes/applies effort `(when (and thinking adaptive?) ...)`. The override mapping for extended models has no sending path, and the renamed `thinking-level->effort-default` has no remaining consumer since adaptive models use the new `thinking-level->effort-xhigh` table. Either remove the extended-thinking effort-override mapping and the dead rename, or specify how effort-override is applied to extended-thinking models (e.g. as a budget multiplier or ignored with a warning).

---

## Inconsistency follow-up — 2026-05-30 (effort override extended-thinking)

Completed the newly added inconsistency follow-up item in `design-steps.md` by refining `design.md`.

Decision: option (a) — removed the rename and the dead extended-thinking effort-override mapping.

- The existing `thinking-level->effort` table is updated in place to map `:xhigh` → `"highest"` (was `"high"`). No rename to `thinking-level->effort-default` is needed because this table is only consumed in the adaptive path.
- Effort override for adaptive models uses a separate `effort-override->effort` mapping `{:low "low" :medium "medium" :high "high" :xhigh "highest"}`, applied only when the model is adaptive-thinking.
- Effort override is silently inapplicable to extended-thinking models because they use `budget_tokens`, not `output_config.effort`. No dead table, no dead mapping, no special handling needed.
- Extended-thinking `:xhigh` distinction is already provided by `thinking-level->budget {:xhigh 32000}` vs `:high 16000` (Part 3 step 11, unchanged).

---

## Design ambiguity review pass — 2026-05-30 (independent, effort display)

**New actionable ambiguity found:**

1. **`effective-reasoning-effort` resolver not updated for adaptive `:xhigh` or effort override** — The existing `effective-reasoning-effort` resolver in `resolvers/session.clj` (used by footer and `/status`) has its own `thinking-level->reasoning-effort` map where `:xhigh` → `"high"`. The design changes the actual Anthropic adaptive effort for `:xhigh` to `"highest"` and adds an effort override, but does not specify updating this display resolver. Part 3 step 8 adds a new `• effort:xhigh` footer suffix for the override case, but the existing `thinking high` display from the resolver remains stale when plain `thinking-level :xhigh` on adaptive models actually sends `"highest"`. Decide whether the `effective-reasoning-effort` resolver should (a) incorporate the effort override and the new adaptive `:xhigh` → `"highest"` value, becoming provider/model-aware, or (b) remain a simple thinking-level-derived display where the `• effort:xhigh` suffix is the only override signal, accepting that `thinking high` is shown even when `"highest"` is actually sent.

---

## Ambiguity follow-up — 2026-05-30 (effort display resolver)

Completed the newly added ambiguity follow-up item in `design-steps.md` by refining `design.md`.

Decision: option (b) with a display correction — keep the resolver provider-agnostic but update the display map.

- `effective-reasoning-effort` remains a simple thinking-level display resolver, not provider/model-aware.
- Updated `thinking-level->reasoning-effort` map: `:xhigh` → `"xhigh"` (was `"high"`), so the footer shows `thinking xhigh` when `thinking-level` is `:xhigh`, accurately reflecting the now-distinct level.
- The `• effort:xhigh` footer suffix (Part 3 step 8) remains the separate signal for an explicit `/effort` override.
- No effort override or provider-specific wire values are incorporated into this resolver.

---

## Design inconsistency review pass — 2026-05-30 (independent)

**New actionable inconsistencies found:**

1. **Codex/responses effort override mechanism doesn't match actual code path** — Part 3 step 6 says "Update `reasoning-effort` to accept an optional `:effort-override` from options" and "Codex/responses request shaping must use the same mapping instead of reading `thinking-level->effort` directly." But `codex_responses.clj/codex-reasoning` reads `reasoning/thinking-level->effort` directly via `(get reasoning/thinking-level->effort ...)` — it does NOT call `reasoning/reasoning-effort`. Updating `reasoning-effort` alone won't fix Codex because Codex doesn't call that function. The design must specify that `codex-reasoning` is changed to call the updated `reasoning-effort` (or an equivalent shared function incorporating the effort override) rather than reading the map directly.

2. **`/speed fast` provider-semantics paragraph is misplaced in Part 3** — The paragraph defining the canonical user-facing meaning of `/speed fast` ("use the provider's non-default alternate throughput tier") and noting the Anthropic/OpenAI semantic difference appears in Part 3 (effort section, between the effort table and effort override description), not in Part 2 (speed section). Part 2 describes the speed command, architecture, and acceptance criteria but never defines the canonical semantics or addresses the provider asymmetry. An implementor reading Part 2 alone lacks the semantic definition for the feature.

---

## Inconsistency follow-up — 2026-05-30 (independent)

Completed the two newly added inconsistency follow-up items in `design-steps.md` by refining `design.md`:

- Part 3 step 6 now explicitly specifies that `codex-reasoning` must be changed to call the updated `reasoning-effort` function (or equivalent shared function incorporating effort override) rather than reading `reasoning/thinking-level->effort` directly, so the effort override actually reaches the Codex request path.
- The `/speed fast` provider-semantics paragraph (canonical user-facing meaning and Anthropic/OpenAI mapping difference) is now in Part 2 after the speed mode values table, making Part 2 self-contained. The duplicate in Part 3 has been removed.

---

## Design ambiguity review pass — 2026-05-30 (session resume/journal gap)

**New actionable ambiguity found:**

1. **Speed/effort not restored on session resume** — The existing session resume path (`session_lifecycle.clj/resume-session-in!`) restores model and thinking-level from journal entries (`:kind :model` and `:kind :thinking-level`). The design adds `:speed-mode` and `:effort-override` to session state and specifies startup config application for *newly created root sessions* (Part 2 step 12, Part 3 step 10a), but does not add journal entry kinds for speed/effort changes, nor does it specify how resume restores them. If a user sets `/speed fast project` and then resumes a session, the resume path reads journal entries (which lack speed/effort kinds) and falls back to the source session's in-memory state — not the persisted project config. Decide whether (a) speed/effort changes should be recorded as journal entries (`:speed-mode` and `:effort-override` kinds) so resume restores them like thinking-level, (b) the resume path should re-read shared-config for speed/effort, or (c) speed/effort are intentionally session-transient and lost on resume (with explicit documentation of that limitation).

---

## Ambiguity follow-up — 2026-05-30 (session resume)

Completed the newly added ambiguity follow-up item in `design-steps.md` by refining `design.md`.

Decision: option (c) — speed/effort are intentionally session-transient and not restored on cold session resume.

- No `:speed-mode` or `:effort-override` journal entry kinds are added in this task. Resume from a persisted journal file starts with nil speed/effort (provider defaults).
- Hot resume from a live source session incidentally carries over in-memory values via `source-sd` fallback, but this is not a guaranteed contract.
- Persisted project/user config is applied only on new session creation (Part 2 step 12, Part 3 step 10a), not on resume.
- Added Part 2 step 13 and Part 3 step 12 documenting the session-transient constraint.
- Added acceptance criteria entries for both Part 2 and Part 3 stating speed/effort are not restored on cold resume.

---

## Design inconsistency review pass — 2026-05-30 (independent verification)

No new actionable inconsistencies found. Re-read `design.md` against referenced code artifacts: `models.clj` (model catalog, `anthropic-json-schema-native-model-keys`, `adaptive-thinking` flag pattern), `anthropic.clj` (`thinking-level->effort` table, `thinking-param` nil-for-off guard, `request-body` effort/output_config construction, `beta-header`/`request-headers` signatures, `transform-message` role dispatch), `request_schema.clj` (`anthropic-output-config-schema` effort enum, `anthropic-request-body-schema` closed map, `anthropic-message-schema` user/assistant-only), `reasoning.clj` (`thinking-level->effort` OpenAI map, `reasoning-effort` function signature), `codex_responses.clj` (`codex-reasoning` direct map read), `chat_completions.clj` (`build-request` effort/service_tier construction), `schemas.clj` (`Model` closed schema, `MessageRole` enum, `Message` schema), `model.clj` (`session-entry-kind-schema`, `agent-session-schema`), `conversation.clj` (`append-msg` role dispatch), `compaction.clj` (`entry->message` kind dispatch), `prompt_request.clj` (`journal->provider-messages` `:message`-only projection, `session->request-options` propagation pattern, `replace-current-user-message` tail logic), `resolution.clj` (`system-defaults` keys, `resolve-config` merge). Verified all 27 existing design-steps are checked and consistent with current design text. No duplicate follow-up items added.

---

## Design ambiguity review pass — 2026-05-30 (latest independent verification)

No new actionable ambiguities found. Re-read `design.md` and checked the referenced shared-config resolution defaults/accessors, extension mutation/runtime EQL bridge, prompt request journal projection/current-user replacement, provider request schema/request shaping, OpenAI Codex reasoning path, and existing `design-steps.md`; all ambiguity concerns identified in this pass are already resolved or already captured in checked follow-up items. No duplicate `design-steps.md` items were added.

---

## Ambiguity follow-up execution — 2026-05-30 (post-review pass)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items after the preceding ambiguity-review pass. No unchecked ambiguity items were present; all ambiguity follow-ups are already marked complete. No `design.md` changes were required. `plan.md` and `steps.md` were not touched.

---

## Design inconsistency review pass — 2026-05-30 (compaction placement verification)

**New actionable inconsistency found:**

1. **Compaction can place preserved mid-system instructions before retained user history** — Part 4 defines Anthropic-safe mid-system placement as immediately after the most recent user turn and before the assistant response being generated, and the injection API only permits the latest-user tail shape. But the compaction rule coalesces pre-cut active `:mid-system` entries immediately after the synthetic summary user turn, then carries retained post-cut history normally. If the retained post-cut history starts with a user turn, the rebuilt request becomes `summary user → system → retained user ...`, so the preserved system message is no longer attached to the most recent user turn / next assistant generation despite passing the weaker provider validation rule of “preceded by a user”. Align compaction preservation with the placement contract by specifying how to handle retained history that begins with a user (for example attach the coalesced instruction to the latest retained user before generation, define `user → system → user` as intentionally valid, or choose a cut/merge rule that preserves the latest-user tail invariant).

---

## Inconsistency follow-up — 2026-05-30 (compaction latest-user placement)

Completed the newly added inconsistency follow-up item in `design-steps.md` by refining `design.md`:

- Compaction now preserves active pre-cut `:mid-system` instructions by attaching the coalesced instruction to the first valid user boundary after compaction.
- If retained post-cut history begins with a user turn, the coalesced instruction is reattached immediately after that retained user instead of after the synthetic summary user, avoiding `summary user → system → retained user`.
- If retained boundary `:mid-system` entries are present, they merge into the same coalesced entry and move with the chosen attachment point, preserving order and avoiding consecutive system messages.

---

## Design ambiguity review pass — 2026-05-30 (compaction acceptance verification)

**New actionable ambiguity found:**

1. **Compaction acceptance criterion still states only summary-user attachment** — Part 4 step 11 now has a conditional compaction rule: pre-cut active `:mid-system` instructions attach after the summary user only when retained history does not begin with a user, but reattach after the first retained user when retained history starts with a user. The Part 4 acceptance criterion still says compaction preserves pre-cut active mid-system instructions "coalesced after the summary user turn". Decide whether acceptance/tests must cover both conditional attachment cases, or whether the detailed rule should be simplified back to unconditional summary-user attachment.

---

## Ambiguity follow-up — 2026-05-30 (compaction acceptance criterion)

Completed the newly added ambiguity follow-up item in `design-steps.md` by refining `design.md`.

Decision recorded:
- Kept the detailed conditional compaction attachment rule from Part 4 step 11.
- Updated the Part 4 acceptance criterion to require tests for both conditional cases: pre-cut mid-system instructions coalesced after the summary user when retained history does not begin with a user, and reattached after the first retained user when retained history begins with a user.

---

## Design inconsistency review pass — 2026-05-30 (latest independent verification)

No new actionable inconsistencies found. Re-read `design.md` and checked referenced artifacts for model/provider schemas, Anthropic request shaping/validation, prompt request projection/current-user replacement, compaction rebuild rules, session resolvers, and shared-config resolution. Existing design concerns are already resolved or captured in checked `design-steps.md`; no duplicate follow-up items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (latest no-op)

Read `design-steps.md` for newly added unchecked inconsistency follow-up items after the preceding inconsistency-review pass. No unchecked design-step items were present, so no `design.md` changes were required. `plan.md` and `steps.md` were not touched.

---

## Design ambiguity review pass — 2026-05-30 (journal metadata placement)

**New actionable ambiguity found:**

1. **Mid-system injection placement with non-conversational journal entries** — Part 4 says `:session/inject-mid-system-message` is accepted when the journal tail has a user turn as the latest conversational entry and no pending `:mid-system`, but the journal can contain non-conversational entries (`:model`, `:thinking-level`, `:label`, `:logprobs`, etc.) after that user turn before the assistant response. Specify whether placement validation ignores such metadata entries and inserts/appends the `:mid-system` after them, or requires the literal last journal entry to be the user/`:mid-system` boundary. Without this, extension calls after a model/thinking change between user and assistant have unclear accept/reject behavior and unclear provider-message ordering.


---

## Ambiguity follow-up — 2026-05-30 (journal metadata placement)

Completed the newly added ambiguity follow-up item in `design-steps.md` by refining `design.md`.

Decision recorded:
- `:session/inject-mid-system-message` placement validation ignores non-conversational journal entries such as `:model`, `:thinking-level`, `:label`, and `:logprobs` when determining the latest conversational entry and pending `:mid-system` state.
- On valid injection, dispatch appends the `:mid-system` entry at the literal journal tail; any intervening metadata remains before it in journal order.
- `journal->provider-messages` does not project metadata entries, so provider-message assembly collapses across them and preserves `user → system` ordering.
- Acceptance criteria now require coverage for valid injection after metadata entries between the latest user turn and the assistant response.
---

## Design inconsistency review pass — 2026-05-30 (compaction retained-history placement)

**New actionable inconsistency found:**

1. **Compaction reattaches preserved mid-system instructions before retained assistant history** — Part 4 defines the Anthropic-safe/injection placement as a pending instruction immediately after the most recent user turn before the assistant response being generated, but the compaction rule reattaches pre-cut active `:mid-system` text after the first retained user when retained history begins with a user, before any retained assistant response to that user. If retained history contains an already-generated assistant reply after that first user, compaction retroactively inserts the instruction into historical conversation rather than attaching it to the next generation/latest-user boundary, contradicting the placement contract and the “remain valid for the remainder of the session” intent.


---

## Inconsistency follow-up — 2026-05-30 (compaction retained-history placement)

Completed the newly added inconsistency follow-up item in `design-steps.md` by refining `design.md`:

- Compaction must not retroactively insert preserved mid-system instructions before already-retained assistant responses.
- When preserved pre-cut or boundary `:mid-system` instructions need placement, compaction normalizes the retained suffix by advancing the cut past completed retained user/assistant exchanges until the suffix is empty or ends at a pending latest user boundary.
- Preserved instructions attach after the summary user only when no pending retained user boundary exists, or after the latest retained pending user when one exists; tests must cover the cut-normalization case that avoids `retained user → system → retained assistant`.

---

## Design ambiguity review pass — 2026-05-30 (post-compaction verification)

No new actionable ambiguities found. Re-read `design.md` and checked referenced model catalog/schema, Anthropic request schema/request shaping, prompt-request projection/current-user replacement, dispatch/session mutation surfaces, and compaction preservation rules; existing ambiguity follow-ups are already checked in `design-steps.md`, and no unduplicated ambiguity items were identified. `design-steps.md` was left unchanged.

---

## Ambiguity follow-up execution — 2026-05-30 (latest post-compaction pass)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items after the preceding ambiguity-review pass. No unchecked ambiguity items were present; all ambiguity follow-ups are already marked complete. No `design.md` changes were required. `plan.md` and `steps.md` were not touched.

---

## Design inconsistency review pass — 2026-05-30 (OpenAI resolver acceptance)

**New actionable inconsistency found:**

1. **Mid-system resolver acceptance contradicts OpenAI support requirement** — Part 4 requires all OpenAI chat-completions models, including custom/runtime-loaded maps inferred from `:provider :openai` + `:api :openai-completions`, to report mid-conversation system support. But the resolver acceptance criterion says `(session/query-in ... [:psi.agent-session/model-supports-mid-system-messages])` returns `true` when an opus-4.8 session is active and `false` otherwise, which would make OpenAI chat-completions sessions false despite the immediately preceding OpenAI support requirement. Align the acceptance criterion to include OpenAI chat-completions true cases and unsupported Anthropic/Codex false cases.

---

## Inconsistency follow-up — 2026-05-30 (OpenAI resolver acceptance)

Completed the newly added inconsistency follow-up item in `design-steps.md` by refining `design.md`:

- Updated the Part 4 resolver acceptance criterion so `:psi.agent-session/model-supports-mid-system-messages` must return true for both opus-4.8 and OpenAI chat-completions sessions, including custom/runtime-loaded OpenAI chat-completions model maps inferred from `:provider :openai` + `:api :openai-completions`.
- The same criterion now explicitly requires false for Codex/responses models and unsupported Anthropic models, aligning acceptance with the model capability and resolver rules.

---

## Design ambiguity review pass — 2026-05-30 (final verification)

No new actionable ambiguities found. Re-read `design.md` and checked referenced model catalog/schema, shared-config startup resolution, session request options, command/status display surfaces, provider request shaping/validation, OpenAI Codex effort path, extension mutation bridge, journal projection/current-user replacement, and compaction preservation rules. Existing ambiguity follow-ups in `design-steps.md` are already checked; no duplicate follow-up items were added.

---

## Ambiguity follow-up execution — 2026-05-30 (final no-op)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items after the preceding ambiguity-review pass. No unchecked ambiguity items were present; all ambiguity follow-ups are already marked complete. No `design.md` changes were required. `plan.md` and `steps.md` were not touched.

---

## Design inconsistency review pass — 2026-05-30 (final independent verification)

No new actionable inconsistencies found. Re-read `design.md` and checked referenced artifacts for model catalog/schema, Anthropic request shaping and local validation, OpenAI reasoning/Codex effort paths, shared-config resolution/startup semantics, command/resolver/status surfaces, extension mutation/runtime EQL bridge, journal projection/current-user replacement, and compaction preservation. Existing inconsistency follow-ups in `design-steps.md` are already checked; no duplicate follow-up items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (post-final verification no-op)

Read `design-steps.md` for newly added unchecked inconsistency follow-up items after the preceding inconsistency-review pass. No unchecked design-step items were present; all inconsistency follow-ups are already marked complete. No `design.md` changes were required. `plan.md` and `steps.md` were not touched.

---

## Design ambiguity review pass — 2026-05-30 (post-final independent verification)

No new actionable ambiguities found. Re-read `design.md` and checked referenced shared-config resolution, prompt request projection/current-user replacement, Anthropic/OpenAI request shaping surfaces, extension mutation bridge, model capability rules, and compaction placement rules. Existing ambiguity follow-ups in `design-steps.md` are already checked; no duplicate follow-up items were added.

---

## Ambiguity follow-up execution — 2026-05-30 (post-final independent no-op)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items after the preceding ambiguity-review pass. No unchecked ambiguity items were present; all ambiguity follow-ups are already marked complete. No `design.md` changes were required. `plan.md` and `steps.md` were not touched.


---

## Design inconsistency review pass — 2026-05-30 (post-final independent no-op)

No new actionable inconsistencies found. Re-read `design.md` only for design scope and checked referenced artifacts needed for consistency: model catalog/schema, Anthropic request shaping/schema, OpenAI reasoning/Codex request paths, session-state schemas, prompt request projection/current-user replacement, conversation assembly, compaction rebuild rules, shared-config resolution semantics, and extension mutation/runtime EQL surfaces. Existing inconsistency follow-ups in `design-steps.md` are already checked; no duplicate follow-up items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (post-final independent no-op)

Read `design-steps.md` for newly added unchecked inconsistency follow-up items after the preceding inconsistency-review pass. No unchecked design-step items were present; all inconsistency follow-ups are already marked complete. No `design.md` changes were required. `plan.md` and `steps.md` were not touched.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps verification)

**New actionable ambiguity found:**

1. **Plan slice count contradicts slice list** — `plan.md` says to implement the task as four vertical slices, but the slice order lists five numbered items and `steps.md` has five slice sections, with integration/coherence as Slice 5. Decide whether integration/coherence is intended to be a fifth slice or a final non-slice pass, and align `plan.md`/`steps.md` wording so execution order is unambiguous.

---

## Ambiguity follow-up — 2026-05-30 (plan/steps slice count)

Completed the newly added ambiguity follow-up item in `design-steps.md` by aligning `plan.md` with the existing slice list and `steps.md` structure.

Decision recorded:
- Integration/coherence is Slice 5, not a separate unnumbered final pass.
- `plan.md` now says the task has five vertical slices and explicitly identifies the fifth slice as the integration/coherence slice.
- `steps.md` already used five slice sections, so no `steps.md` changes were required.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps docs ownership)

**New actionable inconsistency found:**

1. **Documentation ownership differs between plan and steps** — `plan.md` describes Slice 2, Slice 3, and Slice 4 as including docs for `/speed`, `/effort`, and mid-system support respectively, while `steps.md` has no per-feature doc items in those slices and instead puts all README/`doc/` updates in Slice 5 integration/coherence. Align the plan/steps so documentation work has one execution owner: either keep docs in Slice 5 only, or add explicit per-slice documentation steps.
---

## Inconsistency follow-up — 2026-05-30 (plan/steps docs ownership)

Completed the newly added inconsistency follow-up item in `design-steps.md` by aligning documentation ownership between `plan.md` and `steps.md`.

Decision recorded:
- Documentation belongs to Slice 5 integration/coherence, matching the existing `steps.md` structure.
- Removed per-feature docs ownership wording from plan Slices 2, 3, and 4.
- Expanded plan Slice 5 to explicitly own README, `doc/`, and changelog updates for Opus 4.8, `/speed`, `/effort`, and mid-system extension capability.
- Marked the docs-ownership design-step complete.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps independent verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and the latest `implementation.md` notes, then spot-checked referenced code surfaces for Anthropic request validation, prompt-request journal projection/current-user replacement, and compaction rebuild. The plan/steps now present one unambiguous five-slice execution structure with documentation/changelog owned by Slice 5; existing ambiguity follow-ups in `design-steps.md` are checked and were not duplicated.

---

## Ambiguity follow-up execution — 2026-05-30 (post-plan verification no-op)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items after the preceding ambiguity-review pass. No unchecked ambiguity items were present; all ambiguity follow-ups are already marked complete. No `design.md` changes were required. `plan.md` and `steps.md` were already aligned with the current five-slice execution structure, so they were not changed.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps post-docs verification)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked `design.md` for the plan/steps-owned surfaces: five-slice execution structure, Slice 5 documentation/changelog ownership, speed/effort scoped persistence/startup/resume semantics, mid-system extension/projection/compaction rules, and verification expectations. `plan.md` and `steps.md` remain aligned; all existing inconsistency follow-ups in `design-steps.md` are checked, so no duplicate follow-up items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (post-plan verification no-op)

Read `design-steps.md` for newly added unchecked inconsistency follow-up items after the preceding inconsistency-review pass. No unchecked design-step items were present; all inconsistency follow-ups are already marked complete. No design, plan, or steps changes were required.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps latest verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and latest `implementation.md` notes, then spot-checked referenced provider/request-schema, prompt-request, compaction, and Codex effort surfaces for plan/steps clarity. The plan and steps still present one unambiguous five-slice execution structure with Slice 5 owning docs/changelog and no unchecked ambiguity follow-ups to duplicate.

---

## Ambiguity follow-up execution — 2026-05-30 (latest plan/steps no-op)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items after the preceding ambiguity-review pass. No unchecked ambiguity items were present; all ambiguity follow-ups are already marked complete. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps latest independent verification)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked `design.md` for the plan/steps-owned execution structure and acceptance surfaces. The task files remain aligned on five slices, Slice 5 owns docs/changelog and broad verification, speed/effort remain session-transient on cold resume, and mid-system placement/compaction rules are consistently represented in the task steps. No duplicate `design-steps.md` items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (latest design-steps no-op)

Read `design-steps.md` for newly added unchecked inconsistency follow-up items after the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no actionable follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps final independent verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and latest `implementation.md` notes, then checked representative referenced code surfaces for the remaining plan/steps risk areas: Anthropic request schema gaps, prompt-request mid-system projection/current-user replacement, compaction boundary placement, Codex effort override routing, and shared-config presence-aware resolution. The task plan and steps remain unambiguous: five vertical slices, Slice 5 owns docs/changelog and broad verification, and all existing ambiguity follow-ups in `design-steps.md` are checked. No duplicate follow-up items were added.

---

## Ambiguity follow-up execution — 2026-05-30 (newly added design-steps check)

Read `design-steps.md` for newly added unchecked ambiguity follow-up items after the preceding ambiguity-review pass. No unchecked ambiguity items were present (`unchecked count 0`), so there were no actionable follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps repeat verification)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and latest `implementation.md` notes, then checked `design.md` for the plan/steps-owned execution structure and acceptance surfaces. The task files remain consistent: five vertical slices, Slice 5 owns README/`doc/`/changelog work and broad verification, speed/effort session-transience is represented in design and steps, and mid-system placement/compaction expectations are covered by Slice 4 steps. Existing inconsistency follow-ups in `design-steps.md` are checked; no duplicate follow-up items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (newly added design-steps check)

Read `design-steps.md` for unchecked inconsistency follow-up items added by the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no actionable follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps repeat independent verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and latest `implementation.md` notes, then spot-checked referenced code surfaces for currently-risky plan/step areas: Anthropic closed request schema for `speed`/inline system/`highest`, prompt-request mid-system projection/current-user replacement, compaction `:mid-system` rebuild, Codex effort routing, and shared-config presence-aware accessors. The plan and steps still present a single unambiguous five-slice execution structure, with Slice 5 owning README/`doc/`/changelog and broad verification; all existing ambiguity follow-ups in `design-steps.md` are checked, so no duplicate follow-up items were added.

---

## Ambiguity follow-up execution — 2026-05-30 (latest unchecked-item pass)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps latest repeat verification)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked `design.md` for the execution structure and acceptance surfaces plus representative referenced source/doc paths for implementation status. The task files remain consistent: five vertical slices, Slice 5 owns README/`doc/`/changelog and broad verification, Slice 2/3 cover session-transient speed/effort semantics, and Slice 4 covers mid-system projection/provider/extension/compaction work. Existing inconsistency follow-ups in `design-steps.md` are checked; no duplicate follow-up items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (latest repeat unchecked-item pass)

Read `design-steps.md` for unchecked inconsistency follow-up items added by the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no newly actionable follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps latest no-new-feedback)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then spot-checked referenced implementation surfaces for the plan/steps' known risk areas: Anthropic request validation for `speed`/inline system/`highest`, prompt-request current-user replacement with a pending mid-system tail, compaction `:mid-system` rebuild, Codex effort routing, and shared-config presence-aware startup resolution. The plan and steps remain unambiguous: five vertical slices, Slice 5 owns README/`doc/`/changelog plus broad verification, and all existing ambiguity follow-ups in `design-steps.md` are checked. No duplicate follow-up items were added.

---

## Ambiguity follow-up execution — 2026-05-30 (latest no-new-feedback pass)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps no-new-actionable)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked `design.md` for the plan/steps-owned execution structure and acceptance surfaces. The task files remain consistent: five vertical slices, Slice 5 owns README/`doc/`/changelog and broad verification, Slice 2/3 cover session-transient speed/effort semantics, and Slice 4 covers mid-system projection/provider/extension/compaction work. Existing inconsistency follow-ups in `design-steps.md` are checked; no duplicate follow-up items were added.

---

## Inconsistency follow-up execution — 2026-05-30 (no-new-actionable pass)

Read `design-steps.md` for unchecked inconsistency follow-up items added by the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no newly actionable follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Implementation pass — 2026-05-30 (Slice 1 model catalog)

Completed Slice 1 model-catalog work:

- Added built-in `:opus-4.8` catalog entry with `id` `claude-opus-4-8`, adaptive thinking, 1M context / 128k max tokens, placeholder Opus 4.7 pricing, and `:supports-mid-conversation-system-messages true`.
- Added `:opus-4.8` to `anthropic-json-schema-native-model-keys`, so built-in structured-output capability resolves to `:anthropic/json-schema-output`.
- Added optional `:supports-mid-conversation-system-messages` to the AI `Model` schema so the new model remains schema-valid before Slice 4 expands capability use.
- Added focused catalog/model assertions in `psi.ai.core-test` and `psi.ai.model-registry-test`.
- Added gated `^:integration` Anthropic Models API tests for list/retrieve coverage of `claude-opus-4-8`; without `PSI_LIVE_ANTHROPIC_MODELS_API=1` or `ANTHROPIC_API_KEY`, the tests pass via explicit skip assertions.

Verification:

- `clojure -M:test --focus psi.ai.core-test --focus psi.ai.model-registry-test --focus psi.ai.providers.anthropic-models-api-test` — 23 tests, 170 assertions, 0 failures.
- `clj-kondo --lint components/ai/src/psi/ai/models.clj components/ai/src/psi/ai/schemas.clj components/ai/test/psi/ai/core_test.clj components/ai/test/psi/ai/model_registry_test.clj components/ai/test/psi/ai/providers/anthropic_models_api_test.clj` — clean.

Next concrete work: Slice 2 speed-mode stack.


## Implementation pass — 2026-05-30 (Slice 2 speed-mode stack, first slice)

Implemented the core speed-mode stack:

- Added `speed-mode-schema`, optional session `:speed-mode`, and nil default.
- Added `:session/set-speed-mode`, `set-speed-mode-in!`, command-facing core wrapper, and request-option projection.
- Added presence-aware `shared-config.resolution/resolved-speed-mode` and startup application for new root sessions; `:normal` is preserved as an explicit config/session mask while request shaping treats nil/`:normal` as provider default.
- Added `:psi.agent-session/speed-mode` resolver with nil→`:normal` display semantics and footer `• fast` display.
- Added Anthropic `speed: "fast"` request shaping plus `fast-mode-2026-02-01` beta header, and admitted `:speed` in the closed Anthropic request schema.
- Added OpenAI chat-completions `service_tier: "flex"` shaping for `:fast`; Codex/responses is unchanged and therefore omits speed mode.
- Added focused tests for `/speed` command branches, request-option propagation, shared-config resolution, startup explicit `:normal`, Anthropic request shaping/schema, and OpenAI request shaping.

Verification:

- `clojure -M:test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.commands-test --focus psi.agent-session.prompt-request-test --focus psi.shared-config.resolution-test --focus psi.app-runtime-bootstrap-test --focus psi.app-runtime.footer-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-request-headers-test` — 117 tests, 611 assertions, 0 failures.
- `clj-kondo --lint` on all modified source/test paths — clean.

Remaining Slice 2 work: add cold-resume transience proof and decide whether any additional session mutation/persistence assertions are needed before moving to Slice 3.

## Implementation pass — 2026-05-30 (Slice 2 speed cold-resume transience)

Completed the remaining Slice 2 transience proof:

- Split resume inheritance in `session_state/init.clj` so cold journal resume excludes session-transient `:speed-mode` while preserving common lifecycle inheritance for new/fork paths.
- Added session lifecycle coverage proving a source session with `:speed-mode :fast` resumes a cold journal with canonical session `:speed-mode nil` and resolver display `:normal`.
- Marked the Slice 2 speed session mutation/resolver/startup/cold-resume test step complete.

Verification:

- `clj-paren-repair components/session-state/src/psi/session_state/init.clj components/agent-session/test/psi/agent_session/session_lifecycle_test.clj` — no changes needed.
- `clojure -M:test --focus psi.agent-session.session-lifecycle-test` — 11 tests, 119 assertions, 0 failures.
- `clj-kondo --lint components/session-state/src/psi/session_state/init.clj components/agent-session/test/psi/agent_session/session_lifecycle_test.clj` — clean.

Slice 2 is now complete. Next concrete work: Slice 3 effort override and adaptive `:xhigh` stack.

## Implementation pass — 2026-05-30 — Slice 3 effort override

Implemented Slice 3 effort override stack:

- Added session-state `:effort-override` schema/default, dispatch mutation, core/session-settings API, request-options propagation, EQL resolver, startup config resolution, and cold-resume transience exclusion.
- Added `/effort` command module with `low|medium|high|xhigh|none` plus optional `session|project|user` scope; wired command list/help/dispatch.
- Updated adaptive Anthropic effort shaping so level-derived `:xhigh` and override `:xhigh` send `output_config.effort = "highest"`; local Anthropic request schema now accepts `"highest"`.
- Updated OpenAI chat-completions and Codex/responses to share effort override mapping, with `:xhigh` capped to provider `"high"`.
- Updated display surfaces so effective reasoning `:xhigh` remains visible as `xhigh`, and footer appends `• effort:<value>` while thinking is on.
- Added focused coverage for command branches, request option projection, shared-config explicit nil presence, startup application, cold-resume transience, Anthropic adaptive xhigh/override shaping, OpenAI chat-completions override shaping, and Codex/responses override shaping.

Verification:

- `clojure -M:test --focus psi.agent-session.commands-test --focus psi.agent-session.prompt-request-test --focus psi.shared-config.resolution-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test --focus psi.app-runtime-bootstrap-test --focus psi.agent-session.session-lifecycle-test` — 131 tests, 685 assertions, 0 failures.
- `clj-kondo --lint` on modified source/test paths — clean.

Slice 3 is complete. Next concrete work: Slice 4 mid-conversation system messages.

## Implementation pass — 2026-05-30 — Slice 4 mid-system foundation

Implemented the first concrete Slice 4 mid-conversation system-message slice:

- Added `:system` to the AI message role schema and `conv/add-system-message` for schema-valid inline system messages.
- Added `:mid-system` to the session journal entry-kind schema.
- Extended journal projection so `:mid-system` entries emit provider-style `{:role "system" :content [{:type :text :text ...}]}` messages while ignoring intervening non-conversational metadata.
- Updated prepared-turn current-user replacement to preserve a pending `... user, system` tail as `... current-user, system`.
- Extended turn-runtime conversation assembly to normalize provider-style system text blocks into canonical AI `:system` messages.
- Extended Anthropic transformation/schema support for inline system messages, with placement validation that allows final `user → system` requests and drops/logs invalid beginning, consecutive-system, or after-assistant system messages.
- Extended OpenAI chat-completions transformation to map internal `:system` messages to wire role `"system"`.
- Added focused tests for journal projection, current-user replacement, conversation normalization, Anthropic transform/schema acceptance, and OpenAI transform.

Verification:

- `clojure -M:test --focus psi.agent-session.prompt-request-test --focus psi.agent-session.conversation-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test` — 65 tests, 322 assertions, 0 failures.
- `clj-kondo --lint` on modified source/test paths — clean.

Remaining Slice 4 work: shared mid-system capability predicate/resolver, dispatch injection handler, extension API/Pathom mutation wiring, compaction preservation, and their focused tests.

## Implementation pass — 2026-05-30 — Slice 4 capability, injection, and extension API

Implemented the next Slice 4 mid-system vertical slice:

- Added `psi.agent-session.model-capabilities` with a shared runtime-active-model lookup and `supports-mid-system-messages?` predicate. Explicit model metadata enables Opus 4.8; OpenAI chat-completions support is inferred from runtime model shape (`:provider :openai`, `:api :openai-completions`) so custom/runtime maps do not need psi-specific metadata.
- Added `:psi.agent-session/model-supports-mid-system-messages` resolver using the shared predicate.
- Added `:session/inject-mid-system-message` dispatch handler with capability gating, placement validation over conversational entries only, non-conversational metadata ignored, and journal append of schema-valid `:mid-system` entries with source provenance.
- Added `inject-mid-system-message` public session API.
- Added extension API helper `:inject-mid-system-message`, Pathom mutation `psi.extension/inject-mid-system-message`, optional `:source` / `:ext-path` provenance inference, mutation registration, and session-scoped runtime EQL routing.
- Added focused tests for resolver support on Opus 4.8, OpenAI chat-completions inference, custom OpenAI chat maps, unsupported Anthropic/Codex cases, dispatch success/rejection/no-mutation behavior, metadata-after-user placement, and extension API result normalization/provenance params.

Verification:

- `clojure -M:test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.extensions-test` — 36 tests, 273 assertions, 0 failures.
- `clojure -M:test --focus psi.agent-session.commands-test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.extensions-test --focus psi.agent-session.prompt-request-test --focus psi.agent-session.conversation-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test` — 153 tests, 817 assertions, 0 failures.
- `clj-kondo --lint` on modified source/test paths — clean.

Remaining Slice 4 work: compaction preservation and tests, plus any broader focused verification after compaction lands.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps current implementation-state verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked representative referenced implementation/test surfaces for the current Slice 4 state: mid-system capability resolver/dispatch/extension mutation wiring, provider message transforms, prompt-request projection/current-user replacement, and the still-open compaction/doc verification steps. The plan and steps remain unambiguous: Slice 4 has completed capability/injection/API work, compaction preservation and related tests remain explicitly unchecked, and Slice 5 owns docs/changelog plus broad verification. Existing ambiguity follow-ups in `design-steps.md` are checked; no duplicate follow-up items were added.
---

## Ambiguity follow-up execution — 2026-05-30 (current implementation-state no-op)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable ambiguity follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required; the remaining unchecked work in `steps.md` is implementation/test work, not design ambiguity follow-up work.

---

## Design inconsistency review pass — 2026-05-30 (steps/test-status alignment)

**New actionable inconsistency found:**

1. **Slice 4 test steps remain unchecked after implementation notes say tests were added** — `implementation.md` records that focused tests were added for journal projection, prepared-turn current-user replacement, conversation normalization, Anthropic inline system transform/schema acceptance, and OpenAI system-role transformation. But the matching Slice 4 test items in `steps.md` are still unchecked. Align the task state by either marking those test steps complete after verifying the tests exist/pass, or revising the implementation note if the tests are incomplete.

---

## Implementation review pass — 2026-05-30

Actionable feedback found:

1. **Mid-system injection bypasses journal persistence IO** — `:session/inject-mid-system-message` appends `:mid-system` with `persist/append-journal-entry-root-update` directly. That mutates in-memory journal state but bypasses the existing `:session/append-journal-entry` persistence IO path, so injected instructions may not be flushed to the session journal on disk. Route the append through the standard journal append handler/effect path or add an equivalent persistence effect, with coverage proving persisted journal IO is requested.

---

## Inconsistency follow-up execution — 2026-05-30 (steps/test-status alignment)

Completed the newly added inconsistency follow-up item in `design-steps.md` by verifying the referenced Slice 4 tests exist and pass, then aligning `steps.md` with the implementation notes:

- `prompt_request_test.clj` covers `journal->provider-messages` projection for `:mid-system` and current-user replacement preserving a pending `user → system` tail.
- `conversation_test.clj` covers normalization of provider-style system messages into canonical AI `:system` messages.
- `anthropic_test.clj` covers valid/invalid inline system transform behavior and local request schema acceptance for inline system messages.
- `openai_test.clj` covers OpenAI chat-completions system-role transformation.

Verification:

- `clojure -M:test --focus psi.agent-session.prompt-request-test --focus psi.agent-session.conversation-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test` — 65 tests, 322 assertions, 0 failures.

Focused verification initially exposed that the current working tree referenced a missing Anthropic message-transform namespace; restored that namespace so tests could load and pass.

---

## Implementation pass — 2026-05-30 — Slice 4 compaction and persistence follow-up

Completed the remaining Slice 4 mid-system compaction slice and addressed the implementation-review persistence concern:

- Added `:mid-system` handling in `compaction/entry->message`, returning provider-style inline system messages.
- Added compaction rebuild preservation for active pre-cut `:mid-system` entries by coalescing them at a valid next-generation boundary.
- Added retained-suffix normalization so preserved instructions are not inserted before already-retained assistant history; completed retained user/assistant exchanges are treated as summarized for rebuilt-message purposes.
- Merged retained boundary `:mid-system` entries with pre-cut active instructions to avoid adjacent system messages.
- Added state-based compaction tests for summary-boundary attachment, retained pending-user attachment, cut advancement over completed user/assistant exchanges, and boundary mid-system merge.
- Added/kept the extracted Anthropic `message_transform` namespace required by the working tree's Anthropic provider refactor.
- Added persistence-effect coverage for `:session/inject-mid-system-message`; injected `:mid-system` entries now request `:persist/session-journal-io` when a flushed journal file is active.

Verification:

- `clj-paren-repair components/agent-session/src/psi/agent_session/compaction.clj components/agent-session/test/psi/agent_session/compaction_test.clj components/ai/src/psi/ai/providers/anthropic/message_transform.clj` — no changes needed.
- `clojure -M:test --focus psi.agent-session.compaction-test --focus psi.ai.providers.anthropic-test` — 27 tests, 185 assertions, 0 failures.

Remaining concrete work: run the broader focused Slice 4 verification after the persistence follow-up, then Slice 5 docs/changelog/coherence.

## Follow-up implementation pass — 2026-05-30 — mid-system journal persistence

Executed the newly added actionable implementation-review follow-up:

- Changed `:session/inject-mid-system-message` so successful injections emit equivalent journal persistence IO instead of only mutating the in-memory journal. The handler now computes the same `persist/persistence-io-request` shape used by the canonical append path and declares `:persist/session-journal-io` when a session file should be updated.
- Extended session-persistence IO gating so `:mid-system` entries are persistence anchors like assistant messages; injected instructions can therefore flush/append to disk even before the next assistant response.
- Added focused coverage proving injected `:mid-system` entries are appended to the persisted session journal file and that the declared effect is `:persist/session-journal-io` with `:op :append-entry`.
- Marked the Slice 4 journal persistence follow-up complete in `steps.md`.

Verification:

- `clojure -M:test --focus psi.agent-session.model-dispatch-test` — 12 tests, 152 assertions, 0 failures.
- `clojure -M:test --focus psi.agent-session.commands-test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.extensions-test --focus psi.agent-session.prompt-request-test --focus psi.agent-session.conversation-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test` — 153 tests, 822 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj components/session-persistence/src/psi/session_persistence/core.clj components/agent-session/test/psi/agent_session/model_dispatch_test.clj components/agent-session/src/psi/agent_session/compaction.clj components/ai/src/psi/ai/providers/anthropic.clj components/ai/src/psi/ai/providers/anthropic/message_transform.clj components/agent-session/test/psi/agent_session/commands_test.clj components/ai/test/psi/ai/providers/openai_test.clj` — clean.

## Design ambiguity review pass — 2026-05-30 (plan/steps current no-new-actionable)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked representative referenced implementation surfaces for the current task state: speed/effort session-transient semantics, mid-system provider/request-schema and extension persistence coverage, compaction preservation steps, and remaining Slice 5 docs/changelog/broad verification work. The plan and steps remain unambiguous: Slice 4 implementation/test work is complete in `steps.md`, Slice 5 owns the remaining docs/changelog/verification items, and all existing ambiguity follow-ups in `design-steps.md` are checked. No duplicate follow-up items were added.

---

## Implementation review pass — 2026-05-30 (compaction persistence/resume)

Actionable feedback found:

1. **Compacted mid-system preservation is runtime-only, not journal-replay-safe** — `compaction/rebuild-messages-from-entries` coalesces pre-cut `:mid-system` instructions into the replacement runtime message list, but the persisted journal only records the compaction entry with the original `:first-kept-entry-id`. `rebuild-messages-from-journal-entries` later reconstructs from the compaction entry and kept journal entries without applying the same preservation/coalescing rules, so a cold resume after compaction can drop pre-cut active mid-system instructions that the design says remain valid for the remainder of the session. Persist the preservation boundary/state or make journal replay use the same mid-system preservation semantics, with a resume/replay test.

---

## Ambiguity follow-up execution — 2026-05-30 (post compaction persistence/resume review)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable ambiguity follow-ups to execute. The only remaining unchecked task items are implementation/Slice 5 verification items in `steps.md`, including the implementation-review compaction replay/cold-resume follow-up; those were not ambiguity follow-ups from `design-steps.md`. No `design.md`, `plan.md`, or `steps.md` changes were required for this pass.

---

## Design inconsistency review pass — 2026-05-30 (post compaction replay review)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked `design.md` for the relevant Slice 4/Slice 5 boundaries. The only current inconsistency-like concern is already captured as the unchecked Slice 4 follow-up in `steps.md`: compacted mid-system preservation must survive journal replay/cold resume. I did not add a duplicate `design-steps.md` item.

## Follow-up implementation pass — 2026-05-30 — compaction journal replay preservation

Executed the newly added actionable implementation-review follow-up for compaction persistence/resume safety:

- Reused the mid-system preservation/coalescing path for `rebuild-messages-from-journal-entries`, so cold journal replay after a compaction entry preserves pre-cut active `:mid-system` instructions rather than only preserving them in the immediate runtime rebuilt message list.
- `rebuild-messages-from-journal-entries` now derives pre-cut entries from the persisted compaction entry's `:first-kept-entry-id`, merges boundary mid-system entries with pre-cut active instructions, and applies the same retained-suffix normalization that avoids retroactive `retained user → system → retained assistant` insertion.
- Added replay-focused compaction tests proving pre-cut `:mid-system` instructions survive after a compaction entry and that completed retained exchanges are normalized before replaying the preserved instruction.
- Marked the new Slice 4 replay/cold-resume preservation step complete in `steps.md`.

Verification:

- `clj-paren-repair components/agent-session/src/psi/agent_session/compaction.clj components/agent-session/test/psi/agent_session/compaction_test.clj` — no changes needed.
- `clojure -M:test --focus psi.agent-session.compaction-test` — 5 tests, 41 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/compaction.clj components/agent-session/test/psi/agent_session/compaction_test.clj` — clean.

---

## Inconsistency follow-up execution — 2026-05-30 (post-replay unchecked-item pass)

Read `design-steps.md` for unchecked inconsistency follow-up items added by the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no newly actionable design follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required. Existing uncommitted test-file modifications were left untouched.

---

## Implementation review pass — 2026-05-30 (post-compaction replay preservation)

Actionable feedback found:

1. **Compaction replay can drop post-compaction history when preserved mid-system text exists** — `rebuild-messages-from-journal-entries` concatenates kept pre-compaction entries with entries after the compaction record, then applies `normalize-retained-suffix-for-mid-system` across the combined message list. If the journal has pre-cut `:mid-system`, a compaction entry, and later post-compaction user/assistant turns, normalization drops everything through the latest assistant, including the post-compaction turns that must remain in replay. Keep post-compaction entries intact while still avoiding retroactive insertion before already-retained assistant history, and add a replay test covering pre-cut `:mid-system` + compaction + later user/assistant history.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps post-replay-feedback verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and the latest `implementation.md` notes, then checked representative referenced surfaces for the remaining open work: compaction replay preservation, Slice 5 documentation/changelog ownership, focused/broad verification, and existing README/`doc/` configuration references. The unchecked compaction replay item in `steps.md` is actionable implementation feedback rather than a plan/steps ambiguity, and Slice 5 remains a clear integration/coherence pass. Existing ambiguity follow-ups in `design-steps.md` are checked; no duplicate follow-up items were added.

---

## Slice 5 integration/coherence pass — 2026-05-30

Completed the user-facing documentation and verification slice:

- Updated `README.md` to surface Claude Opus 4.8 plus the new `/speed` and `/effort` controls and to point extension readers at mid-conversation system-message injection docs.
- Updated `doc/configuration.md` with `:speed-mode` and `:effort-override` config keys, scoped clear/mask semantics, session-transient cold-resume caveats, and adaptive Anthropic `:xhigh` behavior.
- Updated `doc/tui.md` with `/model anthropic claude-opus-4-8`, `/speed`, `/effort`, and footer display behavior.
- Updated `doc/extension-api.md` with `inject-mid-system-message!`, result contracts, placement rules, provenance, and capability query guidance.
- Added `CHANGELOG.md` Unreleased entries for Opus 4.8, speed mode, effort override / `:xhigh`, and extension mid-system injection.
- Aligned two RPC payload tests with the now-distinct provider-agnostic `thinking xhigh` display.

Verification:

- Focused Slice 1–4 regression set: `clojure -M:test --focus psi.ai.model-registry-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test --focus psi.agent-session.commands-test --focus psi.agent-session.prompt-request-test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.compaction-test --focus psi.agent-session.extensions-test --focus psi.agent-session.session-lifecycle-test --focus psi.shared-config.resolution-test --focus psi.app-runtime-bootstrap-test` — 189 tests, 1113 assertions, 0 failures.
- RPC display fix checks: `clojure -M:test --focus psi.rpc-events-test/session-updated-payload-includes-model-metadata-test` and `clojure -M:test --focus psi.rpc-test/session-updated-payload-includes-model-metadata-test` — green.
- Targeted lint: `clj-kondo --lint components/ai/src components/ai/test components/agent-session/src components/agent-session/test components/session-state/src components/shared-config/src components/shared-config/test components/app-runtime/src components/app-runtime/test` — no errors/warnings; existing info-level assertion-message notes only.
- RPC test lint: `clj-kondo --lint components/rpc/test/psi/rpc_events_test.clj components/rpc/test/psi/rpc_test.clj` — clean.
- Full verification: `bb test` — passed.

## Follow-up implementation pass — 2026-05-30 — compaction replay post-history preservation

Executed the newly added actionable implementation-review follow-up for compaction replay:

- Changed `rebuild-messages-from-journal-entries` so mid-system preservation/coalescing is applied only to the kept pre-compaction segment recorded by the compaction boundary.
- Post-compaction journal entries are now replayed after the preserved/normalized boundary messages without being passed through retained-suffix normalization, so later user/assistant history is not dropped.
- Added replay coverage for pre-cut `:mid-system`, a compaction entry, and later post-compaction user/assistant history.
- Marked the Slice 4 replay post-history follow-up step complete in `steps.md`.

Verification:

- `clj-paren-repair components/agent-session/src/psi/agent_session/compaction.clj components/agent-session/test/psi/agent_session/compaction_test.clj` — no changes needed.
- `clojure -M:test --focus psi.agent-session.compaction-test` — 5 tests, 45 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/compaction.clj components/agent-session/test/psi/agent_session/compaction_test.clj` — clean.

---

## Ambiguity follow-up execution — 2026-05-30 (post-replay unchecked-item pass)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable ambiguity follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required. Existing uncommitted implementation/test changes were left untouched.

---

## Design inconsistency review pass — 2026-05-30 (post-final follow-up verification)

**New actionable inconsistency found:**

1. **Full-suite verification is marked complete before a later code/test follow-up** — `steps.md` Slice 5 marks `Run bb test and confirm the full suite is green` complete, and the Slice 5 implementation note records `bb test` passing. But a later compaction replay post-history implementation pass changed source/tests after that full-suite run and only reran focused compaction tests plus targeted lint. The task files therefore imply the final working tree has post-change full-suite verification, while `implementation.md` only proves full-suite verification for the pre-follow-up state. Rerun `bb test` after the latest follow-up (or uncheck/reword the Slice 5 verification step until it is rerun) so steps and implementation evidence describe the same final state.

---

## Inconsistency follow-up execution — 2026-05-30 (post-final full-suite verification)

Completed the newly added inconsistency follow-up item in `design-steps.md`:

- Reran `bb test` after the latest compaction replay code/test follow-up; the full suite passed (`✅ All tests passed`).
- Kept the Slice 5 `bb test` verification step checked because final-state full-suite evidence now matches the current task state.
- Marked the corresponding `design-steps.md` follow-up complete.

Pre-existing uncommitted modifications in `components/agent-session/test/psi/agent_session/commands_test.clj` and `components/ai/test/psi/ai/providers/openai_test.clj` were not changed by this follow-up.

---

## Design ambiguity review pass — 2026-05-30 (final plan/steps verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked representative referenced code/tests/docs for Opus 4.8, `/speed`, `/effort`, mid-system injection, provider shaping, shared-config startup, compaction replay, docs/changelog, and final verification evidence. The plan and steps remain unambiguous: five vertical slices, Slice 5 owns documentation/changelog and broad verification, all listed steps are checked, and all existing ambiguity follow-ups in `design-steps.md` are checked. No duplicate `design-steps.md` item was added. Pre-existing uncommitted edits in `components/agent-session/test/psi/agent_session/commands_test.clj` and `components/ai/test/psi/ai/providers/openai_test.clj` were not touched.

---

## Ambiguity follow-up execution — 2026-05-30 (final unchecked-item pass)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable ambiguity follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required. Pre-existing uncommitted edits in `components/agent-session/test/psi/agent_session/commands_test.clj` and `components/ai/test/psi/ai/providers/openai_test.clj` were left untouched.

---

## Test review pass — 2026-05-30

Actionable test feedback found:

1. **Scoped `/speed` and `/effort` tests do not prove persistence writes** — The design requires project/user scoped updates to persist `:speed-mode` and `:effort-override`, but the current focused command coverage only checks result text and current session state. Existing shared-config/startup tests prove reads, not that `/speed ... project|user` or `/effort ... project|user` write the expected project/user config keys. Add mutation/command tests that assert scoped persistence output for project and user scopes, including explicit `:normal` and nil clear masks.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps final no-new-actionable)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and latest `implementation.md` notes, then checked referenced task surfaces for Opus 4.8, `/speed`, `/effort`, mid-system injection, docs/changelog ownership, final verification evidence, and the existing test-review follow-up. The only unchecked task item is the already-recorded test-review follow-up in `steps.md`; it is not a new plan/steps inconsistency and was not duplicated in `design-steps.md`. Pre-existing uncommitted edits in `components/agent-session/test/psi/agent_session/commands_test.clj` and `components/ai/test/psi/ai/providers/openai_test.clj` were left untouched.

---

## Inconsistency follow-up execution — 2026-05-30 (post-plan no-op)

Read `design-steps.md` for unchecked inconsistency follow-up items added by the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no newly actionable inconsistency follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required. Pre-existing uncommitted edits in `components/agent-session/test/psi/agent_session/commands_test.clj` and `components/ai/test/psi/ai/providers/openai_test.clj` were left untouched.

---

## Test review follow-up execution — 2026-05-30

Completed the newly added test-review follow-up in `steps.md`:

- Added scoped `/speed` command persistence coverage for project and user scopes, including explicit `/speed normal project|user` `:normal` masks.
- Added scoped `/effort` command persistence coverage for project and user scopes, including explicit `/effort none project|user` nil masks while preserving key presence.
- Marked the follow-up done in `steps.md`.

Verification:

- `clojure -M:test --focus psi.agent-session.commands-test` — green, 52 tests / 232 assertions.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/commands_test.clj` — clean.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps post-test-review verification)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked referenced docs/code/test surfaces for the remaining plan/steps risk areas: Opus 4.8 catalog/docs, `/speed` and `/effort` scoped persistence/test follow-up, mid-system extension capability, Anthropic/OpenAI provider request shaping, shared-config startup semantics, and compaction replay preservation. The task plan and steps remain unambiguous: five vertical slices, Slice 5 owns user-facing docs/changelog and broad verification, and the scoped persistence test-review follow-up is already represented in the current task files rather than a new ambiguity. No duplicate `design-steps.md` item was added. Pre-existing uncommitted edits in `components/agent-session/test/psi/agent_session/commands_test.clj` and `components/ai/test/psi/ai/providers/openai_test.clj` were not touched by this ambiguity review.

---

## Ambiguity follow-up execution — 2026-05-30 (post-test-review unchecked-item pass)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable ambiguity follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required. Pre-existing uncommitted edits in `components/ai/test/psi/ai/providers/openai_test.clj` were left untouched.
---

## Test review pass — 2026-05-30 (effort override coverage)

Actionable test feedback found:

1. **Effort override tests only prove the `:xhigh` ceiling path, not ordinary override precedence** — The design acceptance requires non-`xhigh` override values to pass through for Anthropic adaptive, OpenAI chat-completions, and Codex/responses. Existing provider tests cover level-derived medium/high and `:effort-override :xhigh`, but they do not prove that `:effort-override :high` or `:medium` overrides a different thinking level on each provider path. Added one unchecked follow-up in `steps.md`; no duplicate existing step covered this exact gap.

---

## Design inconsistency review pass — 2026-05-30 (post-test-review plan/steps)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and latest `implementation.md` notes, then checked referenced task/code/doc/test surfaces for Opus 4.8, `/speed`, `/effort`, mid-system injection, docs/changelog ownership, final verification evidence, and current follow-up status. The only unchecked task item is the already-recorded test-review follow-up for non-`xhigh` effort override request-shaping coverage; it is test feedback rather than a plan/steps inconsistency, so no duplicate `design-steps.md` item was added. Existing uncommitted test-file changes were not touched.

---

## Inconsistency follow-up execution — 2026-05-30 (post-test-review unchecked-item pass)

Read `design-steps.md` for unchecked inconsistency follow-up items added by the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no newly actionable inconsistency follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required. Existing uncommitted test-file changes were left untouched.

---

## Test review follow-up execution — 2026-05-30 (effort override precedence)

Completed the newly added test-review follow-up in `steps.md`:

- Added Anthropic adaptive request-shaping coverage proving `:effort-override :high` sends `output_config.effort = "high"` over a different `thinking-level`.
- Added OpenAI chat-completions coverage proving `:effort-override :medium` sends `reasoning_effort = "medium"` over a different `thinking-level`.
- Added Codex/responses coverage proving `:effort-override :medium` sends `{"effort" "medium" ...}` over a different `thinking-level`.
- Marked the non-`xhigh` effort override request-shaping follow-up done in `steps.md`.

Verification:

- `clojure -M:test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test` — 46 tests, 258 assertions, 0 failures.
- `clojure -M:test --focus psi.agent-session.commands-test` — 50 tests, 200 assertions, 0 failures.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/commands_test.clj components/agent-session/test/psi/agent_session/commands_speed_effort_test.clj components/ai/test/psi/ai/providers/anthropic_test.clj components/ai/test/psi/ai/providers/openai_test.clj` — clean.

---

## Design ambiguity review pass — 2026-05-30 (plan/steps no-new-actionable after effort coverage)

No new actionable ambiguities found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and recent `implementation.md` notes, then checked representative referenced code/tests/docs for Opus 4.8, `/speed`, `/effort`, mid-system injection, provider request shaping, shared-config startup/resume semantics, compaction replay, docs/changelog, and the latest effort-override test follow-up. The task plan and steps remain unambiguous: five vertical slices, Slice 5 owns documentation/changelog and broad verification, all `steps.md` and `design-steps.md` items are checked, and the latest test-review follow-up has been completed. No duplicate follow-up item was added.

---

## Ambiguity follow-up execution — 2026-05-30 (post-effort-coverage unchecked-item pass)

Read `design-steps.md` for unchecked ambiguity follow-up items added by the preceding ambiguity-review pass. No unchecked ambiguity design-step items were present (`unchecked count 0`), so there were no newly actionable ambiguity follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Design inconsistency review pass — 2026-05-30 (plan/steps post-effort-coverage verification)

No new actionable inconsistencies found. Re-read `plan.md`, `steps.md`, `design-steps.md`, and latest `implementation.md` notes, then checked the task state against representative referenced surfaces for Opus 4.8, `/speed`, `/effort`, mid-system injection, docs/changelog ownership, verification evidence, and follow-up completion. `plan.md` and `steps.md` remain aligned on five vertical slices, Slice 5 owns user-facing docs/changelog plus broad verification, all `steps.md` and `design-steps.md` items are checked, and no duplicate follow-up item was added.

---

## Test review pass — 2026-05-30 (final independent verification)

No new actionable test feedback found. Re-read `design.md`, `plan.md`, `steps.md`, `design-steps.md`, and `implementation.md`; checked the referenced speed/effort command persistence tests, provider effort request-shaping tests, mid-system capability/dispatch/persistence tests, prompt-request/conversation/provider transform tests, compaction replay tests, and docs/changelog-owned acceptance surfaces. Focused verification passed: `clojure -M:test --focus psi.agent-session.commands-speed-effort-test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.compaction-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test` — 65 tests, 495 assertions, 0 failures. No duplicate `steps.md` follow-ups added.

---

## Inconsistency follow-up execution — 2026-05-30 (post-effort-coverage unchecked-item pass)

Read `design-steps.md` for unchecked inconsistency follow-up items added by the preceding inconsistency-review pass. No unchecked design-step items were present (`unchecked count 0`), so there were no newly actionable inconsistency follow-ups to execute. No `design.md`, `plan.md`, or `steps.md` changes were required.

---

## Test-shaper review pass — 2026-05-30

No new actionable test-shaping feedback found. Re-read the task artifacts and applied `.psi/skills/test-shaper/SKILL.md` against the referenced speed/effort command persistence tests, provider request-shaping tests, mid-system dispatch/persistence/projection tests, compaction replay tests, and docs/changelog acceptance surfaces. The current tests are behavior-focused, deterministic, reasonably localized, and cover the key partitions/boundaries added by the prior test-review follow-ups. Focused verification passed: `clojure -M:test --focus psi.agent-session.commands-speed-effort-test --focus psi.agent-session.model-dispatch-test --focus psi.agent-session.compaction-test --focus psi.ai.providers.anthropic-test --focus psi.ai.providers.openai-test` — 65 tests, 495 assertions, 0 failures. No duplicate `steps.md` follow-ups added.

---

## Docs review pass — 2026-05-30

Actionable documentation feedback found:

1. **`doc/configuration.md` overstates extension mutation support for speed/effort** — The "Speed and effort runtime settings" section says "Extension/runtime mutation surfaces use the same `:session`, `:project`, and `:user` scopes as model and thinking settings." The implemented surfaces expose slash commands plus internal/core `set-speed-mode-in!` / `set-effort-override-in!` dispatch wrappers; no `psi.extension/set-speed-mode` or `psi.extension/set-effort-override` Pathom extension mutations are registered. Update the docs to avoid promising extension mutation surfaces, or add and document the actual mutation names if that surface is intended. No duplicate existing follow-up covered this doc accuracy issue.

## Docs follow-up — 2026-05-30

Completed the newly added docs review follow-up:

- Corrected `doc/configuration.md` so the speed/effort runtime-settings section no longer claims extension mutation surfaces exist for `/speed` or `/effort`.
- The docs now explicitly say there is no `psi.extension/set-speed-mode` or `psi.extension/set-effort-override` EQL mutation, and direct users to the interactive `/speed` and `/effort` commands with their optional scope arguments.
- Marked the docs follow-up item complete in `steps.md`.

---

## Docs review pass — 2026-05-30 (extension API key)

Actionable docs issue found: `doc/extension-api.md` documents calling `(:inject-mid-system-message! api)`, but `create-extension-api` exposes the helper in the public API map as `:inject-mid-system-message` (without bang). The examples should use the actual public key or the implementation should expose the documented bang alias; as written, copy/pasted docs fail with a nil function lookup. Existing docs-review follow-ups only covered speed/effort config wording, so this is new and not duplicated.
