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
