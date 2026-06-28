# Acceptance-Criteria Traceability (Story #76)

| Story criterion | Covered by this contract |
|---|---|
| 1. Canonical request validation | Sections 1, 8 |
| 2. Handshake gate + compatibility | Sections 2, 8 |
| 3. Canonical response/error/event envelopes | Section 1 |
| 4. Pending request lifecycle | Section 3 |
| 5. Event stream interleaving | Sections 7, 1 |
| 6. Event topic/payload catalog compliance | Section 7 |
| 7. `query_eql` graph/memory parity | Sections 4, 5 |
| 8. stdout discipline | Section 1 (Transport discipline) |
| 9. Tests (contract target) | Sections 1–8 define testable rules |

## Out-of-Scope Reminders

- No protocol version bump (stay `1.0`).
- Event topics must remain within the `rpc-edn.allium` catalog.
- No HTTP transport.
- No Emacs rendering internals.
