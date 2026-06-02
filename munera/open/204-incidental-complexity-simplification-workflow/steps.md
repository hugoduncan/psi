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

## Implementation review follow-ups (review pass 3)

- [x] F3 — F2's `(ns, var, arity, line)` join-key uniqueness fix did not
      propagate to the **generated design contract's** acceptance keys. The
      generated `design.md` template embedded in
      `.psi/workflows/reduce-incidental-complexity.edn` (and `design.md` line
      217) still keys Phase-1 **A5** ("target `lcc-total` decreased … keyed by
      `(ns, var, arity)`") and the **A2** touched-set identity
      `{u | before(u) != after(u)}` on the **non-unique** `(ns, var, arity)`
      key — the same null-arity-`defmethod` collision F2 fixed in the selector
      recipe (all 51 `execute-effect!` defmethods share key
      `…/execute-effect!/null`). Guarded today only by the tunable threshold
      (no null-arity unit currently reaches `lcc-total ≥ 5.0`), but a future
      high-burden defmethod target would be mis-compared. Fix: propagate F2's
      keying into the A5/A2 wording in `reduce-incidental-complexity.edn` (key
      on `(ns, var, arity, line)` to match the selector) and the matching
      `design.md` A5 line — or state explicitly that null-arity units are out of
      unit-level acceptance scope. Generated-design prose + `design.md` only; no
      forced test change (optionally assert the key string for coherence).
      RESOLUTION: keyed A5/A2 (and the P2 before/after note) on
      `(ns, var, arity, line)` to match the selector's unique join key in both
      the generated-contract prose in `reduce-incidental-complexity.edn`
      (step-7) and the mirrored `design.md` Phase-1 acceptance, each with an
      inline note that `line` disambiguates same-named null-arity `defmethod`
      units (the 51 `execute-effect!` defmethods). Chose key-matching over
      out-of-scope declaration so selector and acceptance share one key space.
      `(ns, var, arity)` left intact where it denotes a unit's logical identity
      (per the pass-2 SKILL distinction). `doc/workflows.md`'s F2 join-key
      mention already coheres (it describes the selector join, not A5/A2). EDN
      still parses (`clj-paren-repair` Success); both workflows still load
      (14 tests, 196 assertions, 0 failures); no `.clj` change → no clj-kondo
      delta. (See implementation.md pass-3 F3 resolution entry.)

## Implementation review follow-ups (review pass 4)

- [x] F4 — `design.md` line 62 (Deliverable 1, **selector** procedure step 2)
      still describes the selector join as "Join on `(ns, var, arity)`" — the
      pre-F2 key. F2 changed the implemented `incidental-complexity-finder`
      SKILL.md recipe to join on `(ns, var, arity, line)` (the `@line` key) for
      null-arity `defmethod` determinism, and F3 propagated that key into the
      A5/A2 acceptance prose (design lines 217/232) — but the selector join
      description at line 62 was left on the old `(ns, var, arity)` key. Residual
      design(spec)↔SKILL(mechanism) coherence gap; threshold-guarded today (no
      null-arity unit reaches `lcc-total ≥ 5.0`), so behaviour is unaffected.
      Fix: update `design.md` step 2 to "Join on `(ns, var, arity, line)`" with a
      one-clause note that `line` disambiguates same-named null-arity `defmethod`
      units (mirroring the A5/A2 lines and SKILL §3 rationale). `design.md` prose
      only; no workflow/skill/test change forced — the recipe is already correct.
      (See implementation.md pass-4 F4 entry.)
      RESOLUTION: updated `design.md` step 2 (selector procedure) to "Join on
      `(ns, var, arity, line)`" with a parenthetical note that `line`
      disambiguates same-named null-arity `defmethod` units sharing
      `(ns, var, arity)` (the 51 `execute-effect!` defmethods), cross-referencing
      the A5/A2 acceptance and SKILL.md §2/§3. The selector join (line 62) and the
      A5/A2 acceptance (lines 220, 235) now share one key string; the remaining
      bare `(ns, var, arity)` occurrences (lines 64, 222) deliberately denote a
      unit's *logical identity* (the collision `line` resolves), consistent with
      the SKILL §3 distinction. `design.md` prose only — no workflow/skill/test
      change (the SKILL recipe already keys on `@line`); SKILL §2/§3 cross-ref
      verified accurate. design(spec)↔SKILL(mechanism) coherence gap closed.

## Test review follow-ups (review pass 1)

- [x] TR1 — The `incidental-complexity-finder` skill's **core behaviours** are
      untested. `incidental-complexity-finder-skill-registers-test` asserts only
      discovery + a non-empty `:description` + zero diagnostics. None of the
      design Deliverable-1 behaviours the skill exists to encode are covered by
      any test: the `gap = lcc-total / max(cc, 1)` method, the qualification
      thresholds (`lcc-total ≥ 5.0 ∧ gap ≥ 2.0`), the top-5 essential-vs-
      incidental judgment guard, the single-executable-unit scope, the A1
      unmatched-row rule (drop, never default `cc=1`), and — most pointedly —
      the F2 determinism fix (the `(ns, var, arity, line)`/`@line` join key that
      passes 1–5 manual reviews repeatedly leaned on but never locked). A skill
      whose frontmatter is valid but whose recipe body is empty, paraphrased,
      or regressed to the pre-F2 `(ns, var, arity)` key would pass this test
      green. Per the task-test-review criterion `∀b ∈ behaviour(design). ∃t.
      covers(t,b)`, the skill's central behaviour is the design's first
      acceptance criterion ("documents the `gap` method and the false-positive
      guard, is scoped to a single unit") yet has no covering assertion. Add a
      content/behaviour lock for the skill — at minimum substring assertions on
      SKILL.md for the threshold strings (`5.0`, `2.0`), the `gap`/`max(cc, 1)`
      formula, the single-unit scope, the A1 drop rule, and the F2 `@line`/
      `(ns, var, arity, line)` join key (mirroring how the workflow tests anchor
      on prompt substrings); ideally also an executable determinism assertion
      that the embedded `jq` recipe is lossless over a non-unique-arity input
      (the F2/F3 "optionally assert recipe determinism" the prior passes
      deferred). Scope: extend `workflow_definitions_test.clj`'s skill test (no
      new ns, per C1).
      RESOLUTION: added two deftests in `workflow_definitions_test.clj` (same ns,
      no new ns / no production Clojure, per C1). (1)
      `incidental-complexity-finder-skill-content-lock-test` slurps the loaded
      skill's `:file-path` SKILL.md and locks the design Deliverable-1
      behaviours: the `gap = lcc-total / max(cc, 1)` method, the `lcc-total ≥ 5.0`
      / `gap ≥ 2.0` thresholds, the single-executable-unit scope + "High cc alone
      is not a target" false-positive guard, the A1 drop rule ("dropped" /
      "**never** defaulted to `cc = 1`"), and the F2 `(ns, var, arity, line)` /
      `@line` join key. (2)
      `incidental-complexity-finder-recipe-determinism-test` extracts the
      embedded `jq` recipe and runs it (via `clojure.java.shell`) over a synthetic
      input with two same-`(ns,var,arity)` null-arity units differing only by
      `line` — asserting both survive the join with their OWN distinct `cc` (the
      F2 losslessness the pre-F2 `(ns,var,arity)` key would collapse last-wins). A
      jq-absent fallback asserts the recipe keys on `@line` on both the `$ccmap`
      build and the `$loc` `gap_key`. Focused suite green (16 tests, 218
      assertions, 0 failures); `clj-kondo` 0 findings.

- [x] TR2 — The generated two-phase behaviour-preserving contract embedded in
      `reduce-incidental-complexity.edn` step-7 is **under-covered**.
      `reduce-incidental-complexity-test` asserts the enforcing gate flags and
      both baseline filenames (`before-local.json`/`before-diagnose.edn`), but
      not the contract's substantive shape: the Phase-0 characterization-test
      gate ("green net before any refactor"), the behaviour-identical constraint
      (meta/spec unchanged, existing expectations not weakened), and — directly
      relevant to F3 — the A5/A2 acceptance keys. F3 specifically re-keyed A5/A2
      in this prompt onto `(ns, var, arity, line)` and its own resolution noted
      "no test change forced (optionally assert the key string for coherence)",
      so a regression of that key back to `(ns, var, arity)` in the generated
      contract passes green. Per `∀b ∈ behaviour(design). ∃t. covers(t,b)`, the
      design acceptance "Generated tasks carry the two-phase behaviour-preserving
      contract: a Phase 0 test-coverage gate … and Phase 1 refactor with the
      `local`-lens + `gate` + green-tests acceptance" is only partially covered.
      Add prompt-substring assertions in `reduce-incidental-complexity-test` for
      the Phase-0 characterization-test gate, the behaviour-identical constraint,
      and the `(ns, var, arity, line)` A5/A2 key (so F3 cannot silently regress).
      Scope: extend the existing test (no new ns).
      RESOLUTION: extended `reduce-incidental-complexity-test` (same ns, no new
      ns) with three new `testing` blocks asserting prompt substrings in step-1's
      generated-contract text: (a) the Phase-0 characterization-test gate
      ("These tests must be GREEN against the unmodified code before any
      refactoring begins" + "add characterization tests"), (b) the
      behaviour-identical constraint ("behaviour is identical — meta/spec are
      unchanged; existing test expectations are not weakened"), and (c) the F3
      A5/A2 key — both "keyed by `(ns, var, arity, line)`" (A5) and "identified by
      `(ns, var, arity, line)`" (A2) — so a regress to `(ns, var, arity)` in the
      generated contract fails green. Focused suite green (16 tests, 218
      assertions, 0 failures); `clj-kondo` 0 findings.

## Test review follow-ups (review pass 2)

- [x] TR3 — `incidental-complexity-finder-skill-content-lock-test` (added by TR1)
      locks the gap method, thresholds, single-unit scope, the high-cc-alone
      guard string, the A1 drop rule, and the F2 `@line` key — but **omits two
      named Deliverable-1 behaviours**, so a SKILL.md regress dropping them passes
      green. (1) The **step-5 judgment guard** — the design's *core* discriminator
      (Locked decisions 1/2/9; the whole "Why gap" rationale): "read the top 5
      qualifying units by `gap`", the incidental-burden signal list, the
      essential-complexity rejection, "choose the first that passes", "if none of
      the top 5 pass, report no target". The locked high-cc-alone string is the
      *rationale*, not the top-5 *procedure*; without it the skill degenerates to
      the `gordian complexity` ranking it exists not to be. (2) The **step-6
      evidence + coverage-hint emission** — the design's first acceptance is
      "produces a target **+ evidence**", and step 5/6 names the coverage hint
      (sibling test ns exists? any test references the target var?) as a required
      emitted field; no test covers it. Per `∀b ∈ behaviour(design). ∃t.
      covers(t,b)`, both are uncovered design acceptance behaviours. Fix: extend
      `incidental-complexity-finder-skill-content-lock-test` (same ns, no new ns,
      per C1) with substring locks for (a) the top-5 judgment guard ("top 5
      qualifying units by `gap`", essential-rejection, "Choose the first … that
      passes", "none of the top 5 pass") and (b) the coverage-hint evidence
      ("coverage hint", sibling-test-ns / references-the-target-var wording).
      SKILL.md already carries all these strings (lines 78, 121–139, 152).
      Test-only change.
      RESOLUTION: extended `incidental-complexity-finder-skill-content-lock-test`
      with two new `testing` blocks (test-only; same skill-test ns — the
      content-lock test now lives in `incidental_complexity_finder_skill_test.clj`,
      split out of `workflow_definitions_test.clj` to stay under the 800-line
      `components/` file-length guard; no new production Clojure). (a) step-5
      judgment guard: locks "top 5 qualifying units by `gap`", "Reject as
      **essential**", "Choose the first unit (highest `gap`) that passes the
      guard", and "If none of the top 5 pass" — so a SKILL.md that dropped step 5
      (degenerating to the `gordian complexity` ranking the skill exists *not* to
      be) fails green. (b) step-6 evidence + coverage hint: locks "coverage hint",
      "sibling test namespace exists for the target", and "any test references the
      target `var`" — so a regress dropping the coverage-hint emission fails
      green. Focused suite green: skill-test 3 tests/27 assertions + definitions
      13 tests/198 assertions = 16 tests/225 assertions, 0 failures; clj-kondo 0
      findings; file-length guard clean. (See implementation.md pass-2 TR3
      resolution entry.)

## Test review follow-ups (review pass 3)

- [x] TR4 — The determinism test proves losslessness but not the F2 core claim
      (order-independence). `incidental-complexity-finder-recipe-determinism-test`
      runs the embedded `jq` recipe over a single fixed emit order and asserts
      both null-arity units survive with their own `cc` — a *necessary*
      precondition but not the behaviour the test names ("not collapsed
      last-wins"), which is inherently order-dependent. A recipe that was
      order-sensitive yet lossless for this one ordering would still pass green.
      Per `∀b ∈ behaviour(design). ∃t. covers(t,b)`, the F2/A1 "join is
      deterministic w.r.t. emit order" behaviour is under-covered. Fix: run the
      recipe a second time with the two same-`(ns,var,arity)` null-arity units'
      emit order **reversed** in both the `local` and `cc` inputs, and assert the
      output is identical (each `line` keeps the same `cc`) — locking
      order-independence, not just losslessness. Extend
      `incidental-complexity-finder-recipe-determinism-test` (same ns, test-only).
      RESOLUTION: extracted the recipe-running into `run-jq-recipe` (+ `local-unit-json`
      / `cc-unit-json` fixture builders) so emit order is caller-controlled, then
      split the determinism deftest into two `testing` blocks: (1) the existing
      **losslessness** lock (each null-arity unit keeps its own cc), and (2) a new
      **order-independence** lock that runs the recipe twice — forward
      (`line-10`, `line-40`) and with **both** inputs' emit order reversed
      (`line-40`, `line-10`) — and asserts byte-identical `:out`. Under the pre-F2
      `(ns, var, arity)` key, `from_entries` is last-wins, so the reversed run
      would swap which cc each unit inherits → outputs differ; the `@line` key
      makes them identical. The jq-absent fallback (structural `@line`-key
      assertion) is unchanged. Focused suite green (16 tests, 228 assertions, 0
      failures — +3 over pass 3's 225); clj-kondo 0 findings; file 186 lines
      (< 800). (See implementation.md pass-4 test-review TR4 entry.)

- [x] TR5 — Stale/incorrect fixture comment in
      `incidental-complexity-finder-recipe-determinism-test`. The inline comment
      `;; line 10 unit (cc 3) and line 40 unit (cc 6/lcc 60) both survive`
      mis-states the line-40 fixture: the fixture JSON sets `cc:4` and the
      assertion two lines below correctly checks `cc=4`, not `cc 6`. The "cc 6"
      annotation is stale and self-contradictory, hurting the test's
      readability/signal (test-shaper: comments must not mislead). Fix the comment
      to read `(cc 4)`. Test-only; same ns; no assertion change.
      RESOLUTION: corrected the inline comment to `;; line 10 unit (cc 3) and
      line 40 unit (cc 4) both survive`, matching the fixture JSON (`cc:4`) and
      the `cc=4` assertion below it. Comment-only; no assertion change.

## Test review follow-ups (review pass 4)

- [x] TR6 — Add executable coverage for the selector recipe's **qualification
      filter** and **A1 unmatched-row drop rule**, currently locked only as
      SKILL.md prose substrings (lines 52–68 of
      `incidental_complexity_finder_skill_test.clj`) and never exercised. The
      `run-jq-recipe` harness already exists and both determinism fixtures
      qualify + match a `cc` row, so the filter/drop branches of the embedded
      `jq` recipe are untested — a regress (e.g. `>=`→`>` threshold typo, or
      defaulting an unmatched `local` row to `cc = 1` instead of dropping it,
      which A1 explicitly forbids) passes green. Extend
      `incidental-complexity-finder-recipe-determinism-test` (or a sibling
      deftest in the same skill-test ns; test-only, no new ns/production code)
      with: (a) a **filter** assertion — feed one unit below threshold
      (`lcc-total < 5.0` or `gap < 2.0`) and one above, assert only the above
      survives the recipe output; (b) a **drop** assertion — feed a `local`
      unit with **no matching `cc` row**, assert it is absent from the output
      (dropped, not defaulted to `cc = 1`/`gap` inflated into qualification).
      Both are named design Deliverable-1 behaviours (the filter also drives the
      workflow early-stop) with no executable cover. Run focused suite +
      `clj-kondo`; keep under the 800-line `components/` file guard.
      RESOLUTION: added a sibling deftest
      `incidental-complexity-finder-recipe-filter-and-drop-test` in the same
      skill-test ns (test-only; no new ns / no production Clojure), plus two
      parameterized fixture builders (`named-local-unit-json` /
      `named-cc-unit-json`) so units are individually identifiable in the output.
      (a) **filter** block feeds three matched units — `keep/qual` (lcc 30, cc 4
      → gap 7.5, qualifies), `drop/lowgap` (lcc 30, cc 20 → gap 1.5, fails
      `gap ≥ 2.0`), `drop/lowlcc` (lcc 4.0, cc 1 → fails `lcc-total ≥ 5.0`) — and
      asserts only `qual` survives; a `>=`→`>` regress flips a boundary unit.
      (b) **drop** block feeds a matched qualifying unit (`matched/present`) and
      an unmatched `local` row (`unmatched/absent`, lcc 30, no cc row) — asserting
      `present` survives and `absent` is absent; were A1 violated (defaulted to
      cc=1) `absent` would gain gap 30.0 and qualify, so its absence proves the
      drop. Both blocks gated on jq availability (reuse the existing harness).
      Verified live the recipe drops the unmatched row (gap-30 candidate excluded).
      Focused suite green (skill 4 tests/38 assertions, +1 test/+8 over pass-4's
      3/30; definitions still 13/198 → 17 tests/236 total); `clj-kondo` 0
      findings; file 251 lines (< 800). (See implementation.md pass-4 TR6 entry.)

## Test review follow-ups (review pass 5)

- [x] TR7 — `task-lifecycle-in-worktree-test` locks the wrapper's NO_TARGET
      (negative) branch (work-on NOT called + NO_TARGET sentinel, F1) and that
      the `work-on` tool is present + `{{input}}` is wired, but never asserts the
      **positive (target-present) branch** of the `resolve-worktree` prompt: that
      on a handoff carrying both `worktree_path:`/`munera_task_path:` it **calls
      `work-on` with the extracted worktree path** to set the session worktree
      and then **yields ONLY the bare Munera task path**. That instruction is the
      verified worktree-continuity mechanism the design chose over bare
      sibling-step inheritance (Locked decision 11 / Verified Facts: the wrapper
      "re-calls `work-on` … before sub-delegating"). A regress dropping that
      positive-path instruction (keeping the NO_TARGET branch + the `work-on`
      tool entry) passes green yet silently breaks the design's central
      cross-`:delegate` continuity claim — an uncovered design behaviour per
      `∀b ∈ behaviour(design). ∃t. covers(t,b)`. Fix: extend
      `task-lifecycle-in-worktree-test` (same ns, test-only, no production
      change) with substring locks on `resolve-step`'s template text for the
      positive-path instruction — calls `work-on` with the extracted worktree
      path, then responds with ONLY the Munera task path on a single line. Run
      focused suite + `clj-kondo`.
      RESOLUTION: extended `task-lifecycle-in-worktree-test`'s `resolve-worktree`
      coverage (same ns, test-only, no production change) with a new `testing`
      block locking the **positive (target-present)** branch of the
      `resolve-worktree` template text via three substring/regex asserts: (1)
      "call `work-on` with the extracted worktree path" — the re-call that
      establishes cross-`:delegate` worktree continuity (Locked decision 11);
      (2) "respond with ONLY the Munera task path" — the bare-path yield; (3)
      "on a single line" — the single-line yield constraint. The "on a single
      line" phrase occurs **only** on the positive path (the NO_TARGET branch
      uses "this exact single line"), so the asserts disambiguate the two
      branches and a regress dropping the positive-path instruction (while
      keeping the NO_TARGET branch + the `work-on` tool entry) now fails green.
      Focused definitions suite green (13 tests, 201 assertions, 0 failures —
      +3 over pass-5's 198); `clj-kondo` 0 findings; `cljfmt` clean; file 734
      lines (< 800). (See implementation.md pass-5 test-review TR7 entry.)

## Test review follow-ups (review pass 6)

- [x] TR8 — The design's **distinguishing endpoint behaviour — no push/PR — is
      not locked by any test**. Locked decision 7 ("Endpoint is a completed,
      reviewed task on a local worktree branch — no push/PR") and Locked
      decision 8 (the whole reason this is a new workflow vs
      `complexity-reduction-pr`: "different endpoint: full task lifecycle vs.
      quick PR") are encoded as an explicit step-1 execution constraint in
      `reduce-incidental-complexity.edn` — "Do NOT push or open a PR; this
      workflow ends with a completed, reviewed task on the local worktree branch
      (the user decides on PR)." — but `reduce-incidental-complexity-test` never
      asserts it. The test covers handoff fields, early-stop, gate flags,
      baselines, and the generated two-phase contract, yet a regress adding a
      push/PR step or instruction to step-1 (silently turning this into a
      `complexity-reduction-pr` clone and erasing the design's reason for
      existing) would pass every existing test green. Per
      `∀b ∈ behaviour(design). ∃t. covers(t,b)`, this is an uncovered design
      acceptance behaviour ("ends with a completed, reviewed task on the local
      worktree branch — it does **not** push or open a PR"). Fix: extend
      `reduce-incidental-complexity-test` (same ns, test-only, no production
      change) with a prompt-substring lock on the no-push/PR endpoint constraint
      (the string is already present in step-1's prompt — "Do NOT push or open a
      PR" / "ends with a completed, reviewed task on the local worktree branch").
      Run focused suite + `clj-kondo`.
      RESOLUTION: added a new `testing` block to `reduce-incidental-complexity-test`
      (same ns, test-only, no production change) — "select-and-create prompt locks
      the no-push/PR endpoint constraint (TR8)" — asserting two step-1 prompt
      substrings: "Do NOT push or open a PR" (the explicit constraint) and
      "ends with a completed, reviewed task on the local worktree branch" (the
      local-worktree-branch endpoint, Locked decisions 7 & 8). A regress adding a
      push/PR step — silently turning this into a `complexity-reduction-pr` clone
      and erasing the workflow's reason to exist — now fails green. Both strings
      already present in step-1's prompt; no production change. Focused
      definitions suite green (13 tests, 203 assertions, 0 failures — +2 over
      pass-5's 201); `clj-kondo` 0 findings; `clj-paren-repair` Success; file 746
      lines (< 800).

## Implementation review follow-ups (review pass 5)

- [x] F5 — SKILL.md frontmatter `lambda` (line 4) still states the **pre-F2**
      join key: `… → join(ns,var,arity) → gap=burden/cc → …`. The
      `join(ns,var,arity)` token describes the skill pipeline's **join
      operation** — exactly the operation F2 re-keyed onto `(ns, var, arity,
      line)` (the `@line` recipe) and F3/F4 propagated through the A5/A2
      acceptance and `design.md`'s selector-procedure step 2. The skill body
      §2/§3 + the `jq` recipe + docs + the design selector join all correctly key
      on `(ns, var, arity, line)`; only the frontmatter lambda — the skill's
      one-line behavioural summary — was left stale. F2 deliberately left it,
      classifying it as the unit's "logical identity," but that is wrong for the
      lambda specifically: the token is `join(...)` (a join-key statement), not a
      unit-identity statement — the same distinction F4 used when it fixed
      `design.md` line 62's "Join on `(ns, var, arity, line)`" while leaving the
      bare-identity mentions at lines 64/222. Residual design(spec)↔SKILL
      coherence gap (F4-class); threshold-guarded today, never locked by a test
      (the content-lock test asserts body strings only). Fix: SKILL.md line 4
      `join(ns,var,arity)` → `join(ns,var,arity,line)`; leave the Scope
      "a `(ns, var, arity)`" mention intact (logical identity). Optionally extend
      `incidental-complexity-finder-skill-content-lock-test` with the lambda
      join-key substring so it cannot regress. Skill markdown only; no
      workflow/test change forced.
      RESOLUTION: updated SKILL.md line 4 frontmatter `lambda`
      `join(ns,var,arity)` → `join(ns,var,arity,line)`, matching the body
      §2/§3 + `jq` recipe, the F3 A5/A2 acceptance keys, and the F4-corrected
      `design.md` selector procedure (line 62). Applied F4's distinction: the
      lambda token is a **join-key statement** so it tracks the unique key; the
      Scope "a `(ns, var, arity)`" mention (logical identity) is left intact (as
      F4 left design lines 64/222). Took the optional test lock: extended
      `incidental-complexity-finder-skill-content-lock-test` (same ns, test-only,
      no production change) with an F5 block asserting the body **contains**
      `join(ns,var,arity,line)` and does **not** contain the pre-F2
      `join(ns,var,arity)`, so the lambda cannot silently regress. Frontmatter
      prose only — behaviour unchanged (recipe already keyed on `@line`). Focused
      skill-test suite green (4 tests, 40 assertions, 0 failures — +2 over
      pass-4's 38); `clj-kondo` 0 findings; skill-test file 259 lines (< 800).

## Implementation review follow-ups (review pass 6)

- [ ] F6 — `design.md`'s `## Verified facts (grounding)` section (and the
      Step-1/Step-2 wrapper references at lines 138–139, 144, 168–170, 295, 316,
      328, 362, 367) still names `implement-task-in-worktree` as the **verified /
      proven** wrapper precedent the new wrapper is "structurally identical to" —
      but D1 found, and this review re-confirmed against the live loader, that
      `implement-task-in-worktree.md` **does not load**: its body begins with an
      EDN map, which `parse-markdown-workflow-file` rejects (`parser.clj:162`).
      The actual loadable precedent used was `review-implementation-in-worktree.edn`
      (recorded in implementation.md/steps.md D1, never propagated to the design
      spec). Per the coherence invariant (`source_of_truth ≡ … ∪ spec`), the spec
      asserts a false grounding fact — F4/F5-class stale-claim-in-one-artifact,
      here a stale *precedent claim*. The chosen mechanism (handoff `worktree_path:`
      + `resolve-worktree` `:session` re-calling `work-on`) is correct and IS
      demonstrated by a loadable precedent — but that precedent is
      `review-implementation-in-worktree.edn`, not `implement-task-in-worktree`.
      Threshold-harmless (wrapper behaviour is correct + tested) but design↔
      implementation grounding is internally contradictory. Fix: reconcile
      `design.md` so the verified/loadable precedent is named as
      `review-implementation-in-worktree.edn` (the loadable `.edn` 3-step
      `resolve-worktree → delegate → summary`), demoting `implement-task-in-worktree`
      to "the *intended* shape, which does not currently load (`.md` body begins
      with an EDN map — see D1), hence the wrapper is authored as `.edn` mirroring
      the loadable `review-implementation-in-worktree.edn`." Keep the mechanism
      prose; only the named verified precedent is wrong. Cross-reference D1.
      Design prose only; no workflow/skill/test change forced. (See
      implementation.md pass-6 F6 entry.)

## Contingency (non-planned; only if Slice 3 step-1 proves unwieldy)

- [ ] Split step-1 selection from task-creation into two `:session` steps,
      threading selection output forward (accepting added inter-step data flow).
      Per design "Open questions": keep as one step unless it proves unwieldy.
