# Handshake and Readiness Policy

- Only `handshake` is allowed before ready.
- Any non-handshake request pre-ready returns:
  - `{:kind :error :id <req-id?> :op <op?> :error-code "transport/not-ready" ...}`
- Unsupported protocol major returns:
  - `:error-code "protocol/unsupported-version"`
  - endpoint transitions to disconnected/non-ready (disconnect behavior)
- Successful handshake negotiates server protocol within major `1` and sets transport ready.
