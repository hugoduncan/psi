# Preserve custom provider auth resolution when session model providers are stored as strings

## Goal

Make provider-scoped auth resolution for custom model providers work when the selected session model stores `:provider` as a string, so Anthropic-compatible custom providers such as MiniMax do not regress to the built-in Anthropic missing-key failure.

## Context

A user reports that after upgrading to `35dfa40af91023c802ce86f9124d5e813056dd69`, using a custom provider such as:

```clojure
{:version 1
 :providers
 {"minimax"
  {:base-url "https://api.minimax.io/anthropic"
   :api      :anthropic-messages
   :auth     {:api-key "..."}
   :models   [{:id "MiniMax-M2.7"}]}}}
```

again fails with:

```text
Missing Anthropic API key. Set ANTHROPIC_API_KEY or login via /login anthropic.
```

Task `067-custom-provider-anthropic-auth` already established the intended invariant:

- `:api` selects the transport / protocol implementation
- `:provider` selects provider-scoped configuration such as auth and base URL

That fix appears to work when provider identity is represented as a keyword, but real session state stores `:model :provider` as a string. The shared provider-auth path therefore appears to succeed in focused keyword-shaped tests while failing in the live session-shaped path.

## Why

This is a regression in a core extensibility path.

Users who configure Anthropic-compatible custom providers should not need to duplicate credentials into built-in Anthropic auth surfaces just because the live session model stores provider identity in string form.

More generally, provider identity shape must not silently change auth behavior between:

- resolved runtime model maps
- persisted/live session model maps
- prompt preparation
- runtime helper paths

## Observed seam

The current system has these two shapes in play:

- model registry auth keys are provider keywords, e.g. `:minimax`
- live session state stores model providers as strings, e.g. `"minimax"`

The shared provider-auth layer is the correct boundary for this mismatch. If provider normalization is not handled there, canonical prompt preparation can lose custom-provider auth before transport execution, which then falls through to transport-specific built-in missing-key behavior.

## Scope

Investigate and fix provider-scoped auth resolution so custom provider auth lookup is shape-stable across string and keyword provider identities.

The task must cover:

- canonical prepared-request auth shaping
- runtime helper auth shaping
- the specific regression path for custom `:anthropic-messages` providers
- provider-request option lookup when it depends on the same shared provider-identity seam

Do not widen beyond shared provider-auth lookup paths that use provider identity as a registry key.

## Constraints

- Preserve the architectural invariant from task 067: transport identity and provider identity remain distinct.
- Fix the root cause at the shared provider-auth boundary, not by adding MiniMax- or Anthropic-specific special cases.
- Preserve explicit per-call runtime overrides such as `:runtime-opts {:api-key ...}`.
- Preserve current custom-provider auth semantics for keyword-shaped provider identities.
- Preserve the current built-in Anthropic missing-auth behavior when no selected-provider auth exists.
- Add regression coverage that uses real session-shaped string provider identities, not only keyword test fixtures.

## Required behavior

For a selected custom provider stored in live session data as `{:model {:provider "minimax" :id "MiniMax-M2.7"}}`:

- provider auth lookup must resolve the configured custom-provider auth
- prepared request options must include the custom provider API key when appropriate
- runtime helper auth lookup must return the same effective provider auth
- provider-request option lookup must use the same selected provider identity when it depends on the same shared provider-auth seam
- canonical request preparation and runtime helper auth resolution must supply selected-provider auth so the custom `:anthropic-messages` path does not surface the built-in Anthropic missing-key error when selected-provider auth exists

For keyword-shaped provider identities such as `:minimax`:

- preserve existing working behavior

For built-in Anthropic without configured auth:

- preserve current missing-auth behavior

## Acceptance

- A custom Anthropic-compatible provider with inline auth still works when live session data stores `:model :provider` as a string.
- `prompt-request/session->request-options` resolves provider auth correctly for both string and keyword provider identities.
- `runtime/resolve-api-key-in` resolves provider auth correctly for both string and keyword provider identities.
- Any provider-request option lookup that shares the same provider-identity seam is also correct for both string and keyword provider identities.
- At least one new regression test uses live session-data shape `{:model {:provider "minimax" :id "MiniMax-M2.7"}}` and fails on the pre-fix implementation.
- The new string-provider regression tests pass after the fix.
- The fix is implemented at a shared provider-auth boundary rather than duplicated across callers.

## Suggested verification surface

- focused `prompt_request` tests using live session-data shape with string-shaped provider identities
- focused `runtime` auth-resolution tests using string-shaped provider identities
- focused coverage for provider-request option lookup if it shares the same provider-identity seam
- one regression proof showing the custom `:anthropic-messages` path no longer surfaces the built-in Anthropic missing-key error when selected-provider auth is present
