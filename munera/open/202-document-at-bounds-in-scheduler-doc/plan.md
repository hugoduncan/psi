# Plan — Document absolute-`:at` delay bounds in `doc/scheduler.md`

## Approach

This is a documentation-only task. Preserve scheduler behaviour and tests as the source of truth, then align `doc/scheduler.md` with the existing `resolve-fire-time!` / `validate-delay-ms!` behaviour and the task 201 verification matrix.

Key decisions:

- Describe absolute `:at` bounds in terms of the resolved millisecond delay, not the raw instant.
- State that resolved delay `0` fires immediately, including past/now and sub-millisecond future instants that truncate to `0ms`.
- State that positive resolved delays below the minimum (`1–999ms`) are rejected by the minimum bound.
- State that positive resolved delays above the maximum (`>24h`) are rejected by the maximum bound.
- Do not change scheduler implementation or add new scheduler behaviour tests; existing task 201 verification coverage is the behavioural proof.

## Risks

- Wording could overclaim that every future instant below `1000ms` is rejected, which would be wrong for sub-millisecond futures that truncate to `0ms`.
- Wording could imply relative `:delay-ms 0` is accepted; it is not. The immediate-fire rule is specific to absolute `:at` after resolution to delay `0`.
- Behavioural drift risk is low because this task does not change code, but the doc should still be checked against `resolve-fire-time!` and the existing scheduler verification test names/claims.

## Slice order

1. **Behaviour grounding** — Re-read the scheduler design, current scheduler doc, `resolve-fire-time!`, and the task 201 verification matrix to confirm the exact absolute-`:at` boundary cases.
2. **Documentation update** — Update `doc/scheduler.md` "Create validation rules" so the bounds section explicitly covers delay-0, positive below-minimum, and above-maximum absolute-`:at` resolutions.
3. **Coherence verification** — Re-read the updated doc and compare it with the design and existing verification proof; optionally run the focused scheduler verification test if the implementation context is already loaded.
4. **Task record update** — Record the documentation decision/evidence in `implementation.md` and check completed items in `steps.md` when executing the task.
