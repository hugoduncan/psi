# Canonical Error-Code Taxonomy

Minimum set for Story #76.

## Transport
- `transport/not-ready`
- `transport/invalid-frame`
- `transport/max-pending-exceeded`

## Protocol
- `protocol/unsupported-version`
- `protocol/invalid-envelope`

## Request
- `request/invalid-id`
- `request/invalid-op`
- `request/invalid-params`
- `request/invalid-query`
- `request/op-not-supported`
- `request/session-not-idle`
- `request/not-found`
- `request/unknown-model`
- `request/unsupported-model` — model exists in the catalog/request surface but is unavailable for the current runtime policy; for example direct RPC `set_model` with OpenAI OAuth-backed `gpt-5.6` until an evidenced ChatGPT/Codex alias or alternate OAuth-compatible transport is added.

## Runtime
- `runtime/query-failed`
- `runtime/failed`
