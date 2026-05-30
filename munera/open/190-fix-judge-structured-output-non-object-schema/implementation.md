# Implementation notes

2026-05-30 — Design ambiguity review: found two actionable ambiguities: whether JSON `null` must be preserved as `:payload nil`, and whether structured judge retries must re-send the structured-output opts/schema on every retry.

2026-05-30 — Ambiguity follow-up: clarified design that JSON `null` is a valid native structured-output payload and must be preserved as present `:payload nil` without parse error; clarified that structured-output judge retries must pass the original structured-output opts/schema to `execute-judge-turn!` on every retry. Marked both ambiguity design-steps complete.
