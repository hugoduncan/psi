# Remember Capture Test Matrix

Scope: Step 10 `/remember` manual memory capture contract.

## Coverage Map (Checklist → Tests)

- Exactly-once manual capture per invocation → U1, I1, E1
- Surface parity (REPL/RPC/Emacs) → E1, E2, E3
- Minimal context snapshot fields → U2, I2
- Persist via active provider + recover later session → I3, E4
- No mirrored git-history summaries in memory ranking corpus → U3, I4
- Blocked prerequisites emit `memory_capture_prerequisites_not_ready` → U4, I5, E5
- Stable EQL telemetry attrs → U5, I6
- `/resume` persistence distinct from remember/recover semantics → I7, E6
- Negative provider/unavailable behavior → U6, I8, E7

---

## Unit Tests

| ID | Scenario | Arrange | Assert |
|---|---|---|---|
| U1 | Single invocation writes one capture | ready memory + valid reason | capture count increments by 1 only |
| U2 | Snapshot shape is minimal and stable | provide session/cwd/branch context | capture contains `sessionId/cwd/gitBranch/summary/evidence` (nullable where appropriate) |
| U3 | Recovery corpus excludes git-mirrored entries | seed memory + git data | ranking input comes from memory artifacts only |
| U4 | Prereq gating | toggle query/memory readiness false | status `error`, `lastError="memory_capture_prerequisites_not_ready"` |
| U5 | EQL remember attrs resolve | query remember store attrs | `status/captures/lastCaptureAt/lastError` present and typed |
| U6 | Provider failure path | mock provider write failure | failure surfaced with explicit fallback metadata; no silent success |

## Integration Tests

| ID | Scenario | Arrange | Assert |
|---|---|---|---|
| I1 | `/remember` command pipeline | running session + ready memory | one memory write-through call + success response |
| I2 | Context extraction from live session | run in repo with branch/cwd | stored capture reflects current session metadata |
| I3 | Cross-session recovery | write capture in session A, new session B recover | capture is retrievable/used in recovery |
| I4 | Git history remains query-only | run remember after git activity | no git-log/diff payload persisted as memory artifact |
| I5 | Not-ready memory layer | memory status != ready | command fails with canonical blocked error |
| I6 | EQL telemetry stability | perform remember + query attrs | attrs available before/after capture; counts/timestamps evolve |
| I7 | `/resume` separation | persist session journal, resume, inspect memory | resumed transcript != implicit remember artifact creation |
| I8 | Fallback/unavailable provider behavior | persistent provider unavailable with fallback modes | explicit selection/failure telemetry and user-facing fallback warning (`⚠ Remembered with store fallback …`) |

## End-to-End Tests

| ID | Surface | Scenario | Assert |
|---|---|---|---|
| E1 | REPL | `/remember "reason"` once | one capture created, user-visible confirmation |
| E2 | RPC-EDN | remember command op/flow | same semantics and result shape as REPL |
| E3 | Emacs | interactive remember command | same semantics and visibility as REPL/RPC |
| E4 | Cross-session | capture then restart/new session recover | recovered artifact is visible/usable |
| E5 | Failure UX | invoke remember when memory not ready | clear blocked message with canonical error key |
| E6 | Resume boundary | `/resume` old session then query memory captures | no synthetic remember captures from resume alone |
| E7 | Provider outage | active provider fails mid-run | deterministic fallback warning + telemetry, no duplicate writes |

---

## Suggested Test Placement

- Unit: `components/memory/test/...`, `components/agent-session/test/...remember*`
- Integration: `components/agent-session/test/.../commands_*`, memory-store integration namespaces
- E2E: existing REPL/RPC/Emacs acceptance suites (add remember-focused scenarios)

## Runtime Warning Contract (store fallback)

When `/remember` succeeds in-memory but active provider write fails, surfaces should
emit a warning (not silent success):

- Prefix: `⚠ Remembered with store fallback`
- Include `provider` when available
- Include `store-error` when available
- Include `detail` message when available

This warning must be consistent across REPL, RPC, and Emacs-visible transcript output.

## Pass Criteria

- All matrix IDs implemented and green.
- No regression in existing memory/session persistence tests.
- Telemetry attrs remain queryable and documented.
- Store fallback warning contract is documented and tested across surfaces.
