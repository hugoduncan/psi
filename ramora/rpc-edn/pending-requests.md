# Pending Request Lifecycle Policy

- Accepted request adds pending entry `id -> op`.
- Terminal `:response` or `:error` with matching `:id` clears entry.
- Enforce `max_pending_requests` guard.
- Duplicate/invalid IDs return canonical request errors (no transport crash).
