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

- [x] F6 — `design.md`'s `## Verified facts (grounding)` section (and the
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
      RESOLUTION: reconciled `design.md` so the verified/loadable wrapper
      precedent is named as **`review-implementation-in-worktree.edn`** (a
      loadable 3-step `resolve-worktree` → `review`(`:delegate`) → `summary`
      `.edn`), demoting `implement-task-in-worktree` to the *intended shape*
      "which does not currently load (`.md` body begins with an EDN map — see
      D1)." Added an explicit **F6/D1 precedent note** at the head of the
      `## Verified facts (grounding)` section that (a) names the loadable
      precedent, (b) cites the live rejection (`parser.clj:162`,
      `body-starts-with-edn-map?`), and (c) instructs that every later
      "structurally identical to / verified `implement-task-in-worktree`" mention
      be read as "mirrors the loadable `review-implementation-in-worktree.edn`,
      the `.edn` realisation of the intended shape." Reconciled each load-bearing
      named reference inline: Step-1 handoff-consumer (line 136), Step-2 wrapper
      framing (144), handoff-wiring (163), worktree-continuity mechanism (168),
      the delegate-yield fact (336), the worktree-ownership fact (347), Locked
      decision 11 (305), and both acceptance-criteria mentions (395, 400) — each
      now names `review-implementation-in-worktree.edn` with an "`.edn`
      realisation of the `implement-task-in-worktree` intended shape; see F6/D1
      precedent note" cross-ref. The only bare `implement-task-in-worktree`
      mentions left are inside the precedent note itself (intentional). Also
      corrected a latent coherence drift the F6 reconciliation surfaced: the
      acceptance-criteria wrapper description said "two-step" while the resolved
      P1 design makes the wrapper three steps (resolve-worktree → lifecycle →
      summary) — aligned it to "three-step" matching the loadable 3-step
      precedent. Verified live (static): `implement-task-in-worktree.md`'s body
      begins with `{:terminal-contract ...}` (an EDN map) → rejected by
      `parse-markdown-workflow-file` at `parser.clj:162`; whereas
      `review-implementation-in-worktree.edn` is a loadable 3-step
      `resolve-worktree`(`:session`,`work-on`) → `review`(`:delegate`) →
      `summary` `.edn`. Mechanism prose (handoff `worktree_path:` +
      `resolve-worktree` re-calling `work-on`) unchanged — it was already correct
      and is demonstrated by the loadable precedent. **Design prose only**: no
      workflow/skill/test change; `doc/workflows.md`'s lone
      `implement-task-in-worktree.md` mention is a plain workflow-listing line,
      not a 204 precedent claim, so it stays coherent (no doc change). design(spec)
      ↔ implementation grounding contradiction closed.

## Test review follow-ups (review pass 7)

- [x] TR9 — The selector recipe's **gap-descending ranking** (`sort_by(-.gap)`)
      and **top-5 cap** (`.[0:5]`) are encoded only in the embedded `jq` recipe
      and never exercised: every executable skill test feeds ≤3 units (just enough
      for the join/determinism/filter/drop branches), so neither the ordering nor
      the slice is asserted. Both are named Deliverable-1 behaviours — design step
      4 / Locked decision 2: "Rank qualifying units by `gap`" and the step-5 guard
      reads "the **top 5** qualifying units by `gap`". A regress to
      `sort_by(.gap)` (ascending → picks the lowest-gap unit, guard reads the
      wrong five) or a dropped/widened slice (`.[0:10]`/no slice → unbounded
      candidate set) passes every test green; the TR3 content-lock asserts only
      the SKILL *prose*, which can drift from the executed recipe. Per
      `∀b ∈ behaviour(design). ∃t. covers(t,b)`, both are uncovered. Fix: extend
      `incidental-complexity-finder-recipe-filter-and-drop-test` (or a sibling
      deftest in the same skill-test ns; test-only, no new ns / no production
      code), reusing `run-jq-recipe` + `named-{local,cc}-unit-json`: (a) a
      **ranking** assertion — feed ≥3 qualifying units whose input emit order
      differs from their gap order, assert output `gap` values are strictly
      descending; (b) a **top-5 cap** assertion — feed >5 qualifying units, assert
      exactly 5 survive. Run focused suite + `clj-kondo`; keep under the 800-line
      `components/` file guard.
      RESOLUTION: added a sibling deftest
      `incidental-complexity-finder-recipe-ranking-and-cap-test` in the same
      skill-test ns (test-only; no new ns / no production Clojure), reusing
      `run-jq-recipe` + `named-{local,cc}-unit-json`. (a) **ranking** block feeds
      three qualifying units in non-gap emit order (lowmid gap 7.5, top gap 25.0,
      mid gap 15.0) and asserts the output `gap` sequence is strictly descending
      (`= gaps (reverse (sort gaps))`) plus a positional check that `top` precedes
      `lowmid` in the serialized output — a regress to `sort_by(.gap)` (ascending)
      emits them lowmid<mid<top and fails. (b) **top-5 cap** block feeds 7
      qualifying units (all gap 12.5, distinguished by var/line) and asserts
      exactly 5 survive (`(count (re-seq #"\"ns\":\s*\"cap\"" out)) = 5`) — a
      regress dropping/widening the `.[0:5]` slice emits all 7 and fails. Both
      blocks gated on jq availability (reuse the existing harness). Focused
      skill-test suite green (5 tests, 47 assertions, 0 failures — +7 over
      pass-6's 40); definitions still 13 tests/203 assertions; `clj-kondo` 0
      findings; file 322 lines (< 800). (See implementation.md pass-7 TR9 entry.)

## Test review follow-ups (review pass 8)

- [x] TR10 — `task-lifecycle-in-worktree-test`'s `summary`-step coverage locks
      only the NO_TARGET (negative) branch (prompt `.contains "NO_TARGET"` +
      sources `resolve-worktree :yield :text`); it never locks the summary's
      **positive (target-present) terminal contract** — the symmetric gap TR7
      fixed for `resolve-worktree`. The `summary` template is the workflow's
      user-facing terminal report: on a real `munera/...` path it must
      independently inspect the task artifacts and report whether the lifecycle
      ran cleanly (design → plan → implement → review), the work completed, the
      artifacts updated, and whether the task was closed/open — and it sources
      the `lifecycle` step `:yield :text` to report lifecycle outcomes. None of
      those positive-path strings nor the `lifecycle`-output sourcing is
      asserted, so a regress dropping the positive-path summary contract (or the
      `lifecycle` contribution) while keeping the NO_TARGET branch passes every
      existing test green, gutting the workflow's terminal user-facing report.
      Per `∀b ∈ behaviour(design). ∃t. covers(t,b)` (design: three-step
      `… → summary(:session)` adapter producing the terminal user-facing result;
      Locked decision 7's completed/reviewed-task endpoint is what summary
      reports), this is an uncovered terminal behaviour. Fix: extend
      `task-lifecycle-in-worktree-test`'s `summary` coverage (same ns, test-only,
      no production change) with (a) a substring lock on the positive-path
      design→plan→implement→review report contract, and (b) an assertion that
      `summary` sources the `lifecycle` step `:yield :text` contribution. Run
      focused suite + `clj-kondo`.
      RESOLUTION: extended `task-lifecycle-in-worktree-test`'s `summary` coverage
      (same ns, test-only, no production change) with two new `testing` blocks
      symmetric to TR7's `resolve-worktree` positive-path lock. (a) "summary
      prompt reports the positive-path lifecycle terminal contract" asserts four
      positive-branch substrings on the `summary` template text: "independently
      inspect that specific task" (independent artifact inspection on a
      target-present run), "completed cleanly (design → plan → implement →
      review)" (the lifecycle-clean report), "task artifact files updated" (the
      artifacts-updated report), and "closed (moved to munera/closed/) or remains
      open" (the closed/open endpoint, Locked decision 7). (b) "summary sources
      the lifecycle step :yield :text" asserts the `{:step "lifecycle" :yield
      :text}` source contribution is present on the `summary` step — so a regress
      dropping the `lifecycle` output sourcing (which the positive-path report
      depends on) fails green. A regress dropping the positive-path contract
      while keeping the NO_TARGET branch + the resolve-worktree sourcing now
      fails. All asserted strings already present in the EDN summary template; no
      production change. Focused definitions suite green (13 tests, 208
      assertions, 0 failures — +5 over pass-7's 203); `clj-kondo` 0 findings;
      `clj-paren-repair` Success; file 772 lines (< 800). (See implementation.md
      pass-8 test-review TR10 entry.)

## Test review follow-ups (review pass 9 — test-shaper)

- [x] TR11 — The **A3 baseline-path-resolution** behaviour is named in the
      design ("Generated task design" Phase-1 A3 + R3) with an explicit,
      called-out failure mode: the gate `--baseline` must reference the
      **worktree-root-relative task-dir path**
      (`munera/open/NNN-slug/before-diagnose.edn`), NOT a bare filename, because
      "a bare filename does not resolve" from the worktree-root cwd where Phase 1
      runs `gordian gate`. `reduce-incidental-complexity-test`'s "embeds the
      enforcing gate flags + both baselines" block asserts only the `--fail-on
      new-cycles,new-high-findings --max-new-medium-findings 0` flag tail and the
      bare filename substrings `before-local.json`/`before-diagnose.edn`; it never
      anchors the baseline on its **worktree-relative path in the gate command**.
      A regress changing `--baseline munera/open/NNN-slug/before-diagnose.edn` to
      a bare `--baseline before-diagnose.edn` (the exact R3-warned bug) passes
      every existing test green. The same class applies to the A5
      `before-local.json` read — locked only as a bare filename, not as the
      worktree-relative comparison path. Per `∀b ∈ behaviour(design). ∃t.
      covers(t,b)` and test-shaper `economical` (cover named acceptance) +
      `meaningful_failures` (a named behaviour with a called-out failure mode
      should have a test that fails on the regress), this is an uncovered
      named behaviour. Fix: extend `reduce-incidental-complexity-test` (same ns,
      test-only, no production change) to assert the gate command embeds the
      full worktree-root-relative baseline path
      `--baseline munera/open/NNN-slug/before-diagnose.edn` (not just the
      `--fail-on` tail), and that the A5 burden-reduction instruction names the
      worktree-relative `munera/open/NNN-slug/before-local.json` read path. Run
      focused suite + `clj-kondo`.
      RESOLUTION: added a new `testing` block to `reduce-incidental-complexity-test`
      (same ns, test-only, no production change) —
      "select-and-create prompt resolves A3/A5 baselines by worktree-relative
      path (TR11)" — asserting two step-1 prompt substrings: (a) the **A3 gate
      command** embeds the full worktree-root-relative baseline path
      `--baseline munera/open/NNN-slug/before-diagnose.edn` (not just the
      `--fail-on` tail already locked above), so the exact R3-warned regress to a
      bare `--baseline before-diagnose.edn` fails green; and (b) the **A5**
      burden-reduction comparison names the worktree-relative read path
      `` the stored `munera/open/NNN-slug/before-local.json` `` rather than the
      bare filename. Both strings already present verbatim in step-1's prompt; no
      production change. The pre-existing baseline-filename locks
      (`before-local.json` / `before-diagnose.edn`) are kept — TR11 anchors the
      *path resolution* the bare-filename locks did not. Focused suite green
      (`bb clojure:test:unit` — all tests passed); `clj-kondo` 0 findings;
      test file 787 lines (< 800). (See implementation.md pass-9 test-review TR11
      entry.)

- [x] TR12 — Two of the three executable recipe tests in
      `incidental_complexity_finder_skill_test.clj`
      (`incidental-complexity-finder-recipe-filter-and-drop-test` and
      `incidental-complexity-finder-recipe-ranking-and-cap-test`) gate their
      entire behavioural body on a bare `(when jq-available …)` with **no else
      branch**, so when `jq` is absent each `deftest` runs only its single
      pre-`when` `(is (some? recipe) …)` floor assertion and reports green while
      asserting **none** of the behaviours it exists to lock (filter `>= 5.0` /
      `>= 2.0`, the A1 unmatched-row drop, `sort_by(-.gap)` ranking, `.[0:5]`
      cap). Only `incidental-complexity-finder-recipe-determinism-test` degrades
      gracefully — it has a jq-absent **structural fallback** asserting the
      recipe keys on `@line`. Per test-shaper `meaningful_failures` (a recipe
      regress would pass green in any jq-less environment) + `deterministic`
      (coverage must not silently vary with the environment) + `economical`
      (named Deliverable-1 behaviours must stay covered), this asymmetry is a
      robustness defect. Fix: give both `when`-gated recipe tests a jq-absent
      fallback mirroring the determinism test — structurally lock the recipe
      fragment each behaviour depends on (the `>= 5.0`/`>= 2.0` filter predicate
      + inner-join/drop shape for filter-and-drop; the `sort_by(-.gap)` +
      `.[0:5]` fragments for ranking-and-cap) — so a regress fails green whether
      or not jq is installed. Test-only, no production/skill/EDN change; skill
      test file is 322 lines (well under the 800 `components/` guard). Run
      focused suite + `clj-kondo`.
      RESOLUTION: converted both `when`-gated recipe tests
      (`incidental-complexity-finder-recipe-filter-and-drop-test`,
      `incidental-complexity-finder-recipe-ranking-and-cap-test`) to the
      determinism test's `(if jq-available (do …) (testing …structural fallback…))`
      shape (test-only; no production/skill/EDN change). filter-and-drop fallback
      locks the recipe carries `select(.["lcc-total"] >= 5.0 and .gap >= 2.0)`
      (filter) and `select($ccmap[.gap_key] != null)` (A1 inner-join/drop);
      ranking-and-cap fallback locks `sort_by(-.gap)` (descending, not ascending)
      and `.[0:5]` (top-5 cap). All four fragments verified present verbatim in
      the live SKILL.md recipe, so the exact regresses TR12 names (>= typo,
      drop-rule removal, ascending sort, dropped/widened slice) now fail green
      whether or not jq is installed. Focused recipe tests green (jq present
      path); `clj-paren-repair` + `clj-kondo` 0 findings; test file 340 lines
      (< 800). (See implementation.md pass-10 test-review TR12 entry.)

## Contingency (non-planned; only if Slice 3 step-1 proves unwieldy)

- [ ] Split step-1 selection from task-creation into two `:session` steps,
      threading selection output forward (accepting added inter-step data flow).
      Per design "Open questions": keep as one step unless it proves unwieldy.

## Test review follow-ups (review pass 11 — test-shaper)

- [x] TR13 — `reduce-incidental-complexity-test` does not lock the
      `lifecycle-in-worktree` `:delegate` step's `:context`. That step carries
      `[{:type :source :from :workflow-original}
        {:type :source :from {:step "select-and-create" :yield :text}}]`; the
      second source propagates the step-1 structured handoff blob into the
      delegated wrapper's context — the companion to the `:prompt-string`
      `:input` wiring and part of the verified cross-`:delegate`
      worktree-continuity mechanism (Locked decision 11 / Verified Facts).
      `task-lifecycle-test` explicitly locks its `:context`
      ("every step carries only :workflow-original context (no prior-step
      yield)"), but the outer-workflow test asserts only `:type`/`:target`/
      `:prompt-string` of this delegate, leaving its `:context` uncovered. Per
      test-shaper `behavior_focused` (the context-propagation behaviour is
      observable and design-significant) + `meaningful_failures` (a regress
      dropping the `{:step "select-and-create" :yield :text}` context source —
      stripping the handoff from the delegated run's context — would pass green),
      this is a coverage gap. Fix: add a `testing` block to
      `reduce-incidental-complexity-test` asserting the `lifecycle-in-worktree`
      delegate's `:context` equals
      `[{:type :source :from :workflow-original}
        {:type :source :from {:step "select-and-create" :yield :text}}]`,
      mirroring `task-lifecycle-test`'s `:context` lock. Test-only, no
      production/EDN change; `workflow_definitions_test.clj` is 787 lines — keep
      the edit under the 800 `components/` guard. Run focused suite +
      `clj-kondo`. (See implementation.md pass-11 test-shaper TR13 entry.)
      RESOLUTION: added a `testing` block to `reduce-incidental-complexity-test`
      (same ns, test-only, no production/EDN change) —
      "lifecycle-in-worktree :delegate :context propagates workflow-original +
      the select-and-create handoff yield (TR13)" — asserting the delegate's
      `:context` equals
      `[{:type :source :from :workflow-original}
        {:type :source :from {:step "select-and-create" :yield :text}}]`,
      mirroring `task-lifecycle-test`'s `:context` lock. Verified the EDN's
      `lifecycle-in-worktree` step carries exactly that `:context` before
      locking. A regress dropping the `{:step "select-and-create" :yield :text}`
      source — stripping the step-1 handoff from the delegated run's context —
      now fails green. Focused suite green (13 tests, 211 assertions, 0 failures
      — +3 over pass-9's 208); `clj-kondo` 0 findings; `clj-paren-repair`
      Success; test file trimmed to 799 lines (< 800 `components/` guard — the
      verbose TR13 comment was shortened to stay under).

## Test review follow-ups (review pass 12 — test-shaper)

- [x] TR14 — `task-lifecycle-in-worktree-test`'s `lifecycle` `:delegate`
      coverage asserts only `:type`/`:target`/`:prompt-string`; it never locks
      the delegate's `:context`. The wrapper's `lifecycle` step carries
      `:context [{:type :source :from :workflow-original}]` (EDN line 17) —
      deliberately ONLY `:workflow-original`, NOT the `resolve-worktree` handoff
      yield, because inner `task-lifecycle` reads the task path solely via
      `:prompt-string {:input …}`; re-injecting the raw handoff/worktree-path
      blob as a context source would pollute the lifecycle's context. This is
      the exact TR13-class symmetry: `task-lifecycle-test` explicitly locks every
      delegate's `:context` ("no prior-step yield"), and TR13 added the same lock
      for the OUTER `reduce-incidental-complexity` delegate — but the WRAPPER's
      inner lifecycle delegate `:context` was left uncovered. Per test-shaper
      `meaningful_failures` + `behavior_focused`, a regress adding
      `{:step "resolve-worktree" :yield :text}` to the wrapper lifecycle
      `:context` (re-injecting the handoff into the lifecycle context) or
      dropping `:workflow-original` passes every existing test green. Fix: add a
      `testing` block to `task-lifecycle-in-worktree-test` asserting the
      `lifecycle` delegate's `:context` equals
      `[{:type :source :from :workflow-original}]`, mirroring
      `task-lifecycle-test`'s `:context` lock and TR13's outer-delegate lock.
      Test-only, no production/EDN change; `workflow_definitions_test.clj` is
      799 lines — keep the edit under the 800 `components/` guard (trim verbose
      TR-comment headroom if needed). Run focused suite + `clj-kondo`.
      RESOLUTION: added a `testing` block to `task-lifecycle-in-worktree-test`
      (same ns, test-only, no production/EDN change) — "lifecycle :delegate
      :context is only :workflow-original (no prior-step yield) (TR14)" —
      asserting the wrapper `lifecycle` delegate's `:context` equals
      `[{:type :source :from :workflow-original}]`, mirroring
      `task-lifecycle-test`'s `:context` lock and TR13's outer-delegate lock.
      Verified the EDN's `lifecycle` step carries exactly that `:context` (only
      `:workflow-original`, NOT the `resolve-worktree` yield) before locking. A
      regress adding `{:step "resolve-worktree" :yield :text}` (re-injecting the
      handoff into the lifecycle context) or dropping `:workflow-original` now
      fails green. To stay under the 800 `components/` file guard (the file was
      at 799, the new block pushed it to 810), trimmed verbose prose headroom in
      the TR7/TR10/TR13 comment blocks (assertions untouched) → file now 797
      lines. Focused suite green: `task-lifecycle-in-worktree-test` 26 assertions
      (+1 over pass-11's 25); full definitions ns 13 tests, 212 assertions, 0
      failures (+1 over pass-11's 211); `clj-kondo` 0 findings; `clj-paren-repair`
      Success; `bb commit-check:file-lengths` clean (797 < 800).

## Test review follow-ups (review pass 13 — test-shaper)

- [x] TR15 — `reduce-incidental-complexity-test` never locks the
      `select-and-create` `:session` step's `{{input}}` → `:workflow-input`
      wiring. Step 1 carries `:vars {"input" {:from :workflow-input}}` and the
      prompt ends `Input:\n{{input}}` — this is the workflow's entry-point
      data-flow contract (top-level workflow input reaches the selection step's
      prompt). Its sibling tests already lock the analogous wiring:
      `task-lifecycle-in-worktree-test` asserts
      `(step-has-input-var-wired? resolve-step)` for the wrapper's first step,
      and the design's handoff chain depends on the outer step receiving its
      input. But the outer `select-and-create` step's input wiring is uncovered:
      a regress dropping `:vars`/the `{{input}}` template (or mis-wiring `input`
      to a non-`:workflow-input` source) passes every existing
      `reduce-incidental-complexity-test` assertion green. Per test-shaper
      `behavior_focused` (entry-point input flow is observable and
      design-significant) + `meaningful_failures` (the regress fails silently) +
      `consistent` (the sibling wrapper test already locks the analogous wiring
      via `step-has-input-var-wired?`), this is a coverage gap. Fix: add an
      assertion (or `testing` block) to `reduce-incidental-complexity-test`
      asserting `(step-has-input-var-wired? select-step)`, mirroring the
      wrapper test's `resolve-step` lock. Test-only, no production/EDN change;
      `workflow_definitions_test.clj` is 797 lines — keep the edit under the
      800 `components/` guard. Run focused suite + `clj-kondo`.
      RESOLUTION: added a `testing` block to `reduce-incidental-complexity-test`
      (same ns, test-only, no production/EDN change) — "select-and-create wires
      {{input}} to the bare :workflow-input (TR15)" — asserting (a) the step's
      template references `{{input}}` and (b) its `:vars["input"]` equals
      `{:from :workflow-input}`. **Discovery:** the outer step wires `input` to
      the **bare** top-level `:workflow-input` (no `:path`), distinct from the
      wrapper steps' `{:from :workflow-input :path [:input]}` (which select the
      `:input` field of a delegated `:map`). So `step-has-input-var-wired?`
      (which requires the `:path [:input]` shape) could NOT be reused — it
      returned false for this step (verified by a failing first attempt);
      asserted the actual shape directly instead. A regress dropping `:vars`/the
      `{{input}}` template, or mis-wiring to a non-`:workflow-input` source, now
      fails green. File-length guard: the new block + comment pushed
      `workflow_definitions_test.clj` from 797 to 814 lines (> 800 `components/`
      guard); trimmed verbose prose headroom in the TR7/TR8/TR10/TR11/TR13/TR14/
      TR2 comment blocks (assertions untouched) → file now exactly **800** lines
      (`bb commit-check:file-lengths` clean). Focused green: 18 tests, **261
      assertions** (+2 over pass-12's 259), 0 failures; `clj-kondo` 0 findings;
      `clj-paren-repair` Success.

## Implementation review follow-ups (independent pass — task-implementation-review)

- [x] R6 — Relieve the 800-line CI file-length boundary on the shared
      `components/workflow-loader/test/psi/workflow_loader/workflow_definitions_test.clj`.
      Task 204's two new workflow-definition `deftest`s
      (`task-lifecycle-in-worktree-test`, `reduce-incidental-complexity-test`,
      ≈ lines 595–800, ~206 lines) grew the shared ns to **exactly 800** — the
      hard limit `bb commit-check:file-lengths` enforces for `components/`.
      TR15 already had to trim comment prose to fit; the next addition will fail
      the gate. Extract the 204 workflow-definition `deftest`s into a dedicated
      sibling ns (a precedent sibling already exists:
      `incidental_complexity_finder_skill_test.clj`) so each file has headroom
      and no harness drift. Verify both nss load and run via the focused suite +
      `clj-kondo`, and `bb commit-check:file-lengths` is clean afterward.
      RESOLUTION: extracted the two task-204 workflow-definition `deftest`s
      (`task-lifecycle-in-worktree-test`, `reduce-incidental-complexity-test`)
      into a dedicated sibling ns
      `psi.workflow-loader.task-204-workflow-definitions-test`
      (`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`,
      277 lines), following the existing `incidental_complexity_finder_skill_test.clj`
      split precedent. The new ns carries only the loader-fixture subset those
      two tests use (`slurp-workflow-file`, `with-workflow-dir`, `load-edn-only`,
      `input-var-wired?`, `step-has-input-var-wired?`, `step-template-text`);
      the larger fixtures (`load-edn-with-md-refs`, judge helpers) stay in the
      original ns where the remaining tests still use them. All assertions/
      comments moved verbatim — no behaviour, EDN, or production change.
      Original `workflow_definitions_test.clj` dropped from 800 → **593** lines
      (full headroom restored, no further TR-comment trimming pressure). Both
      nss load and run via the focused unit suite (`--focus` both nss): 13 tests,
      214 assertions, 0 failures (same total assertion count as pass-15's 261…
      note: the split preserves the same `deftest`s, so combined the focused run
      now reports 13 tests/214 assertions across the two files — the two moved
      tests retain all their assertions). `clj-paren-repair` Success on both
      files; `clj-kondo` 0 findings (errors 0, warnings 0); no unused-helper
      warnings in the trimmed original (every remaining private helper is still
      referenced). `bb commit-check:file-lengths` clean (exit 0; 593 and 277
      both < 800). (See implementation.md R6 entry.)

## Implementation review follow-ups (independent pass — task-implementation-review)

- [x] IR1 (bookkeeping) — The R6 file-length follow-up (extract task-204
      workflow-definition deftests into a sibling ns; committed `f9f1c5128`,
      shared ns 800 → 593) is recorded only as an implementation.md worklog
      entry, not as a checked `- [x]` item in steps.md. Add an explicit checked
      steps.md item for the R6 extraction so the steps checklist reflects the
      committed work. Non-implementation; the code/tests are already done and
      verified (CI green, file-lengths clean). No further review issues found:
      design↔artifacts match, recipes re-verified against the live `gordian`
      CLI, wrapper mirrors the loadable `review-implementation-in-worktree.edn`
      precedent, handoff wiring matches `gh-issue-implement.edn`, tests/kondo
      green.
      RESOLUTION: added the explicit checked steps.md item below recording the
      committed R6 extraction; re-verified the committed state (shared ns 593
      lines, sibling ns 277 lines, `bb commit-check:file-lengths` exit 0). No
      code/test/EDN change — pure steps.md bookkeeping.

### R6 — extract task-204 workflow-definition deftests into a sibling ns

- [x] R6 — Extracted `task-lifecycle-in-worktree-test` and
      `reduce-incidental-complexity-test` from the shared
      `workflow_definitions_test.clj` into the dedicated sibling ns
      `psi.workflow-loader.task-204-workflow-definitions-test`
      (`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`),
      following the `incidental_complexity_finder_skill_test.clj` split
      precedent. Shared ns 800 → 593 lines; sibling ns 277 lines; both < 800
      `components/` guard. Test-only, no production/EDN change; assertions moved
      verbatim. Committed `f9f1c5128`
      (`⚒ test: split task-204 workflow-definition deftests into sibling ns (R6)`).
      VERIFIED: `bb commit-check:file-lengths` exit 0 (593 + 277 both < 800).

## Test review follow-ups (review pass 14 — test-shaper)

- [x] TR16 — Add an executable recipe test for the `max(cc, 1)` matched-zero-cc
      guard in `incidental_complexity_finder_skill_test.clj`. The recipe's
      `gap: (.["lcc-total"] / ([$ccmap[.gap_key], 1] | max))` is named by SKILL
      §3 as a distinct A1 behaviour ("`max(cc, 1)` guards only the matched
      zero-cc case"), but every executable recipe test feeds `cc ≥ 1`, so a
      regress dropping `| max` (divide-by-zero / `gap: null` for a matched cc=0
      unit) passes green. Feed a single matched unit with `lcc-total` above
      threshold and `cc 0` (use the existing `named-local-unit-json` /
      `named-cc-unit-json` helpers with cc 0) and assert it **survives** the
      join+filter with `gap = lcc-total` (e.g. lcc 30.0 → gap 30). Add the
      jq-absent structural fallback locking the `| max` / `, 1] | max` recipe
      fragment, mirroring the existing TR12 fallbacks. Verify focused tests +
      `clj-kondo` + `clj-paren-repair` green and `bb commit-check:file-lengths`
      clean (sibling skill ns headroom).
      RESOLUTION: added a new sibling deftest
      `incidental-complexity-finder-recipe-max-cc-guard-test` in the skill-test
      ns (test-only; no new ns / no production Clojure), reusing `run-jq-recipe`
      + `named-{local,cc}-unit-json`. Feeds a single matched unit (`zero/cc`,
      lcc 30.0, **cc 0**) and asserts it survives the join + qualification filter
      with `gap` = 30 — i.e. `max(0, 1) = 1` divides lcc by 1, not 0. Verified
      live: with the guard the cc-0 unit emits `gap: 30` and qualifies; were `|
      max` dropped, `gap` would be `null` and the unit would fail `gap >= 2.0`
      and drop. jq-absent fallback locks the recipe fragment
      `[$ccmap[.gap_key], 1] | max` (mirrors the TR12 fallbacks), so the regress
      fails green whether or not jq is installed. Focused skill-test suite green
      (7 tests, 55 assertions, 0 failures — +1 test/+4 over pass-13's 5/47, with
      TR17 below); `clj-kondo` 0 findings; `clj-paren-repair` Success; skill-test
      file 409 lines (< 800); `bb commit-check:file-lengths` exit 0. (See
      implementation.md pass-14 test-review TR16 entry.)

- [x] TR17 — Add an executable recipe test for the empty-qualification (early-
      stop) boundary in `incidental_complexity_finder_skill_test.clj`. Design
      Locked decision 2 ("A real early-stop exists when nothing qualifies") and
      the recipe's `[]` emission are the machine signal driving the workflow's
      early stop, but this is locked only as SKILL prose (TR3) — no executable
      test asserts the recipe emits an empty result when the qualification filter
      removes every candidate (the filter-and-drop test always leaves ≥1
      survivor). Feed only sub-threshold and/or unmatched `local` units (e.g. a
      sole `lcc 4.0` unit) and assert the recipe output is an **empty** result
      (`[]` / no surviving units). Add the jq-absent structural fallback if a
      recipe fragment uniquely guards the empty case; otherwise note jq-required.
      Verify focused tests + `clj-kondo` + `clj-paren-repair` green and
      `bb commit-check:file-lengths` clean.
      RESOLUTION: added a new sibling deftest
      `incidental-complexity-finder-recipe-empty-qualification-test` in the
      skill-test ns (test-only; no new ns / no production Clojure), reusing
      `run-jq-recipe` + `named-{local,cc}-unit-json`. Feeds two non-qualifying
      units — a sub-threshold matched unit (`sub/threshold`, lcc 4.0 < 5.0) and
      an unmatched `local` row (`unmatched/row`, lcc 30.0, no cc → dropped by A1)
      — and asserts the recipe emits the empty result `[]` (the machine
      early-stop signal): `(= "[]" (str/trim out))` plus a guard that no
      `"var":` survives. Verified live: sub-threshold + unmatched input yields
      `[]`. Per the analysis, no recipe fragment *uniquely* guards the empty case
      beyond the qualification filter (already structurally locked in the
      filter-and-drop fallback), so this behaviour is jq-required; the jq-absent
      fallback re-asserts the qualification-filter fragment
      (`select(.["lcc-total"] >= 5.0 and .gap >= 2.0)`) so a regress is still
      caught structurally. Focused skill-test suite green (7 tests, 55
      assertions, 0 failures); `clj-kondo` 0 findings; `clj-paren-repair`
      Success; file 409 lines (< 800); `bb commit-check:file-lengths` exit 0.
      (See implementation.md pass-14 test-review TR17 entry.)

## Test review follow-ups (review pass 15 — test-shaper)

- [x] TR18 — Pin the qualification filter's inclusive `>=` boundary at the exact
      threshold in `incidental_complexity_finder_skill_test.clj`. The recipe
      filter `select(.["lcc-total"] >= 5.0 and .gap >= 2.0)` is exercised only
      well above (lcc 30 / gap 7.5) and well below (gap 1.5, lcc 4.0) the
      thresholds, so the inclusive boundary is unproven — a regress `>=` → `>`
      (strict) passes every existing test green. Add an executable recipe case
      (extend `incidental-complexity-finder-recipe-filter-and-drop-test` or a new
      sibling deftest reusing `run-jq-recipe` + `named-{local,cc}-unit-json`)
      feeding a unit that sits **exactly** on the boundary — e.g. `lcc 10.0,
      cc 5 → gap 2.0` (gap exactly 2.0) and/or `lcc 5.0, cc 1 → gap 5.0` (lcc
      exactly 5.0) — and assert it **survives** the filter (inclusive `>=`,
      not strict `>`). Keep the jq-absent structural fallback already present
      (the filter fragment is locked there). Verify focused skill-test suite +
      `clj-kondo` + `clj-paren-repair` green and `bb commit-check:file-lengths`
      clean.
      RESOLUTION: added a new sibling deftest
      `incidental-complexity-finder-recipe-boundary-inclusivity-test` in the
      skill-test ns (test-only; no new ns / no production Clojure), reusing
      `run-jq-recipe` + `named-{local,cc}-unit-json`. Feeds two units sitting
      **exactly** on each boundary — `gapedge` (lcc 10.0, cc 5 → gap exactly
      2.0) and `lccedge` (lcc 5.0, cc 1 → lcc exactly 5.0, gap 5.0) — and asserts
      both **survive** the qualification filter, proving the `>=` is inclusive
      (a strict `>` regress drops either boundary unit). jq-absent fallback
      re-asserts the `select(.["lcc-total"] >= 5.0 and .gap >= 2.0)` fragment
      (mirrors TR12/TR16/TR17), so the `>=`→`>` regress fails green whether or
      not jq is installed. Focused suite green (skill-test +
      task-204 definitions: 10 tests, 117 assertions, 0 failures); `clj-kondo` 0
      findings; `clj-paren-repair` Success; skill-test file 442 lines (< 800);
      `bb commit-check:file-lengths` exit 0. (See implementation.md pass-15
      test-review TR18 entry.)

- [x] TR19 — Lock the wrapper `summary` step's substantive NO_TARGET contract in
      `task_204_workflow_definitions_test.clj`
      (`task-lifecycle-in-worktree-test`). The existing "summary prompt detects
      NO_TARGET" assertion locks only `.contains "NO_TARGET"` + the
      resolve-worktree-yield source; the prompt's substantive no-target contract
      is unlocked, so a regress that detects the sentinel but still inspects/
      invents task artifacts (or reports lifecycle outcomes) on a no-target run
      passes green. Add assertions on `summary-text` for the substantive NO_TARGET
      contract substrings present in the prompt: `ignore the `lifecycle` step
      output entirely`, `no worktree was created, no task was created, and no
      lifecycle ran`, and `Do not inspect or invent task artifacts`. This is the
      symmetric companion to TR10 (positive-path terminal contract). Verify
      focused task-204 workflow-definition suite + `clj-kondo` + `clj-paren-repair`
      green and `bb commit-check:file-lengths` clean.
      RESOLUTION: added a new `testing` block to `task-lifecycle-in-worktree-test`
      (same ns, test-only, no production/EDN change) — "summary prompt reports the
      substantive NO_TARGET contract (TR19)" — asserting three `summary-text`
      substrings present verbatim in the EDN summary template: "ignore the
      \`lifecycle\` step output entirely" (the lifecycle output is discarded on a
      no-target run), "no worktree was created, no task was created, and no
      lifecycle ran" (the substantive no-target report), and "Do not inspect or
      invent task artifacts" (the no-fabrication guard). Symmetric companion to
      TR10's positive-path terminal contract lock — a regress that detects the
      sentinel but still inspects/invents artifacts or reports lifecycle outcomes
      on a no-target run now fails green. Focused suite green (skill-test +
      task-204 definitions: 10 tests, 117 assertions, 0 failures); `clj-kondo` 0
      findings; `clj-paren-repair` Success; task-204 definitions file 292 lines
      (< 800); `bb commit-check:file-lengths` exit 0. (See implementation.md
      pass-15 test-review TR19 entry.)

## Test review follow-ups (review pass 16 — test-shaper)

- [x] TR20 — Collapse the redundant fixture-builder pair in
      `incidental_complexity_finder_skill_test.clj`. The ns carries two
      fixture-builder abstractions for the same unit-JSON shape:
      `local-unit-json`/`cc-unit-json` (hard-code `ns "x"`/`var "f"`/`file
      "x.clj"`; used only by the determinism test at lines 166–169) and the
      `named-local-unit-json`/`named-cc-unit-json` parameterized pair (used by
      every other recipe test). The `named-*` builders are a strict superset —
      the plain builders are exactly `(named-local-unit-json "x" "f" …)` /
      `(named-cc-unit-json "x" "f" …)`. Two builders for one shape is incidental
      variation (test-shaper `consistent(fixtures)` + `economical`): a future
      unit-JSON shape change must be threaded through both, and a reader learns
      two near-identical helpers. Fix (test-only, no production/skill/EDN
      change): delete `local-unit-json`/`cc-unit-json` and rewrite the four
      determinism-test call sites (lines 166–169) to the `named-*` builders with
      `"x" "f"`; behaviour is unchanged (identical JSON for `ns "x"`). Verify the
      focused skill-test + task-204 suite green, `clj-kondo` 0, `clj-paren-repair`
      Success, `bb commit-check:file-lengths` clean.
      RESOLUTION: deleted the redundant `local-unit-json`/`cc-unit-json` builders
      and rewrote the four determinism-test call sites (the `line-10`/`line-40`
      `local`/`cc` fixtures) to `(named-local-unit-json "x" "f" …)` /
      `(named-cc-unit-json "x" "f" …)` — the `named-*` superset produces
      byte-identical JSON for `ns "x"`/`var "f"`/`file "x.clj"`, so behaviour is
      unchanged. One fixture-builder pair now serves every recipe test
      (test-shaper `consistent(fixtures)` + `economical`): a future unit-JSON
      shape change threads through one pair, and a reader learns one helper.
      Test-only — no production/skill/EDN change; assertions untouched. Focused
      suite green (skill-test + task-204 definitions: 10 tests, 117 assertions,
      0 failures — identical to pass-15, confirming a pure refactor); `clj-kondo`
      0 findings (no unused-var warning → both deleted builders confirmed gone,
      `named-*` still referenced); `clj-paren-repair` Success; skill-test file
      429 lines (< 800); `bb commit-check:file-lengths` exit 0.

## Test review follow-ups (review pass 17 — test-shaper)

- [x] TR21 — Lock the recipe's emitted-evidence projection (the design's step-5
      acceptance). The skill's `gap` recipe ends with a `map({...})` projection
      re-emitting the chosen target's evidence: `ns`, `var`, `arity`, `file`,
      `line`, `end_line`, `lcc_total`, the six per-dimension burdens
      (`flow_burden`, `state_burden`, `shape_burden`, `abstraction_burden`,
      `dependency_burden`, `working_set`), `findings`, `cc`, `gap`. This is the
      design's named step-5 acceptance ("emit one chosen target with evidence:
      … `lcc-total` with per-dimension burdens, `cc`, `gap`, the `local`
      findings, …"), consumed verbatim by the workflow step-1 prompt to build
      the generated task's evidence block. No test exercises it: every recipe
      test asserts only `ns`/`var`/`line`/`cc`/`gap` survival (the synthetic
      fixtures supply the burden fields but nothing asserts they reappear), and
      there is no structural fallback on the projection map. A regress dropping a
      projected field (`end_line`, `findings`, a burden dimension) or mis-renaming
      one (`flow_burden` → `flow-burden`) passes every existing test green,
      silently degrading the evidence the generated task is built from
      (test-shaper `cover_by(invariants)` + `behavior_focused`). Fix (test-only,
      no production/skill/EDN change — recipe is correct): add a
      projection-contract test to `incidental_complexity_finder_skill_test.clj`
      reusing the `run-jq-recipe` + `named-*-unit-json` harness — feed one
      qualifying matched unit and assert the surviving object carries every
      projected evidence key with its expected value (`end_line`, `lcc_total`,
      the six `*_burden`/`working_set` dimensions, `findings`, `cc`, `gap`); add
      the mirroring jq-absent structural fallback locking the projection-map key
      names verbatim, per the TR12/16/17/18 fallback convention. Verify the
      focused skill-test + task-204 suite green, `clj-kondo` 0, `clj-paren-repair`
      Success, `bb commit-check:file-lengths` clean.
      RESOLUTION: added `incidental-complexity-finder-recipe-projection-contract-test`
      to `incidental_complexity_finder_skill_test.clj` (same ns, test-only — no
      production/skill/EDN change; the recipe is correct). Grounded against the
      live SKILL.md projection (`map({ns, var, arity, file, line, end_line:
      .["end-line"], lcc_total: .["lcc-total"], flow_burden: .["flow-burden"],
      …, working_set: .["working-set"], findings, cc, gap})`). Added a dedicated
      `evidence-local-unit-json` builder whose six burden dimensions carry
      DISTINCT values (11..16) + two `findings` entries, so the recipe's
      dash→underscore rename mapping is verified per dimension (not collapsed
      onto a shared `1`, as the existing `named-local-unit-json` would). jq-present
      branch: feed one qualifying matched unit (lcc 30.0, cc 4 → gap 7.5) and
      assert every projected key survives with its expected value — identity
      (`ns`/`arity`/`file`/`line`), the renamed `end_line`=42, `lcc_total`=30,
      the six `*_burden`/`working_set`=11..16, both `findings`, `cc`, and
      `gap`=7.5. jq-absent fallback (per TR12/16/17/18 convention): lock each
      projected key name verbatim, asserting the eight bare-shorthand keys are
      present and the eight renamed fields appear as `<underscore>: .["<dash>"]`,
      so a dropped/mis-renamed field fails green whether or not jq is installed.
      Focused ns green: **9 tests, 78 assertions, 0 failures** (+1 test over
      pass-16's 8); full `bb clojure:test:unit` suite green; `clj-kondo` 0
      findings; `clj-paren-repair` Success; skill-test file **524 lines** (< 800);
      `bb commit-check:file-lengths` exit 0. (See implementation.md pass-17
      test-review TR21 entry.)

## Code-shaper review follow-ups (review pass 1 — code-shaper)

Source: code-shaper review of the task deliverables (SKILL.md recipe, both EDN
workflows, both test namespaces). Production deliverables are clean; the two
items below are incidental duplication accreted in the recipe-test harness.
Both are test-only — no production/skill/EDN change, all assertions identical.

- [x] CS1 — De-duplicate the `jq`-availability guard in
      `components/workflow-loader/test/psi/workflow_loader/incidental_complexity_finder_skill_test.clj`.
      The literal `(try (zero? (:exit (shell/sh "jq" "--version")))
      (catch Exception _ false))` is repeated verbatim at 7 sites
      (lines 158/213/278/342/375/409/462), one per recipe `deftest`. Its meaning
      ("is jq available?") is illegible at the call sites and a change must
      thread through all 7 (`consistent(idioms)` + `locally_comprehensible`
      defect). Fix: extract a named `jq-available?` `defn-` predicate and call it
      at each `if` site. Mechanical, behaviour-identical. Verify the focused
      skill-test ns green (jq-present path), `clj-kondo` 0, `clj-paren-repair`
      Success, `bb commit-check:file-lengths` clean.
      RESOLUTION: extracted a named `(defn- jq-available? [] …)` predicate
      wrapping the `(try (zero? (:exit (shell/sh "jq" "--version"))) (catch
      Exception _ false))` literal, and replaced all 7 call sites' `(if (try …))`
      with `(if (jq-available?))`. The guard's meaning is now legible at every
      call site and a change threads through one definition. Test-only,
      behaviour-identical — no production/skill/EDN change; assertions untouched.
      Focused skill-test ns green (`clojure -M:test --focus
      psi.workflow-loader.incidental-complexity-finder-skill-test`: 9 tests, 78
      assertions, 0 failures — identical to pass-17, confirming a pure refactor);
      `clj-kondo` 0 findings; `clj-paren-repair` Success;
      `bb commit-check:file-lengths` exit 0. (Committed with CS2.)

- [x] CS2 — Collapse the repeated recipe-test `let` preamble in the same ns.
      Every recipe `deftest` opens with the identical
      `{:keys [skill]} (incidental-complexity-finder-skill)` →
      `body (slurp (io/file (:file-path skill)))` →
      `recipe (extract-jq-recipe body)` shape (8 `body`-slurps, 7
      `recipe`-extracts), obscuring each test's distinct payload. Fix: extract a
      single `(defn- skill-recipe [] …)` helper returning the recipe so each test
      binds the recipe in one line. Behaviour-identical; reduces both the
      per-test working set and the file length (currently 524 lines). Verify the
      focused skill-test ns green, `clj-kondo` 0, `clj-paren-repair` Success,
      `bb commit-check:file-lengths` clean.
      RESOLUTION: extracted `(defn- skill-recipe [] …)` — slurps the loaded
      SKILL.md and returns its extracted jq recipe — and collapsed all 7 recipe
      `deftest` preambles to a single `(let [recipe (skill-recipe)] …)` binding
      (the determinism/ranking/projection tests keep their additional per-test
      `let` bindings — fixture units, `gap-of`, `gap-key` — alongside the one-line
      recipe bind). The content-lock test (which uses `body` directly, not the
      recipe) is intentionally left on the `{:keys [skill]}`/`body` preamble.
      Eliminated 7 `body`-slurps + 7 `recipe`-extracts (one `body` slurp remains,
      in the content-lock test). Behaviour-identical, test-only — no
      production/skill/EDN change; assertions untouched. Focused skill-test ns
      green (9 tests, 78 assertions, 0 failures); `clj-kondo` 0 findings;
      `clj-paren-repair` Success; `bb commit-check:file-lengths` exit 0; file
      524 lines (< 800 — the net line delta is ~0: the two extracted helpers
      offset the collapsed preambles).

## Implementation review follow-ups (independent pass — task-implementation-review)

- [x] IR-A — Bring plan.md into coherence with the shipped `.edn` wrapper (D1).
      plan.md still describes the wrapper as `.psi/workflows/task-lifecycle-in-worktree.md`
      in `.md`-with-EDN-body form "mirroring `implement-task-in-worktree.md`"
      (Artifact locations, lines ~63–64), and the Approach / Verified-grammar-anchors
      sections cite `implement-task-in-worktree.md` as the loadable precedent
      without the D1 correction (lines ~40,46,72,140). This contradicts the
      shipped artifact (`task-lifecycle-in-worktree.edn`), design.md's F6/D1 note,
      and implementation.md's documented D1 deviation. Per Munera (`plan.md
      changes ↔ approach changes`): update plan.md's wrapper file path/form to
      `.edn` and re-anchor the precedent on the loadable
      `review-implementation-in-worktree.edn` (note the D1 reason). Doc-only; no
      code change. Verify coherence across design/plan/impl after the edit.
      RESOLUTION: reconciled plan.md to the shipped `.edn` wrapper in five places
      (plan-only; no code/test/EDN change). (1) **Artifact locations**: wrapper
      path → `.psi/workflows/task-lifecycle-in-worktree.edn` (multi-step `.edn`
      map, sibling to the loadable `review-implementation-in-worktree.edn`), with
      an explicit **D1 deviation** note citing the live rejection
      (`parser.clj:162`, `body-starts-with-edn-map?`) and that
      `implement-task-in-worktree.md` itself does not load. (2) **Key decisions →
      Worktree continuity**: "structurally identical to the verified
      `implement-task-in-worktree`" → "structurally identical to the loadable
      `review-implementation-in-worktree.edn` (the `.edn` realisation of the
      intended `implement-task-in-worktree` shape — see D1)". (3) **Grammar-
      conformant handoff wiring** precedent: `implement-task-in-worktree.md` →
      the loadable `review-implementation-in-worktree.edn`. (4) **Verified grammar
      anchors**: replaced the `implement-task-in-worktree.md` anchor block with a
      `review-implementation-in-worktree.edn` block, noting the `.md` form does
      not load. (5) **Slice 2**: "Author the `.md`-with-EDN wrapper … mirroring
      `implement-task-in-worktree`" → "Author the `.edn` wrapper … mirroring the
      loadable `review-implementation-in-worktree.edn`". All remaining
      `implement-task-in-worktree` mentions in plan.md are now inside D1-correction
      context (naming it as the intended-but-non-loadable shape), matching
      design.md's F6/D1 note and implementation.md's D1 deviation. Verified the
      shipped artifact is `.psi/workflows/task-lifecycle-in-worktree.edn` (not
      `.md`). design ↔ plan ↔ implementation now coherent on the wrapper file
      form. Doc-only — no code/test change.

## Test review follow-ups (review pass 18 — task-test-review)

- [x] TT-A — Lock all three design-named step-1 `:skills` in
      `reduce-incidental-complexity-test`. The design (Deliverable 2, Step 1)
      requires step-1 to carry the `incidental-complexity-finder`, `gordian`, and
      `code-shaper` skills, and the shipped
      `.psi/workflows/reduce-incidental-complexity.edn` declares all three
      (`:skills ["incidental-complexity-finder" "gordian" "code-shaper"]`). The
      test
      (`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`,
      `reduce-incidental-complexity-test`, "select-and-create … carries work-on
      tool + incidental-complexity-finder skill") asserts only
      `(some #{"incidental-complexity-finder"} (:skills select-step))`, so a
      regress dropping `gordian` or `code-shaper` passes green. Per the
      task-test-review criterion `∀b ∈ behaviour(design). ∃t. covers(t,b)`, add
      assertions that `:skills` contains `"gordian"` and `"code-shaper"` (the
      selection-methodology + refactor-shaping skills) alongside the existing
      `incidental-complexity-finder` lock. Test-only; no production/skill/EDN
      change. Verify the focused task-204 ns green, `clj-kondo` 0,
      `clj-paren-repair` Success, `bb commit-check:file-lengths` clean.
      RESOLUTION: extended the existing "select-and-create … carries work-on
      tool" `testing` block (renamed to "… + all three design-named skills") in
      `task_204_workflow_definitions_test.clj`'s `reduce-incidental-complexity-test`
      (same ns, test-only — no production/skill/EDN change) with two assertions:
      `(some #{"gordian"} (:skills select-step))` (selection methodology) and
      `(some #{"code-shaper"} (:skills select-step))` (refactor shaping),
      alongside the existing `incidental-complexity-finder` lock. Verified the
      shipped EDN's step-1 declares
      `:skills ["incidental-complexity-finder" "gordian" "code-shaper"]` before
      locking; a regress dropping `gordian` or `code-shaper` now fails green.
      Focused task-204 ns green (2 tests, 60 assertions, 0 failures — +2 over
      pass-17's 58); `clj-kondo` 0 findings; `clj-paren-repair` Success; task-204
      definitions file 301 lines (< 800); `bb commit-check:file-lengths` exit 0.

## Test review follow-ups (review pass 19 — task-test-review)

- [x] TT-B — Lock the step-1 base-refresh behaviour in
      `reduce-incidental-complexity-test`. Design Deliverable 2, Step 1 first
      bullet requires "Refresh base: `git fetch origin master`; treat
      `origin/master` as the authoritative base" and "Base the worktree on
      `origin/master`"; the shipped `.psi/workflows/reduce-incidental-complexity.edn`
      step-1 prompt emits all three (`git fetch origin master`, "Treat
      `origin/master` as the authoritative base", "Base the worktree on
      `origin/master`"). The test
      (`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`,
      `reduce-incidental-complexity-test`) never asserts these, so a regress
      dropping the base-refresh or rebasing the worktree off stale local `master`
      passes green. Per `∀b∈behaviour(design).∃t.covers(t,b)`, add `select-text`
      substring assertions for `git fetch origin master` and the
      authoritative-`origin/master`-base claim. Test-only; no production/skill/EDN
      change. Verify the focused task-204 ns green, `clj-kondo` 0,
      `clj-paren-repair` Success, `bb commit-check:file-lengths` clean.
      RESOLUTION: added a "select-and-create prompt locks the origin/master
      base-refresh (TT-B)" `testing` block to `reduce-incidental-complexity-test`
      (same ns, test-only — no production/skill/EDN change) asserting three
      `select-text` substrings: `git fetch origin master` (the base refresh),
      "Treat `origin/master` as the authoritative base" (the authoritative-base
      claim), and "Base the worktree on `origin/master`" (the worktree base). All
      three present verbatim in the shipped EDN step-1 prompt; a regress dropping
      the fetch or rebasing off stale local `master` now fails green. Focused
      task-204 ns green (2 tests, 67 assertions, 0 failures — +7 over pass-18's
      60, shared with TT-C/TT-D); `clj-kondo` 0 findings; `clj-paren-repair`
      Success; file 337 lines (< 800); `bb commit-check:file-lengths` exit 0.

- [x] TT-C — Lock the baseline *capture commands* (not just the filenames) in
      `reduce-incidental-complexity-test`. Design Step 1 names the baseline
      capture invocations — `before-local.json` ← `bb gordian local --json`
      (bare, NO `--sort`) and `before-diagnose.edn` ← `bb gordian diagnose --edn`
      — and the shipped EDN emits both. The test
      ("select-and-create prompt embeds the enforcing gate flags + both
      baselines") asserts only the output *filenames* (`before-local.json` /
      `before-diagnose.edn`), leaving the generating commands unlocked: a regress
      to `bb gordian local --sort total --json` (the selector ranking call, which
      the EDN explicitly forbids as a baseline — "bare, NO `--sort`") or a wrong
      `diagnose` flag passes green. Add `select-text` substring assertions for
      `bb gordian local --json` and `bb gordian diagnose --edn`. Test-only; no
      production/skill/EDN change. Verify the focused task-204 ns green,
      `clj-kondo` 0, `clj-paren-repair` Success, `bb commit-check:file-lengths`
      clean.
      RESOLUTION: added a "select-and-create prompt locks the baseline capture
      commands (TT-C)" `testing` block (same ns, test-only) asserting two
      `select-text` substrings: `bb gordian local --json` (the bare before-local
      capture) and `bb gordian diagnose --edn` (the before-diagnose capture) —
      the generating *commands*, distinct from the output filenames already
      locked. A regress to the forbidden selector call
      `bb gordian local --sort total --json` (the EDN's own "bare, NO `--sort`"
      baseline rule) or a wrong diagnose flag now fails green. Both substrings
      present in the shipped EDN. Verified green with TT-B/TT-D (2 tests, 67
      assertions, 0 failures); `clj-kondo` 0; `clj-paren-repair` Success; file
      337 lines (< 800); `bb commit-check:file-lengths` exit 0.

- [x] TT-D — Lock the A5/A2 direction-of-change in
      `reduce-incidental-complexity-test`. The Phase-1 acceptance is directional:
      A5 — the target unit's `lcc-total` "decreased versus its `before-local.json`
      value"; A2 — "the after total is strictly less than the before total". The
      existing `F3 lock` test ("keys A5/A2 acceptance on (ns, var, arity, line)")
      locks only the join *key*, not the direction, so a paraphrase/regress
      weakening "decreased" → "changed" or "strictly less" → "not greater"
      passes green while gutting the acceptance. Both substrings are present
      verbatim in the shipped EDN. Add `select-text` substring assertions for
      `decreased versus its \`before-local.json\` value` (A5) and
      `after total is strictly less than the before total` (A2), companion to the
      existing key lock. Test-only; no production/skill/EDN change. Verify the
      focused task-204 ns green, `clj-kondo` 0, `clj-paren-repair` Success,
      `bb commit-check:file-lengths` clean.
      RESOLUTION: added a "select-and-create prompt locks the A5/A2
      direction-of-change (TT-D)" `testing` block (same ns, test-only) asserting
      two `select-text` substrings companion to the existing F3 key lock:
      "decreased versus its `before-local.json` value" (A5 directional reduction)
      and "after total is strictly less than the before total" (A2 strict net
      decrease). The F3 lock covers only the `(ns, var, arity, line)` key; these
      lock the *direction*, so a paraphrase weakening "decreased" → "changed" or
      "strictly less" → "not greater" now fails green. Both substrings present
      verbatim in the shipped EDN. Verified green with TT-B/TT-C (2 tests, 67
      assertions, 0 failures); `clj-kondo` 0; `clj-paren-repair` Success; file
      337 lines (< 800); `bb commit-check:file-lengths` exit 0.

## Test review follow-ups (review pass 20 — task-test-review)

- [x] TT-E — `reduce-incidental-complexity-test`'s early-stop block locks only
      the worktree half of the design's two-part early stop. Design Deliverable 2,
      Step 1 **Early stop** bullet: "if no qualifying unit exists, stop and
      report — do not create a worktree **or task**". The EDN step-3 emits BOTH
      `Do NOT create a worktree` and `Do NOT create a task`, but the test's
      "select-and-create prompt encodes the early-stop-on-no-target intent" block
      asserts only `.contains "Do NOT create a worktree"` (+ the `no unit qualif`
      sentinel). The task half is uncovered: a regress dropping
      `Do NOT create a task.` — letting a no-target run create an orphan task dir
      while still skipping the worktree — passes every existing test green. Same
      TR13/TR14-class symmetry gap (one half of a two-part contract locked, the
      sibling half left uncovered) and the same substring-lock kind TT-B/C/D use.
      Fix: extend the existing early-stop `testing` block in
      `reduce-incidental-complexity-test` with an assert that `select-text`
      contains `Do NOT create a task`. Test-only substring lock on the shipped
      `reduce-incidental-complexity.edn`; no production/skill/EDN change; file is
      337 lines (< 800 `components/` guard). Run focused task-204 ns + `clj-kondo`.
      RESOLUTION: extended the existing "select-and-create prompt encodes the
      early-stop-on-no-target intent" `testing` block in
      `reduce-incidental-complexity-test` (same ns, test-only — no
      production/skill/EDN change) with one assertion: `select-text` contains
      `Do NOT create a task` — the task half of the design's two-part early stop,
      companion to the existing `Do NOT create a worktree` lock. The shipped EDN
      step-3 emits both `Do NOT create a worktree.` and `Do NOT create a task.`;
      a regress dropping the task half — letting a no-target run create an orphan
      task dir while still skipping the worktree — now fails green (TR13/TR14-class
      symmetry gap closed). Focused task-204 ns green (2 tests, 68 assertions,
      0 failures — +1 over pass-19's 67); `clj-kondo` 0 findings;
      `clj-paren-repair` Success; file 339 lines (< 800);
      `bb commit-check:file-lengths` exit 0.

## Test review follow-ups (review pass 21 — task-test-review)

- [x] TT-F — Lock the selector's two-lens invocation commands in
      `incidental-complexity-finder-skill-content-lock-test`. Design Deliverable 1,
      step 1 ("Run both lenses in machine form") names the two data-source
      commands: `bb gordian local --sort total --json` and
      `bb gordian complexity --json`. SKILL.md §1 emits both verbatim — they
      produce the `/tmp/icf-local.json` / `/tmp/icf-cc.json` inputs the embedded
      jq recipe consumes. The content-lock test locks the gap method, thresholds,
      scope, the `(ns, var, arity, line)`/`@line` join key, the top-5 guard, and
      evidence/coverage-hint emission — but NOT these two lens commands; the
      recipe-execution tests rewrite the temp paths and never exercise the
      producing commands either. A regress (wrong subcommand, dropped `--json`,
      or losing the selector-vs-baseline `--sort total`/bare distinction the
      design draws in A5/A2) passes green while breaking the recipe's inputs. This
      is the same symmetry the workflow test's TT-C already enforces for the
      *baseline* capture commands; the only current mention of `local --sort
      total` in tests is a TT-C comment, not an assertion. Fix: extend
      `incidental-complexity-finder-skill-content-lock-test` with a `testing`
      block asserting `body` contains `bb gordian local --sort total --json` and
      `bb gordian complexity --json`. Test-only substring lock on the shipped
      SKILL.md; no production/skill/EDN change. Run focused
      `incidental-complexity-finder-skill-test` + `clj-kondo`.
      RESOLUTION: extended `incidental-complexity-finder-skill-content-lock-test`
      (same ns, test-only — no production/skill/EDN change) with a new `testing`
      block "encodes the step-1 two-lens invocation commands (TT-F)" asserting
      `body` contains `bb gordian local --sort total --json` (selector lens,
      with `--sort total --json`) and `bb gordian complexity --json` (cc lens).
      Both strings verified present verbatim in the shipped SKILL.md §1 before
      locking, so each is a regression guard. Companion to the workflow test's
      TT-C *baseline* capture-command lock; closes the symmetry where only a
      TT-C comment mentioned `local --sort total`. A regress (wrong subcommand,
      dropped `--json`, or losing the `--sort total`/bare selector-vs-baseline
      distinction A5/A2 draws) now fails green. Focused
      `incidental-complexity-finder-skill-test` green (9 tests, 80 assertions,
      0 failures — +2 over pass-17's 78); `clj-kondo` 0 findings;
      `clj-paren-repair` Success; file 537 lines (< 800);
      `bb commit-check:file-lengths` exit 0.

## Test review follow-ups (review pass 22 — task-test-review)

- [x] TT-G — Lock the A2 "touched units = metric-derived set" discriminator in
      `reduce-incidental-complexity-test`. Locked decision 4 and the design's
      "Net burden (A2)" paragraph define "touched units" as the metric-derived
      set (every unit whose recomputed `lcc-total` changed between
      `before-local.json` and the after-`local` run), explicitly NOT the
      diff/touched-files set — the rationale being that file-scoping would let a
      refactor hide relocated burden in an untouched caller. The shipped step-1
      prompt carries this verbatim. Current A2 locks are only F3 (the
      `(ns, var, arity, line)` key) and TT-D (the strictly-less direction);
      neither anchors the metric-vs-file derivation, so a paraphrase to "units
      whose source/files changed" passes green while defeating the global-
      recompute net check. Fix: extend the F3 / TT-D `testing` cluster in
      `reduce-incidental-complexity-test`
      (`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`)
      with an assertion that `select-text` contains
      `the set is computed from the metric, not from the diff/touched files`
      (verified present verbatim in the shipped EDN). Test-only substring lock;
      no production/skill/EDN change; task-204 test ns is well under the
      `components/` 800-line length guard. Run focused
      `task-204-workflow-definitions-test` + `clj-kondo`.
      RESOLUTION: added a "select-and-create prompt locks the metric-derived
      touched-set discriminator (TT-G)" `testing` block to the F3/TT-D cluster in
      `reduce-incidental-complexity-test` (same ns, test-only — no
      production/skill/EDN change) asserting `select-text` contains
      `the set is computed from the metric, not from the diff/touched files`.
      Verified present verbatim in the shipped `reduce-incidental-complexity.edn`
      step-1 prompt before locking. F3 locks only the `(ns, var, arity, line)`
      key and TT-D only the strictly-less direction; this anchors the
      metric-vs-file derivation (Locked decision 4 / "Net burden (A2)"), so a
      paraphrase to "units whose source/files changed" — which would defeat the
      global-recompute net check, letting a refactor hide relocated burden in an
      unedited caller — now fails green. Focused task-204 ns green (2 tests, 69
      assertions, 0 failures — +1 over pass-21's 68); `clj-kondo` 0 findings;
      `clj-paren-repair` Success; file 351 lines (< 800);
      `bb commit-check:file-lengths` exit 0.

## Test review follow-ups (review pass 23 — task-test-review)

- [x] TT-H — Lock the step-1 **worktree-scoped task creation** behaviour (the P3
      resolution) in `reduce-incidental-complexity-test`. Design Deliverable 2,
      Step 1 ("Allocate the next task id, create munera/open/NNN-slug/…",
      "Commit the task creation") plus the resolved P3 (implementation.md: NNN is
      allocated by scanning the WORKTREE's `open/ ∪ closed/`, not the outer
      checkout's, so the id does not collide; the create + commit happen on the
      worktree branch so the emitted `munera_task_path:` resolves for the
      delegated lifecycle). The shipped EDN step-1 prompt encodes this verbatim
      (step 5: "scanning the WORKTREE's `munera/open/` and `munera/closed/`" +
      "so the id does not collide with the outer checkout's open tasks"; step 8:
      "Commit the task creation ON THE WORKTREE BRANCH"). No test anchors it: a
      regress reverting to outer-checkout-scoped id allocation (reintroducing P3 —
      colliding with the outer checkout's open tasks) or committing on the wrong
      branch (so the emitted `munera_task_path:` does not resolve under the
      delegated `work-on`) passes every existing test green. Same TT-class
      symmetry gap as TT-B/TT-G: a design-resolved, prompt-encoded correctness
      behaviour left unlocked. Fix: extend `reduce-incidental-complexity-test`
      (`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`)
      with a `testing` block asserting `select-text` contains
      `scanning the WORKTREE's`, `so the id does not collide with the outer checkout's open tasks`,
      and `Commit the task creation ON THE WORKTREE BRANCH` (all verified present
      verbatim in the shipped EDN). Test-only substring lock; no
      production/skill/EDN change; task-204 test ns is well under the 800-line
      `components/` length guard. Run focused `task-204-workflow-definitions-test`
      + `clj-kondo`.
      RESOLUTION: added a "select-and-create prompt locks the worktree-scoped
      task creation (TT-H)" `testing` block to the TT-G/TT-D/F3 cluster in
      `reduce-incidental-complexity-test` (same task-204 ns, test-only — no
      production/skill/EDN change) asserting `select-text` contains all three
      P3-resolution strings, each verified present verbatim in the shipped
      `reduce-incidental-complexity.edn` step-1 prompt before locking:
      (1) `scanning the WORKTREE's` (step-5 worktree-scoped NNN allocation),
      (2) `so the id does not collide with the outer checkout's open tasks`
      (the P3 collision-avoidance rationale), and (3)
      `Commit the task creation ON THE WORKTREE BRANCH` (step-8 commit-on-branch
      so the emitted `munera_task_path:` resolves under the delegated `work-on`).
      Closes the same TT-class symmetry gap as TT-B/TT-G: a regress to
      outer-checkout-scoped id allocation (reintroducing P3) or committing on the
      wrong branch now fails green. Focused task-204 ns green (2 tests, 72
      assertions, 0 failures — +3 over pass-22's 69); `clj-kondo` 0 findings;
      `clj-paren-repair` Success; file 369 lines (< 800).

## Test review follow-ups (review pass 24 — task-test-review)

- [ ] TT-I — Lock the generated-design contract's **Blast radius** constraint and
      **Phase-0 hard gate / untestable-tangle handling** in
      `reduce-incidental-complexity-test`. The step-7 generated `design.md`
      contract in `reduce-incidental-complexity.edn` is a named design behaviour
      ("Generated tasks carry the two-phase behaviour-preserving contract"). TR2
      locked the Phase-0 characterization-test gate + the behaviour-identical
      constraint, and F3 the A5/A2 key — but two further named clauses of that
      same contract carry no assertion: (1) the **Blast radius** scope fence
      ("the target unit PLUS the minimal surrounding helpers required to
      decomplect it; no unrelated cleanup"), and (2) the Phase-0 **hard gate +
      untestable-tangle escape hatch** ("If the unit cannot be characterized
      safely … a minimal seam … or (b) is closed with the finding (scope drift
      -> close per Munera). No refactor proceeds without a green net."). Both
      strings are present verbatim in the shipped EDN (`grep` 1 each) and absent
      from `task_204_workflow_definitions_test.clj` (`grep` 0 each). A regress
      dropping the blast-radius fence (admitting unrelated cleanup that inflates
      the diff while still passing the net-burden check via relocation) or the
      untestable-tangle/green-net hard gate (letting a refactor proceed on an
      uncharacterized unit without the prescribed seam-or-close decision) passes
      every existing test green — the same sub-clause-lock standard
      TR2/TT-D/TT-G applied to the other contract clauses, left unapplied here.
      Fix: extend the TR2 contract `testing` cluster in
      `reduce-incidental-complexity-test`
      (`components/workflow-loader/test/psi/workflow_loader/task_204_workflow_definitions_test.clj`,
      same task-204 ns, test-only — no production/skill/EDN change) with
      `select-text` substring locks for "Blast radius: the target unit PLUS the
      minimal surrounding helpers required to decomplect it; no unrelated
      cleanup", "No refactor proceeds without a green net", "cannot be
      characterized safely", and "scope drift -> close per Munera". Run focused
      task-204 ns + `clj-kondo`; keep under the 800-line `components/` guard.
