# Steps — Simplify `start-nrepl!`

## Slice 0 — Phase 0 characterization net (gate before any production edit)

- [x] Run the existing net to confirm a green starting point:
      `bb clojure:test:scry --namespace psi.app-runtime-nrepl-test`.
- [x] Add characterization test: `.nrepl-port` file is written with the bound port
      and marked `deleteOnExit` after `start-nrepl!` (use tmp-dir + `user.dir`
      pattern like the existing live test; assert file contents = `(:port srv)`).
- [x] Add characterization test: `stop-nrepl!` deletes `.nrepl-port` only when its
      contents match the running server port (and leaves a non-matching file).
- [x] Add characterization test: session `:nrepl-runtime` publication uses the bound
      (random) port, not the requested port, and occurs only when `ctx` + active
      session id are present (assert via session EQL query, as the existing test).
- [x] Add characterization test: the stderr connection notice
      `"  nREPL : host:port (connect with your editor)"` is emitted to stderr (not
      stdout); prefer a real captured-stream seam over `with-redefs`.
- [x] Run `bb clojure:test:scry --namespace psi.app-runtime-nrepl-test` and confirm
      all new + existing tests are GREEN against UNMODIFIED production code.
- [x] Run `bb lint` on the test file; fix any findings.
- [ ] If the unit cannot be characterized safely, record the finding in
      implementation.md and either introduce a minimal seam first or close per
      Munera scope-drift — do NOT proceed to Slice 1 without a green net.
- [ ] Commit: `⚒ 214 Phase 0: characterization net for start-nrepl! behaviour`.

## Plan/steps review follow-up — ambiguity (ψ)

- [x] Resolve Slice 2's skip criterion: steps say "pass with margin", plan says
      "already satisfied". Specify the exact deterministic condition under which
      Slice 2 is skipped (e.g. SKIP iff A1 strictly decreased AND
      `sum_after < sum_before`; otherwise perform), and remove the undefined
      "with margin" wording.
      → Resolved: SKIP iff A1 strict-decrease AND A2 `sum_{T} after < sum_{T} before`
      both hold; otherwise PERFORM. "With margin" removed in both steps.md + plan.md.
- [x] Resolve `start-server-quietly`'s signature: state explicitly whether the helper
      performs `(requiring-resolve 'nrepl.server/start-server)` internally (arg list =
      `[port]`) or receives the resolved fn (arg list = `[start-server port]`), so the
      `requiring-resolve` dependency burden's location is fixed and A2 accounting is
      well defined. Align Slice 1 step wording accordingly.
      → Resolved: arg list `[port]`; helper does `requiring-resolve` internally.
      A2 accounting fixed (burden charged to seam, `before := 0`); Slice 1 wording
      aligned in steps.md + plan.md.

## Slice 1 — Phase 1 extract stdout-suppression seam

- [ ] Add `start-server-quietly` helper in `nrepl_runtime.clj`. Arg list = `[port]`
      (single arg). The helper OWNS nrepl-start resolution: it performs
      `(requiring-resolve 'nrepl.server/start-server)` internally — the
      `requiring-resolve` moves out of `start-nrepl!` and into the seam, so the
      nrepl dependency burden is charged to `start-server-quietly` (before := 0),
      removing it from the target's A1 lcc-total. Body: resolve `start-server` →
      `original-systemout` save → `binding *out* *err*` + `System/setOut(stderr)` →
      `(start-server :port port)` → `finally` restore; returns the server map.
- [ ] Replace the inline `try`/`binding`/`finally` block in `start-nrepl!` with a
      call to `start-server-quietly`; keep the rest of the orchestration unchanged.
- [ ] Run `clj-paren-repair components/app-runtime/src/psi/app_runtime/nrepl_runtime.clj`
      to balance/format.
- [ ] Re-read the edited file to confirm coherence (`sync` after edit).
- [ ] Run `bb clojure:test:scry --namespace psi.app-runtime-nrepl-test` — GREEN.
- [ ] Run `bb lint` — clean.
- [ ] Commit: `⚒ 214 Phase 1: extract start-server-quietly seam`.

## Slice 2 — Phase 1 collapse incidental duplication (contingent)

- [ ] Check A1/A2 after Slice 1 (see Slice 3 commands). SKIP this slice iff BOTH
      hold: A1 — target `start-nrepl!` lcc-total strictly decreased vs the
      `before-local.json` baseline (`6.015383232244966`); AND A2 — over
      `T = {u | before(u) != after(u)}` (line-insensitive key; baseline-absent
      `before(u) := 0`), `sum_{T} after < sum_{T} before`. Otherwise (either check
      fails or is exactly equal) PERFORM the slice.
- [ ] If still needed: lift the endpoint map `{:host :port :endpoint}` into a single
      local (e.g. `runtime`) used for both `reset!` and the session publication,
      removing the duplicated literal and `(str host ":" (:port server))` repetition.
- [ ] `clj-paren-repair` the file; re-read for coherence.
- [ ] Run `bb clojure:test:scry --namespace psi.app-runtime-nrepl-test` — GREEN.
- [ ] Run `bb lint` — clean.
- [ ] Commit: `⚒ 214 Phase 1: collapse duplicated endpoint-map literal`.

## Slice 3 — acceptance verification + close-out

- [ ] A1: `bb gordian local --json` from worktree root; compare target
      `(psi.app-runtime.nrepl-runtime, start-nrepl!, 4)` lcc-total against
      `before-local.json` value `6.015383232244966` — must have DECREASED.
- [ ] A2: from the same after-`local` run, compute `T = {u | before(u) != after(u)}`
      (line-insensitive key; baseline-absent `before(u) := 0`) and verify
      `sum_{T} after < sum_{T} before`.
- [ ] A3: `bb gordian gate --baseline munera/open/214-simplify-start-nrepl/before-diagnose.edn
      --fail-on new-cycles,new-high-findings --max-new-medium-findings 0` — exit 0.
- [ ] A4: `bb clojure:test:scry --namespace psi.app-runtime-nrepl-test`,
      `bb clojure:test:unit`, and `bb lint` — all GREEN/clean.
- [ ] A5: confirm minimality — only `nrepl_runtime.clj` + the test ns touched;
      helpers stay within blast radius; no unrelated cleanup.
- [ ] Record A1–A5 results (numbers + pass/fail) in implementation.md.
- [ ] Commit: `⚒ 214 acceptance: A1–A5 verified, start-nrepl! simplified`.
- [ ] Close task: `git mv munera/open/214-simplify-start-nrepl munera/closed/` and
      remove the task's entry from `munera/plan.md`.
