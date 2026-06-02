# 204 — Steps

Checklist derived from `plan.md`. Build order is dependency-first. Tick each item
with the commit sha / decision when done.

## Slice 1 — `incidental-complexity-finder` skill

- [x] Re-verify live CLI shape: run `bb gordian local --sort total --json` and
      `bb gordian complexity --json`; confirm each emits a `units` array with
      `ns`/`var`/`arity`, `local` carrying `lcc-total` (+ per-dimension burdens),
      `complexity` carrying `cc`. NOTE (P2): `--sort total` here is a
      **selector-only** convenience for ranking display during selection; it is a
      distinct invocation from the `before-local.json` capture below and does not
      affect baseline validity (see P2 reconciliation). VERIFIED: `local` units
      carry `ns`/`var`/`arity`/`lcc-total`/`flow-burden`/`state-burden`/
      `shape-burden`/`abstraction-burden`/`dependency-burden`/`working-set`/
      `findings`/`file`/`line`/`end-line`; `complexity` units carry `cc`.
- [x] Create `.psi/skills/incidental-complexity-finder/SKILL.md` with frontmatter
      (`name`, `description`, `lambda`) consistent with sibling skills
      (`refactoring`, `gordian`, `code-shaper`).
- [x] In SKILL.md, state the scope explicitly: **a single executable unit**;
      encode the false-positive guard (high CC alone is not a target).
- [x] Embed the **fixed verbatim join recipe**: run both lenses in machine form,
      join on `(ns, var, arity)`, compute `gap = lcc-total / max(cc, 1)`.
      (Implemented as a verbatim `jq` recipe; tested live against this repo.)
- [x] Encode the **unmatched-row rule** (A1): inner join keyed on the `local`
      side — a `local` unit with no matching `cc` row is **dropped**, never
      defaulted to `cc=1`; `complexity`-only units are absent; `max(cc,1)` guards
      only the matched zero-cc case.
- [x] Encode the **qualification filter**: a unit qualifies iff
      `lcc-total ≥ 5.0 ∧ gap ≥ 2.0`; rank qualifying units by `gap`; if none
      qualify there is no target (drives early stop).
- [x] Encode the **judgment guard**: read the top 5 qualifying units by `gap`,
      confirm burden is incidental (braiding / state threading / abstraction
      oscillation / helper-chasing / working-set overload on low/moderate CC) and
      not an essential irreducible algorithm; choose the first that passes;
      report no target if none of the top 5 pass.
- [x] Encode the **evidence emission**: chosen target emits `ns`, `var`, `arity`,
      file, line range, `lcc-total` with per-dimension burdens, `cc`, `gap`, the
      `local` findings, and a **coverage hint** (sibling test ns exists? any test
      references the target var?).
- [x] State thresholds (`lcc-total ≥ 5.0`, `gap ≥ 2.0`, top-5 guard depth) are
      explicit and tunable.
- [x] Verify the skill registers/loads (discoverable in skills registry) and,
      run interactively against this repo, produces a target + evidence (or a
      well-formed no-target report). VERIFIED: `load-skills-from-dir` returns the
      skill with zero diagnostics (name/desc/lambda parsed); the join recipe run
      live yields a ranked top-5 candidate list (e.g. `start-tui-runtime!/5`
      gap≈7.03, `print-help!/0` gap≈5.86) — selection produces a target.
- [x] Commit Slice 1 (`⚒ skill: add incidental-complexity-finder`).

## Slice 2 — `task-lifecycle-in-worktree` wrapper workflow

- [x] Create the wrapper workflow. **DEVIATION (D1)**: authored as
      `.psi/workflows/task-lifecycle-in-worktree.**edn**` (multi-step EDN map
      with top-level `:name`/`:description`), **not** `.md`-with-EDN-body. Reason:
      the live `workflow-loader` parser **rejects** any `.md` body that begins
      with an EDN map ("Markdown workflow body must not begin with an EDN
      workflow definition block"); the cited precedent `implement-task-in-worktree.md`
      in fact **fails to load** under the current loader. The real loadable
      multi-step-wrapper precedent is `review-implementation-in-worktree.**edn**`
      (verified: loads via `load-edn-only`, 3-step resolve-worktree → delegate →
      summary). Mirrored that instead. (See implementation.md D1.)
- [x] Add `resolve-worktree` step: `:type :session`, tools
      `["read" "bash" "work-on"]`, contribution template extracts `worktree_path:`
      and the Munera task path from `{{input}}`, calls `work-on` with the
      extracted worktree path, then yields **only** the bare task path on one line.
- [x] Add `lifecycle` step: `:type :delegate`, `:target "task-lifecycle"`,
      `:prompt-string {:type :map :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}`.
- [x] Add a trailing `summary` step (`:type :session`, per resolved P1):
      produce the user-facing terminal summary for the Munera task, mirroring
      `review-implementation-in-worktree.edn`'s `summary` step. Rationale (P1): outer
      step-2 (the delegate into this wrapper) is the `reduce-incidental-complexity`
      workflow's **terminal** step, so the workflow needs a user-facing terminal
      summary; the wrapper's `summary` step is where it is produced. The wrapper is
      therefore a three-step adapter (resolve-worktree → lifecycle → summary),
      structurally identical to the loadable `review-implementation-in-worktree.edn`
      precedent (and to the intended `implement-task-in-worktree` shape); the
      design's "thin two-step adapter" framing is superseded by this resolution.
- [x] Run `clj-paren-repair` on the EDN; verify the workflow parses, loads, and
      is registered. VERIFIED: `clj-paren-repair` Success; `load-workflow-definitions`
      registers `task-lifecycle-in-worktree` (3 steps, types `[:session :delegate
      :session]`, lifecycle target `task-lifecycle`, prompt-string `:map`/`:fields`
      wiring, resolve-worktree tools include `work-on`); zero load errors for it.
- [x] Commit Slice 2 (`⚒ workflow: add task-lifecycle-in-worktree wrapper`).

## Slice 3 — `reduce-incidental-complexity` outer workflow

- [x] Create `.psi/workflows/reduce-incidental-complexity.edn` with `:name`
      `"reduce-incidental-complexity"` and a `:description`.
- [x] Author **step-1** (`:type :session`): tools
      `["read" "bash" "edit" "write" "work-on"]`, skills
      `["incidental-complexity-finder" "gordian" "code-shaper"]`.
- [x] Step-1 prompt: `git fetch origin master`; treat `origin/master` as base.
- [x] Step-1 prompt: apply `incidental-complexity-finder` to choose the single
      highest incidental-complexity unit.
- [x] Step-1 prompt: **early stop** — if no qualifying unit exists, stop and
      report; do **not** create a worktree or task.
- [x] Step-1 prompt: create an isolated worktree via `work-on` based on
      `origin/master`, described from the target (`simplify <target>`).
- [x] Step-1 prompt: capture baselines into the task dir —
      `before-local.json` (`bb gordian local --json`, **bare, no `--sort`**) and
      `before-diagnose.edn` (`bb gordian diagnose --edn`). NOTE (P2): this is
      intentionally a different invocation from the `--sort total` selector call
      above; the Phase-1 before/after comparison is keyed by `(ns, var, arity)`,
      so sort order is irrelevant to baseline validity — `--sort total` need not
      match here. (Carries the design inconsistency-review conclusion into steps.)
- [x] Step-1 prompt: allocate next task id, create `munera/open/NNN-slug/design.md`
      for the generated refactor task; record the concrete task path so Phase-1
      commands use the **worktree-root-relative task-dir path** for baselines.
      NOTE (P3): the `work-on` worktree is already active at this point, so NNN is
      allocated by scanning the **worktree's** `munera/open/ ∪ munera/closed/`
      (the `origin/master`-based checkout where the task is created), per Munera
      `alloc → max(NNN over open/ ∪ closed/) + 1` — **not** the outer checkout's
      task set — avoiding collision with the outer checkout's open tasks (e.g. 204
      itself).
- [x] Step-1 prompt: embed the **two-phase behaviour-preserving contract** in the
      generated `design.md` instructions, lifted verbatim from `design.md`'s
      "Generated task design" section:
  - [x] Phase 0: assess coverage vs `{nominal, edge, boundary}`; add
        characterization tests (state/outputs, no interactions,
        `testing-without-mocks`) if insufficient; tests green against unmodified
        code before refactor; untestable-tangle → seam or close.
  - [x] Phase 1 acceptance A5: target `lcc-total` decreased vs stored
        `munera/open/NNN-slug/before-local.json` (the single authoritative
        baseline), keyed by `(ns, var, arity)`.
  - [x] Phase 1 acceptance A2: net burden over the **metric-derived touched set**
        `{u | before(u) ≠ after(u)}` strictly decreases (`Σ after < Σ before`).
  - [x] Phase 1 acceptance A3: `bb gordian gate --baseline
        munera/open/NNN-slug/before-diagnose.edn --fail-on
        new-cycles,new-high-findings --max-new-medium-findings 0` passes (exit 0).
  - [x] Phase 1: Phase-0 + existing tests green; change minimal/local/decomplecting.
- [x] Step-1 prompt: commit the task creation **on the `work-on` worktree branch**
      (off `origin/master`), so the committed task dir lives on the same branch
      step-2's `resolve-worktree`/`work-on` re-enters (P4). Then emit a
      **structured handoff** block with at minimum `worktree_path:` (absolute) and
      `munera_task_path:` lines (mirroring `gh-issue-implement.edn`'s `design`-step
      handoff). NOTE (P3+P4): `munera_task_path:` only resolves for the delegated
      lifecycle because the task dir was created **and** committed on the worktree
      branch (not in the outer checkout) — task-id allocation, dir creation, and
      commit all happen inside the worktree.
- [x] Author **step-2** (`:type :delegate`): `:target "task-lifecycle-in-worktree"`,
      `:prompt-string {:type :map :fields {:input {:from {:step "<step-1-name>" :yield :text}}}}`.
- [x] Confirm the outer workflow ends with a completed/reviewed task on the local
      worktree branch — **no push/PR**, no workflow-level verification step.
- [x] Run `clj-paren-repair` on the EDN; verify it parses, loads, and that the
      `incidental-complexity-finder` skill + `task-lifecycle-in-worktree` target
      references resolve. VERIFIED: `clj-paren-repair` Success;
      `load-workflow-definitions "."` registers `reduce-incidental-complexity`
      with zero errors — two steps `[select-and-create lifecycle-in-worktree]`,
      types `[:session :delegate]`; step-1 tools `["read" "bash" "edit" "write"
      "work-on"]` + skills `["incidental-complexity-finder" "gordian" "code-shaper"]`;
      step-2 `:target "task-lifecycle-in-worktree"`, prompt-string
      `{:type :map :fields {:input {:from {:step "select-and-create" :yield :text}}}}`;
      step-1 prompt emits `worktree_path:`/`munera_task_path:`, early-stop,
      gate flags `--fail-on new-cycles,new-high-findings --max-new-medium-findings 0`,
      and both baselines (`before-local.json`/`before-diagnose.edn`).
- [x] Commit Slice 3 (`⚒ workflow: add reduce-incidental-complexity`).

## Slice 4 — verification + definition tests

- [x] Extend `components/workflow-loader/test/.../workflow_definitions_test.clj`:
      assert `reduce-incidental-complexity` and `task-lifecycle-in-worktree`
      parse/load.
- [x] Assert outer two-step shape: step-1 `:session` (with `work-on` tool +
      `incidental-complexity-finder` skill), step-2 `:delegate`
      `:target "task-lifecycle-in-worktree"` with the
      `:prompt-string {:type :map :fields {:input {:from {:step … :yield :text}}}}`
      wiring.
- [x] Assert wrapper three-step shape (per resolved P1): `resolve-worktree`
      `:session` with `work-on` tool; `lifecycle` `:delegate`
      `:target "task-lifecycle"` with `:input` sourced from `resolve-worktree`
      `:yield :text`; trailing `summary` `:session` step present.
- [x] Assert (where the test ns convention supports it) the step-1 prompt emits
      the `worktree_path:` / `munera_task_path:` handoff fields and the early-stop
      intent (R1 lock).
- [x] Assert the `incidental-complexity-finder` skill registers / is discoverable.
- [x] Run focused workflow tests (workflow-loader + relevant agent-session
      workflow definition tests) green.
- [x] Run `clj-kondo --lint` over any changed source/test paths; 0 new findings.
- [x] Commit Slice 4 (`⚒ test: lock reduce-incidental-complexity + wrapper definitions`).

## Slice 5 — docs + coherence

- [x] Update `doc/workflows.md` (and/or the workflow listing it curates) to
      document `reduce-incidental-complexity` and `incidental-complexity-finder`.
- [x] Add a CHANGELOG `[Unreleased] → Added` entry for the new user-visible
      workflow + skill.
- [x] Verify coherence across `design.md` ↔ SKILL.md ↔ both workflow `.edn/.md`
      ↔ docs (names, thresholds, gate flags, handoff fields all consistent).
- [x] Run final focused workflow tests + `clj-kondo`; record results in
      `implementation.md`.
- [x] Commit Slice 5 (`⚒ doc: document reduce-incidental-complexity workflow`).

## Plan/steps ambiguity follow-ups (review pass 1)

- [x] P1 — Resolved: **add** the wrapper `summary` step. Deciding criterion =
      outer step-2 (delegate into the wrapper) is the `reduce-incidental-complexity`
      terminal step, so a user-facing terminal summary is needed; the wrapper's
      `summary` step provides it (mirroring `implement-task-in-worktree`). Wrapper
      is now three steps. Propagated to Slice 2 + Slice 4 wrapper-shape items and
      plan.md. (steps.md updated; no code yet — Slice 2 unbuilt.)
- [x] P2 — Resolved: annotated both `local` invocation sites in steps.md.
      `before-local.json` uses bare `bb gordian local --json` (no `--sort`);
      `--sort total` (selector-only) is irrelevant to the `(ns,var,arity)`-keyed
      before/after, so the baseline is valid regardless of sort. Design
      inconsistency-review conclusion now carried into steps.
- [x] P3 — Resolved: NNN is allocated by scanning the **worktree's**
      `munera/open/ ∪ munera/closed/` (the `origin/master`-based checkout where
      `work-on` is already active), per Munera `alloc → max(NNN)+1` — not the
      outer checkout — avoiding collision with outer open tasks (e.g. 204). Stated
      on the Slice-3 task-id-allocation step.
- [x] P4 — Resolved: the task dir is created **and committed on the `work-on`
      worktree branch** (off `origin/master`); `munera_task_path:` resolves for
      step-2's `resolve-worktree`/`work-on` only because the commit is on that
      branch. Stated on the Slice-3 commit step (allocation, creation, commit all
      inside the worktree).

## Plan/steps inconsistency follow-ups (review pass 1)

- [x] C1 — Resolved by **softening the plan Approach line** (kept the Slice-4
      `components/` assertions). Approach now reads "no new production Clojure and
      no new test **namespace** in `components/` — the Slice-4 assertions extend the
      existing `workflow_definitions_test.clj` ns". Approach, R4, and Slice 4 now
      agree: assertions are added, but only as extensions of the existing
      definition-test ns (no new ns, no production code).
- [x] C2 — Resolved: collapsed the two duplicate Slice-2 `summary`-step items into
      a single "Add a trailing `summary` step…" item, keeping the P1 rationale as a
      sub-note. The checklist now instructs the `summary` step's creation exactly
      once.
- [x] C3 — Resolved: plan.md now names **both** baselines. Key-decisions capture
      bullet names `before-local.json` *and* `before-diagnose.edn` (captured in the
      task dir during step-1); R3's verbatim-reproduction inventory now lists both
      baselines (`before-local.json` → A5, `before-diagnose.edn` → A3 gate source).
      plan.md's baseline set matches steps.md and the gate acceptance it cites.

## Implementation review follow-ups (review pass 1)

- [x] F1 — Early-stop is prompt-only; step-2 runs unconditionally on a
      no-target handoff. RESOLVED at the prompt level (the only available
      mechanism; grammar has no conditional/skip). The wrapper's
      `resolve-worktree` prompt now detects the absence of
      `worktree_path:`/`munera_task_path:` in the handoff, emits a `NO_TARGET`
      sentinel **without** calling `work-on`, and the `summary` step (now also
      sourcing `resolve-worktree`'s `:yield :text`) detects `NO_TARGET` and
      reports a clean "no target this run; nothing done" result instead of
      inspecting a nonexistent task. Definition tests extended to lock both
      short-circuits; both workflows still load (14 tests, 196 assertions, 0
      failures); `clj-kondo`/`cljfmt` clean; `doc/workflows.md` updated for
      coherence. (See implementation.md F1 entry.)

## Implementation review follow-ups (review pass 2)

- [x] F2 — RESOLVED (option (c), re-key on `line`). The skill's fixed `jq` join
      recipe keyed `$ccmap`/the join on
      `(ns + "/" + var + "/" + (arity|tostring))`, but that key is **non-unique**
      when `arity` is `null`: every `defmethod`-style unit emits `arity: null`,
      so all 51 `psi.agent-session.dispatch-effects/execute-effect!` defmethods
      collapse to one key `…/execute-effect!/null` on **both** the `local` and
      `complexity` sides (verified live). `from_entries` then keeps only the
      **last** of the 51 distinct cc values (jq last-wins) — making `cc`/`gap`
      for any null-arity unit **non-deterministic w.r.t. emit order**, which
      contradicts the SKILL's "fixed recipe … so selection is reproducible" claim
      and the A1 "join is total over the shared key space" wording. Currently no
      null-arity unit qualifies (`lcc-total ≥ 5.0 ∧ gap ≥ 2.0`), so the live
      top-5 is unaffected — but a future high-burden null-arity defmethod would
      be mis-ranked / falsely (dis)qualified. Fix the recipe in
      `.psi/skills/incidental-complexity-finder/SKILL.md` to stop relying on
      last-wins `from_entries` over a non-unique key — e.g. (a) exclude
      `null`-arity units explicitly (`select(.arity != null)`, documenting
      arity-aggregated defmethods as out of unit-level scope), or (b) group cc by
      key and reduce (order-independent), or (c) re-key on something unique — and
      correct the A1 "total over the shared key space" prose to acknowledge the
      null-arity collision. Confined to the skill recipe + its A1/A4 prose; no
      workflow/test change forced (optionally assert recipe determinism). (See
      implementation.md pass-2 entry.)
      RESOLUTION: re-keyed the join on `(ns, var, arity, line)` — `line` is
      present on both lenses and fully unique (verified: 0 dups across 3526 cc /
      3520 local units), so `from_entries` is lossless and each null-arity
      defmethod gets its own correct `cc` (no last-wins). New recipe top-5 is
      identical to the documented expected result (no regression); old vs new
      determinism demonstrated live (51 `execute-effect!` units: old → all
      `cc=1`, new → `cc ∈ {1..8}`). A1 prose gained a "Why `line` is part of the
      key (A1 determinism)" paragraph correcting the prior implicit
      `(ns,var,arity)`-uniqueness framing. `doc/workflows.md` join-key mention
      updated for coherence. Skill still registers; both workflows still load
      (14 tests, 196 assertions, 0 failures); clj-kondo clean.

## Contingency (non-planned; only if Slice 3 step-1 proves unwieldy)

- [ ] Split step-1 selection from task-creation into two `:session` steps,
      threading selection output forward (accepting added inter-step data flow).
      Per design "Open questions": keep as one step unless it proves unwieldy.
