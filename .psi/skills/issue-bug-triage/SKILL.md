---
name: issue-bug-triage
description: Triage a GitHub bug issue by attempting reproduction in an issue-specific worktree, then shape either a precise request for more information or a concrete reproduction handoff into bug-fix execution.
---

Use this skill for bug reports, especially GitHub issues labeled for triage where the next important question is whether the bug can be reproduced.

A good bug triage will answer the following:

What behavior is being reported?
Capture the user-visible failure, error, regression, or incorrect behavior as described in the issue.

What behavior was expected?
If the issue makes the intended behavior clear, state it explicitly. If it does not, say that the expectation is only partially inferable rather than inventing certainty.

How should reproduction be attempted?
Distill the smallest concrete reproduction plan you can derive from the issue. Prefer the fewest steps that could realistically demonstrate the problem.

Where should reproduction happen?
Ensure reproduction happens in an issue-specific worktree before attempting investigation so the work is isolated from the current checkout. Treat that worktree as authoritative for reproduction commands, edits, and later implementation if the bug is confirmed.

What was actually observed during reproduction?
Record the concrete attempt, including the commands, actions, or test paths you used when they matter to understanding the result.

Was the bug reproduced?
Conclude with one explicit status:
- `reproducible`
- `not-yet-reproducible`

If the bug was not reproduced, what information is missing?
Ask only for the minimum additional information most likely to unblock reproduction, such as exact steps, environment details, sample input, expected output, actual output, logs, screenshots, or version/commit information.

If the bug was reproduced, what evidence should carry forward?
Leave a concise reproduction handoff that can seed a Munera bug-fix task. Include the reported behavior, expected behavior, concrete reproduction steps, and the observed failing result.

Guidelines:
- Stay grounded in the issue as written and in observed evidence from the reproduction attempt.
- Do not pretend a bug is reproduced when it is not.
- Do not jump into broad implementation planning inside the triage itself.
- Keep the failed-reproduction reply collaborative and specific rather than generic.
- Keep the successful-reproduction handoff concrete enough that a follow-on Munera task can start from facts rather than re-discovery.
- Prefer narrow reproduction over speculative redesign.

Suggested output surfaces:
- reported behavior
- expected behavior
- reproduction plan
- attempted reproduction
- reproduction status
- missing information needed next
- reproduction evidence for implementation handoff
