---
title: Editing workflow emitter prompts and their content-lock tests
status: active
category: tooling
tags: [workflows, emitter, content-lock, tests, edn, kaocha, classpath]
related: ["munera/closed/215-correct-incidental-complexity-a2-gate", "munera/closed/209-incidental-complexity-simplification-workflow", ".psi/workflows/reduce-incidental-complexity.edn", "src/test/.../task_209_workflow_definitions_test.clj"]
depends-on: []
---

Several `.psi/workflows/*.edn` definitions are **emitters**: a step's `:text` is a large
prompt string that, when run, emits another artifact (e.g. a generated Munera
`design.md` with numbered acceptance criteria). Changing what such a workflow emits means
editing that string and re-pointing the tests that lock its substrings. Two recurring
traps and a test-running gotcha, learned across tasks 204/209/215.

## Trap 1 — content-lock substring attribution (undercount)

Content-lock tests assert `(.contains select-text "…")` over the loaded prompt. When you
change emitted text, the obvious set of broken locks is usually an **undercount**: a
substring belongs to whichever emitted bullet actually produces it — not the bullet it
superficially resembles.

Task 215 case (replacing the net-sum A2 bullet):
- The plan first named "two" broken locks (the literal net-sum strings).
- A **third**, `"identified by (ns, var, arity, line)"`, was emitted **only** by the A2
  bullet and also broke.
- Its near-identical sibling `"keyed by (ns, var, arity, line)"` was emitted by the
  **A5** bullet and had to stay intact.

Near-identical substrings can belong to different criteria.

**Procedure before editing emitted text:**
1. Grep every content-lock substring for the workflow under test.
2. Attribute each to the exact emitting bullet. Occurrence count is the tell —
   `identified by` appeared once (A2-only); `keyed by` appeared twice (A5 + a step note).
3. Disposition each: remove, re-point to the new wording, or leave intact.
4. Don't trust the plan's headline count; verify each removed substring is **absent** and
   each new lock substring is **present** in the loaded `select-text` after the edit.

## Trap 2 — EDN `:text` is a string, not structure

Editing the prompt edits a large string value inside the EDN, not structural Clojure.
When the edit replaces a contiguous span **inside** that string, `clj-paren-repair` is the
wrong gate — it repairs delimiters that were never touched. The correct well-formedness
gate is an `edn/read-string` round-trip of the file (or the `:text` value): if it reads
back, the EDN is intact. (Task 215: a 31765-char `:text`; `bb edn/read-string`
round-tripped.)

Rule of thumb: **paren-repair for structural code edits; `edn/read-string` round-trip for
in-string `:text` span edits in workflow/config EDN.**

## Trap 3 — a contract may be stated twice in one `:text` (what-vs-how)

An emitter `:text` can deliberately state the **same** contract twice: once as the
declarative criterion clause (the *what*) and once as a mechanical procedure (the *how*).
Task 215's A2a/A2b appears both as the acceptance-criterion bullet and as steps 5/6 of
"How A2 is mechanically checked" (the procedure adds `T`-membership the criterion omits).

This is an intentional what-vs-how separation, **but it is a single-source / `λ sync`
hazard**: a future edit that changes the criterion wording must also change the
procedure (and vice versa), or the emitted contract becomes self-inconsistent.
Content-lock tests catch only the substrings they lock — they will not necessarily catch
a what/how *drift*. When editing such a `:text`, grep the whole `:text` for the contract
term (e.g. `A2a`, `after(u) < B`) and update **every** occurrence, criterion and
procedure alike.

(A code-shaper review of task 215 flagged this dual statement, then judged it
not-actionable because it is settled design inside the scoped criterion text — recorded
here as an editing hazard, not a defect to remove.)

## Running the loader content-lock tests

The workflow-loader tests (e.g. `task_209_workflow_definitions_test.clj`,
`reduce-incidental-complexity-test`) read `.psi/workflows/*.edn` via **cwd-relative**
paths, so they must run from the **repo root**, not the component dir. For a focused run:
build an absolute classpath with `-Spath` from the component, then invoke with `-Scp`
from root so the cwd-relative `.psi/workflows/` reads resolve. (Task 215: 3 tests / 196
assertions green from root.)

Pitfall (task 215, pre-existing — not caused by workflow edits): the full kaocha
`--focus` run fails to **load** an unrelated namespace
(`psi.agent_session.tool_execution_test` → missing `psi/metrics/extension` on the
classpath). When that blocks a focused run, isolate the target suite (ad-hoc classpath
above) rather than chasing the unrelated load failure.
