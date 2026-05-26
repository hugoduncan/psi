# Design follow-up steps

- [x] Clarify where the production scheduler time source is owned and how it is injected into psi-tool, dispatch handlers, timer/effect execution, lifecycle, and any runtime context creation paths.
- [x] Decide whether `:scheduler/create` must require explicit `:created-at` and `:fire-at`, or whether it may accept a mandatory time source and derive missing values; update the design so "mandatory" is one concrete boundary contract.
- [x] Specify how scheduled user-message timestamps in both `:scheduler/deliver` and `:scheduler/drain-queue` receive time, including whether delivery events carry `delivered-at` or handlers receive a time source.
- [x] Define the exact scheduler-owned wall-clock search boundary: list the namespaces/files that must be free of direct `Instant/now` and explicitly exclude any remaining test/runtime timer wall-clock use that is outside this task.
- [x] Choose the test time-source helper location and shape, and state whether it is production API, test support only, or a documented map/function contract.
- [x] Create the task execution checklist `steps.md` (or explicitly state why `design-steps.md` is the only checklist) so the task artifacts match the Munera protocol and review instructions that reference `steps.md`.
- [x] Correct the scheduler-owned wall-clock search boundary so `components/agent-session/src/psi/agent_session/scheduler_time.clj` is listed and the `scheduler-time/system-time-source` wall-clock exception is attached to that file, not to `context.clj`.
