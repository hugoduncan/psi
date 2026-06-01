# 202 — Unified review follow-up step

## Intent

Make **every** Munera review workflow use **one** shared follow-up definition.
Today each review workflow carries its own follow-up prompt(s); they all do the
same job. Replace them with **one shared follow-up `.md` per scope profile**
(two files) referenced by `review-task-design` (`design` profile),
`review-task-plan` (`steps` profile), and the `review-step` sub-workflow
(`steps` profile) that `review-task-implementation` delegates to. (A single
file parameterized per-host is not feasible in the current workflow grammar —
see "Sharing mechanism (resolved)".)

## Problem

There are currently five near-identical follow-up prompts across the review family:

- `review-task-design-ambiguity-follow-up.md`
- `review-task-design-inconsistency-follow-up.md`
- `review-task-plan-ambiguity-follow-up.md`
- `review-task-plan-inconsistency-follow-up.md`
- the inline follow-up template inside `review-step.edn`

(Task 201 would add a sixth for architectural-fit if nothing changes.)

Every one of them implements the same contract: *execute only the unchecked items
the preceding review pass just added, update the relevant artifacts, mark completed
items done, leave blocked items unchecked with a terse `implementation.md` reason,
and commit.* A wording or behaviour fix must be applied N times and silently drifts
when it is not.

Two observations make unification clean:

1. **The follow-up's behaviour does not branch on the review aspect.** None of the
   follow-ups *do anything different* per aspect — the contract (execute the
   newly-added items, update artifacts, mark done / record blockers, commit) is
   identical across aspects, so aspect is *not* a follow-up parameter. The
   design/plan follow-ups currently *name* their specific preceding review step
   ("preceding **ambiguity-review** pass" vs "preceding **inconsistency-review**
   pass"); that named reference is **functional**, not cosmetic — it tells the
   agent which review step's just-added items to execute. Because one shared
   profile follow-up file is referenced by **both** the ambiguity-follow-up and
   inconsistency-follow-up host steps, the shared file **cannot** name a single
   preceding step and must generalize the wording to "the preceding review pass"
   (see I1 resolution under "Aspect generalization (resolved)"). This is a
   deliberate generalization of the current per-step named references, not a loss
   of behaviour: each host still routes the follow-up so that the immediately
   preceding pass is the one whose newly-added items the follow-up acts on.

2. **Looping is a host concern, not a follow-up concern.** The difference between
   "loop back to review until clean" (`review-step`) and "advance to the next
   aspect" (design/plan) lives entirely in the host step's judge/`:on` routing, not
   in the follow-up. A shared follow-up step therefore drops into every host
   regardless of its loop shape; each host keeps its own routing.

What genuinely varies reduces to a **scope profile**:

| profile  | items file        | writable artifacts                         | read-only context        | forbidden             |
|----------|-------------------|--------------------------------------------|---------------------------|-----------------------|
| `design` | `design-steps.md` | `design.md`, `design-steps.md`, `implementation.md` | —                  | `plan.md`, `steps.md` |
| `steps`  | `steps.md`        | `plan.md`, `steps.md`, `implementation.md`, plus the referenced code/tests/docs (implementation follow-ups) | `design.md`               | —                     |

(`review-task-plan` and `review-step`/implementation both use the `steps` profile.)

Notes on the profile table:

- **`design-steps.md` and `steps.md` are always writable in their own profile**:
  the follow-up marks completed items done there. They are listed as the items
  file *and* (implicitly) writable; the "writable artifacts" column lists the
  additional content artifacts the follow-up may rewrite.
- **Read-only context vs writable (resolves A3).** The current `review-step`
  follow-up *reads* `design.md, plan.md, steps.md, implementation.md` as context
  but only *writes* `steps.md`/task artifacts in the `steps` sense. The unified
  `steps` profile keeps `design.md` as **read-only context** (the follow-up may
  consult the task design while executing plan/implementation follow-ups) and
  does **not** grant write access to `design.md`. This is **not** a behaviour
  change: today's plan/`review-step` follow-ups never edit `design.md`. The
  `design` profile, conversely, never reads or writes `plan.md`/`steps.md`
  (forbidden), matching the current design follow-up's explicit "Do not touch
  plan.md or steps.md".
- **`design` profile writable set (resolves A3, second clause).** The original
  table omitted `design-steps.md` from `design`'s writable set even though the
  follow-up must tick items there; it is now listed explicitly. No behaviour
  change — the current design follow-up already marks `design-steps.md` items
  done.
- **`steps` profile writes referenced code/tests/docs (resolves R1).** The
  `steps` profile hosts both plan review (`review-task-plan`) and
  *implementation* review (`review-step` → `review-task-implementation`).
  Implementation-review follow-up items routinely require editing the **actual
  source code, tests, and docs** the items reference — not just task files. The
  original `steps` writable set ("`plan.md`, `steps.md`, `implementation.md`")
  understated this, narrowing the prior inline `review-step` template's broad
  "updating task artifacts as you work" scope. The `steps` profile follow-up
  therefore explicitly permits writing the code/tests/docs a follow-up item
  references, restoring the prior breadth. No behaviour change relative to the
  inline `review-step` template; for plan review there are simply no code/test
  items to edit.

## Scope

### In scope

- A shared follow-up definition implementing the common contract, specialized by
  **scope profile** (`design` vs `steps`) — i.e. the items file and the
  writable/forbidden artifact set. Concretely this is **one shared follow-up
  `.md` per profile** (two files: a `design`-profile follow-up and a
  `steps`-profile follow-up) — see "Sharing mechanism (resolved)" below for why
  per-step parameterization of a single file is not feasible within the current
  grammar.
- Adopting it in all review hosts:
  - `review-task-design` aspect follow-up steps (profile `design`);
  - `review-task-plan` aspect follow-up steps (profile `steps`);
  - the `review-step` sub-workflow's follow-up (profile `steps`), which transitively
    covers `review-task-implementation`.
- Removing the now-redundant per-aspect follow-up prompt files and the inline
  `review-step` follow-up template.
- Preserving each host's existing routing/looping behaviour unchanged (forward
  advance for design/plan; `REPEAT→review` loop with `:max-iterations` for
  `review-step`).

### Out of scope

- Changing host routing/looping behaviour. The loop-vs-forward difference between
  hosts (`review-step` loops back `REPEAT→review`; design/plan advance forward, one
  pass per aspect per invocation) is **intentional** and is preserved exactly. This
  task only shares the follow-up *step*, never the routing around it.
- Consolidating the per-aspect *review* prompts (only the *follow-up* is unified).
- Any workflow-engine / `pass-status-routing` / `constant-routing` changes.

## Concepts (minimum set)

- **Follow-up contract**: execute newly-added unchecked items from the preceding
  review pass; update in-scope artifacts; mark done / record blockers; commit. The
  shared behaviour, currently duplicated. The contract explicitly includes the
  **"do not execute items that predate the preceding review pass"** guard for the
  items file (see A4 resolution below): only the unchecked items the immediately
  preceding review pass just added are executed.
- **Predate-exclusion guard (resolves A4).** The current `plan` follow-ups carry
  "Do not execute items from steps.md that predate the preceding review pass";
  the `design` follow-ups and the inline `review-step` template do not state it
  explicitly. The unified follow-up **preserves and generalizes** this guard for
  **both** profiles: execute only the unchecked items added by the immediately
  preceding review pass, leaving any pre-existing unchecked items untouched. For
  the `steps` profile this is identical to current plan behaviour. For
  `review-step` (also `steps` profile) this is a small, **intentional**
  behaviour tightening — `review-step` previously had no explicit predate guard
  but its `REPEAT→review` loop means each pass should only act on the items the
  prior review just added; the guard makes that intent explicit and prevents
  re-execution of stale items across loop iterations. For the `design` profile
  the guard makes explicit the behaviour the design follow-ups already relied on
  in practice. **Flagged as an intentional behaviour change for `review-step`.**
- **Scope profile**: the only legitimate variation — `design` vs `steps` — bundling
  the items file with the writable/forbidden artifact set.
- **Host routing**: the per-workflow judge/`:on` wiring that decides what happens
  after the follow-up (loop vs advance). Out of scope to change.

## Acceptance criteria

1. Exactly one shared follow-up `.md` exists **per scope profile** — one
   `design`-profile follow-up and one `steps`-profile follow-up (two files
   total). Each implements the common contract for its profile; there is no
   per-aspect duplication. (Per-step parameterization of a single file is not
   feasible in the current grammar — see "Sharing mechanism (resolved)".)
2. `review-task-design` references the `design`-profile follow-up;
   `review-task-plan` and `review-step` reference the `steps`-profile follow-up;
   `review-task-implementation` inherits it via `review-step`.
3. The redundant per-aspect follow-up prompt files (the two design and two plan
   per-aspect follow-up `.md` files) and the inline `review-step` follow-up
   template are removed (no orphans), replaced by the two profile follow-ups.
4. Behaviour is preserved per host: design follow-ups operate on
   `design-steps.md`/`design.md` and never touch `plan.md`/`steps.md`; plan and
   implementation follow-ups operate on `steps.md` and may write
   `plan.md`/`steps.md` — and, for implementation follow-ups, the code, tests,
   and docs the follow-up items reference (matching the prior inline
   `review-step` template's broad "task artifacts" scope).
5. Each host's routing/looping is unchanged; all review workflows still load,
   validate, and run their loops to clean termination.
6. Workflow-definition tests updated/extended to cover the shared follow-up wiring
   across all three hosts.
7. User-facing workflow docs (`doc/workflows.md` / review-workflow reference)
   describe the shared per-profile follow-up steps (two profile follow-ups).

## Architectural alignment

Configuration/prompt change only; no runtime code paths, and **no
workflow-engine / grammar / compiler changes** (see A2 resolution). Directly
serves the project's `one_way` (one obvious follow-up mechanism per profile),
`compose > monolith`, and DRY principles, replacing five+ near-identical copies
with two profile follow-ups (the irreducible variation is the two artifact
scopes; the per-aspect named-step references collapse into one generic
"preceding review pass" wording — see "Aspect generalization (resolved)").
`review-step` already parameterizes the *review* step by `{{skill}}`
via `:workflow-input`; the follow-up cannot be parameterized the same way
because its writable/forbidden artifact instructions are profile-specific prose,
not a single interpolated token — hence one file per profile.

## Relationship to task 201

Task 201 adds the architectural-fit review aspect using the existing dedicated
per-aspect follow-up pattern. If 202 lands first, 201's `architecture-follow-up`
targets the shared follow-up directly (no throwaway prompt). If 201 lands first,
202 folds its follow-up into the shared step. Recommend **202 first** to avoid the
throwaway, since this task touches the follow-up mechanism wholesale anyway.

## Sharing mechanism (resolved)

Verified against `components/workflow-loader/src/psi/workflow_loader/compiler.clj`
(`compile-prompt-workflow-step`, `markdown-body->contribution`, `standard-vars`)
and the host wiring in `.psi/workflows/review-task-design.edn` /
`review-task-plan.edn` / `review-step.edn`.

**A1 — the recommended "per-step constant `:vars`" mechanism is infeasible and is
removed.** When an `.edn` host step uses `:prompt-workflow "foo.md"`, the compiler
reads *only* the referenced `.md`'s body plus that `.md`'s own frontmatter `vars:`
(each binding via `source-spec` to `:workflow-input` / `:workflow-original`) and
the two `standard-vars` (`{{input}}`, `{{original}}`). The compiler does **not**
merge any `:vars` declared on the referencing host step into the prompt; there is
no path for a host to inject a per-step literal profile into a shared `.md`. So a
single shared follow-up file parameterized per-host is not expressible in the
current grammar.

Additionally, `:workflow-input` is the *workflow's* single input (the task id),
shared by every step of a host workflow; a host cannot vary `:workflow-input`
per follow-up step. So "carry the profile via `:workflow-input`" is also not a
per-step injection mechanism for these hosts.

**Chosen mechanism: one shared follow-up `.md` per profile (two files).** Each is
a fully concrete `:prompt-workflow`-referenced `.md` (using only `{{input}}`):

- `review-follow-up-design.md` — `design` profile (items file `design-steps.md`;
  writable `design.md` + `design-steps.md` + `implementation.md`; forbidden
  `plan.md`/`steps.md`).
- `review-follow-up-steps.md` — `steps` profile (items file `steps.md`; writable
  `plan.md` + `steps.md` + `implementation.md`; `design.md` read-only context).

This collapses the five near-identical follow-ups (two design, two plan, one
inline `review-step`) to **two** profile follow-ups — the irreducible variation.
Each host `.edn` references the appropriate profile file via `:prompt-workflow`.

**A2 — reconciliation with the out-of-scope boundary.** The chosen mechanism
stays **entirely within the current grammar**: it uses existing
`:prompt-workflow` references and `{{input}}` only, requiring **no**
workflow-engine, compiler, grammar, `pass-status-routing`, or `constant-routing`
changes. The out-of-scope boundary holds; no scope is moved in.

## Aspect generalization (resolved)

**I1 — the shared follow-up uses generic "preceding review pass" wording.**
Verified against the four per-aspect follow-up `.md` prompts: each names the
*specific* preceding review step it acts after —
`review-task-design-ambiguity-follow-up.md` and
`review-task-plan-ambiguity-follow-up.md` say "preceding **ambiguity-review**
pass"; the two `...-inconsistency-follow-up.md` files say "preceding
**inconsistency-review** pass". That named-step reference is **functional**: it
identifies which review step's just-added unchecked items the follow-up must
execute (not purely cosmetic).

Because one shared `design`-profile follow-up file is referenced by **both** the
ambiguity-follow-up and the inconsistency-follow-up host steps in
`review-task-design` (and the one shared `steps`-profile file likewise by both
ambiguity/inconsistency follow-up steps in `review-task-plan`, plus
`review-step`), a single shared file **cannot** name one specific preceding
step. The shared follow-up therefore **generalizes the wording to "the preceding
review pass"** (no named review step). This is the **deliberate generalization**
of the current per-step named references, made possible because:

- The follow-up's *behaviour* never branched on the aspect (the contract is
  identical); only the prose named a step.
- Each host wires the follow-up immediately after its review step, so "the
  preceding review pass" unambiguously refers to that host's just-run review
  pass at runtime. The shared file does not need to name the aspect to identify
  the correct items.

This corrects the earlier "the aspect is mentioned only cosmetically" framing:
the per-aspect named reference is functional, and unification replaces it with a
single equivalent generic reference rather than dropping it. The two profile
follow-up files (`review-follow-up-design.md`, `review-follow-up-steps.md`)
therefore use "the preceding review pass" wording.

## Resolved decisions

1. **Sharing mechanism.** Resolved above (A1/A2): one shared follow-up `.md` per
   profile; no per-step parameterization; no grammar changes.
2. **Profile encoding.** Resolved by the chosen mechanism: the profile is
   encoded by *which* of the two follow-up files a host references — not by a
   passed value the prompt resolves. This is maximally robust (a host cannot
   express an invalid items-file/artifact-set combination; each file is a single
   valid profile).
