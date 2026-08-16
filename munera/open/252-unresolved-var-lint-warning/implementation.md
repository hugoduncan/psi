- architectural review added 3 new design steps
- ambiguity review added 2 new design steps
- no inconsistency review feedback
- inconsistency review (re-pass): no new actionable inconsistency feedback — verified cached baseline reproduces design.md Evidence exactly (572/737, `errors: 0, warnings: 2`); stale-cache provenance confirmed current (2.9.0-beta1, design-step 8 accurate; `~$request` present, `~$get`/`~$post` absent in cache — mechanism story consistent); root `.clj-kondo/config.edn` has no `:hooks`/with-channel entry and its `:lint-as` mirror comment is scoped to `components/*` imports, so plan decision 1's no-root-mirror choice follows the established root-import (with-channel) precedent exactly; root `:output {:exclude-files [".clj-kondo/imports/"]}` excludes import-dir findings output only (config still read/applied — with-channel proves it), no interaction with the plan; no nested .gitignore. Remaining candidates consumed by design-steps 6-9 (CI-enforceability gap → design-step 9, do not re-file).
- ambiguity review (re-pass): added 1 new design step (9 — AC1 CI-enforceability gap)
- architectural review (re-pass): no new actionable architectural-fit feedback — current design.md already resolves prior arch steps 1-3; committed mechanism (per-library import registration, analysis-level) conforms to λ lint(f), λ fix(bug), small, change_chain proof, localization. Scope boundary well-drawn; no SCOPE_QUESTION.
- ambiguity review (re-pass): no new actionable ambiguity feedback — prior ambiguity items 1-2 resolved in current design.md (committed mechanism = analysis-level registration in the http-kit import dir; AC1 pins repo-wide lint as proof surface). The remaining facility "or" (analyze-call/namespace hook vs `:namespaces` `:defined-by` declaration) is an explicitly declared implementation choice with acceptance closure, not a fresh ambiguity — do not re-file.
- inconsistency review (re-pass): no new actionable inconsistency feedback — design.md line/version/mechanism claims verified consistent against deps.edn, .clj-kondo artifacts, and task 251; remaining candidates already consumed by prior passes.

## Implementation slice — relevant non-task files

All 5 design-steps are resolved; design.md (Mechanism/Constraints/Acceptance) is the contract for the implementation slice. Non-task paths the slice touches:
- Target: `.clj-kondo/imports/http-kit/http-kit/config.edn` — extend (currently only the server-side `:hooks {:analyze-call ...}`)
- Hook impl dir (only if hook route chosen): `.clj-kondo/imports/http-kit/http-kit/httpkit/` — mirror `with_channel.clj`
- Forbidden to change (AC2): `.clj-kondo/config.edn` — root `:unresolved-symbol :exclude` stays exactly `[(malli.core/=>)]`
- Facility examples: `.clj-kondo/imports/metosin/malli/config.edn` (per-library `:linters`), `.clj-kondo/imports/funcool/promesa/config.edn` (`:lint-as`)
- Proof surface: `bb.edn` `lint` task (line ~242); `deps.edn` `:lint` alias (line ~229, clj-kondo 2025.09.19); `.github/workflows/ci.yml` "Lint" step (`bb lint`)
- Warning site: `extensions/dev-http/test/extensions/dev_http_test.clj` lines 572 (`http-client/get`) and 737 (`http-client/post`)
- Version pins: `deps.edn` + `extensions/dev-http/deps.edn` — http-kit 2.8.0

Maintain design.md Constraints when implementing: λ lint(f) fix hierarchy (fix > suppress(inline) > exclude ≫ suppress(global)) and λ fix(bug) (no workaround ⇝ complexity) — analysis-level resolution only, never exclusion/ignore; small (one rule cluster confined to the http-kit import dir); localization (root config untouched, per AC2).

## Design review context (re-pass — inconsistency)

- Verified design.md cross-references: (1) `:lint` alias has no `--copy-configs`/classpath wiring — `.clj-kondo/imports/**/config.edn` auto-merges (proven by the working with-channel hook), so a `:namespaces` addition there applies without extra wiring; (2) task 251's 800-line limit lives in `bb.edn` (`commit-check:file-lengths`, `file-length-legacy-max-lines`) — design.md's "bb.edn change disjoint from this `.clj-kondo` change" holds.

## Design review context (re-pass — ambiguity)

- Facility guidance (non-obvious, for the implementation slice): the repo's per-library convention is `:lint-as` in `.clj-kondo/imports/<lib>/<lib>/config.edn`, mirrored into root `.clj-kondo/config.edn` "so individual-file linting works without a full classpath scan" (plus component-local copies under `components/*/.clj-kondo/imports/`). `:lint-as` teaches clj-kondo how to analyze macros the *project invokes*; http-kit's `defreq` is invoked inside the dependency jar (never in-repo), so `:lint-as` does **not** apply — the fix needs var *registration* (e.g. `:namespaces {org.httpkit.client {get {:defined-by clojure.core/defn} ...}}` in the imports-dir config.edn), which clj-kondo reads independent of classpath. No root config mirror and no component-local copy needed: http-kit is used only by extensions/dev-http (extensions lint via root config + root imports dir), and there is no existing `components/*/.clj-kondo/imports/http-kit/`. Design AC2 confinement is coherent with repo convention.

## Design review context (re-pass — architectural fit)

- AC1 proof surface is CI-enforced — **corrected/struck (slice 19, 2026-08-15)**: overstated. CI's `bb lint` runs with no cache and no `--dependencies` → the http-kit jar is never analyzed → trivially clean with or without the fix; the lint surface itself is **not** CI-enforced. The CI-enforceable regression surface is the committed `^:integration` test vehicle (slices 7-18: `http-kit-defreq-analysis-level-resolution-test`, `gitignore-http-kit-tracking-ground-truth-test`, `with-channel-hook-semantics-guard-test`, run via `bb clojure:test:integration`). Retained true part: the CI-installed latest clj-kondo binary is only `--version`-checked, never invoked by the lint gate — effective lint-gate analyzer is the pinned JVM clj-kondo 2025.09.19 only; design.md's "re-verify the hook against both on any version bump" is overcautious for the lint gate.

## Design review context (turn 3 — inconsistency)

- No new actionable items: every candidate contradiction in design.md (Evidence `errors: 0`/`warnings: 2` vs Friction's missing-declaration claim and singular `post` focus; `.clj-kondo/config.edn` target vs per-library import layout; false-negative attribution) is already consumed by existing design-steps 1-3 with the same remedy — do not re-file in later passes.

## Design review context (turn 2 — ambiguity)

- Both new items stem from the Suggested change's wording, which the turn-1 items do not fully pin down: (1) the 'or'-joined fix remedies + multi-reading "include the correct namespace" leave the mechanism ambiguous; (2) the design never names the lint invocation that counts as done, so the acceptance surface (repo-wide lint where the friction occurred vs targeted single-file lint) is under-specified.


## Design review context (turn 1 — architectural fit)

- Root cause is not a missing require/dep: the test ns already requires `[org.httpkit.client :as http-client]` and http-kit 2.8.0 is in root `deps.edn` and `extensions/dev-http/deps.edn`. `org.httpkit.client/get`/`post` are generated by the private `defreq` macro in http-kit 2.8.0's client.clj, so clj-kondo's jar analysis never registers them. Reproduced locally: `clojure -M:lint --lint extensions/dev-http/test/extensions/dev_http_test.clj` → warnings at line 572 (`http-client/get`) and 737 (`http-client/post`).
- Established fix mechanism to mirror: `.clj-kondo/imports/http-kit/http-kit/config.edn` declares the server-side `with-channel` hook, with the hook impl at `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj`. A client-side hook/export for the `defreq`-generated vars belongs in the same import config.

## Addressing the design-steps (notes for implementation slice)

- `org.httpkit.client` is used in exactly one repo file (rg over components/ + extensions/): `extensions/dev-http/test/extensions/dev_http_test.clj`, and only `get`/`post` of the `defreq`-generated verb set; `request` is a plain defn and already analyzes. Registration is ns-scoped to `org.httpkit.client`, so other namespaces/aliases are unaffected.
- Version pins: http-kit 2.8.0 in root `deps.edn` + `extensions/dev-http/deps.edn` (only 2.8.0 is on the classpath; 2.5.3/2.8.0-beta3/2.9.0-beta1 also in ~/.m2 but unused); clj-kondo 2025.09.19 in the `:lint` alias. Re-verify the hook against both on any version bump.
- Forbidden path: root `.clj-kondo/config.edn` `:linters :unresolved-symbol :exclude` currently holds only `malli.core/=>`; do not add http-client vars there (global suppression).
- Verification surfaces: `bb lint` → `clojure -M:lint` (deps.edn `:lint` alias) lints bb.edn, deps.edn, .lsp/config.edn, .psi/startup-prompts.edn, bases/, components/, extensions/, spec/, tests.edn, extensions/tests.edn — the agent-facing surface where the friction occurred. The targeted single-file command in design-step 3 is the dev loop; both must stay green with no new warnings elsewhere.
- Not user-facing (tooling/lint config): no CHANGELOG entry per AGENTS.md user_visible rule.
- Task 251 (file-length lint limit) is a sibling tooling-friction task from the same analyzer (239); its bb.edn change is disjoint from this `.clj-kondo` change — no coupling.

## Design follow-up complete (2026-08-15, batch = arch @45130198e + ambiguity @60d5c3d06 + inconsistency @4e17c1ca7)

- All 5 design-steps resolved with updates to design.md (rewritten: root-cause
  re-diagnosis, committed mechanism, constraints, acceptance AC1/AC2, evidence).
  Scope unchanged; no SCOPE_QUESTION items present.
- This pass was design documentation only: design.md now commits to the
  per-library import mechanism (`.clj-kondo/imports/http-kit/http-kit/config.edn`),
  but the hook/registration code itself is NOT written here — it belongs to the
  implementation slice after the design→plan gate (no plan.md/steps.md yet).

## Facts discovered for the implementation slice

- `clojure -M:lint --lint <file>` does NOT narrow the lint: the `:lint` alias
  `:main-opts` already carries `--lint` + the full path set, and clj-kondo
  accumulates multiple `--lint` args. Verified: both invocations report the same
  two warnings (plus unrelated infos from other files). The effective
  verification surface is the repo-wide `bb lint` ≡ `clojure -M:lint`; design.md
  AC1 pins that as the proof surface (the step-3 "targeted" command adds no
  narrowing over it).
- `defreq` specifics in http-kit 2.8.0 `org/httpkit/client.clj`: `^:private
  defmacro defreq` at line 378; generates get/delete/head/post/put/options/patch/
  propfind/proppatch/lock/unlock/report/acl/copy/move (lines 388-403); `request`
  is a plain `defn` at line 231 (already resolves). Registering the full verb set
  is uniform and acceptable; only `get`/`post` are used in-repo.
- clj-kondo 2025.09.19 supports `:namespaces {org.httpkit.client {get
  {:defined-by clojure.core/defn} ...}}` in config.edn (deep-merged per ns) as a
  var-registration facility — likely simpler than a hook for `defn`-generating
  macros; either is confined to the http-kit import dir.
- Root `.clj-kondo/config.edn` `:unresolved-symbol :exclude` is exactly
  `[(malli.core/=>)]`; AC2 guards that it stays that way.
- plan ambiguity review added 2 new design steps

## Plan review context (turn 1 — ambiguity)

- Two new actionable ambiguities filed in design-steps.md (6-7): (6) plan.md decision 1's `:lint-as` is a third facility outside design.md Mechanism's closed "either/or" (hook | `:namespaces`), and the `:namespaces` alternative provably does not exist in clj-kondo 2025.09.19 — verified in the jar (config.clj/config.types.edn: no top-level `:namespaces` var-registration key; `:defined-by` is analysis output, not config input) — so design.md should name the chosen facility to avoid mechanism-drift rejection at the design gate. (7) AC2's literal confinement ("change confined to `.clj-kondo/imports/http-kit/http-kit/`") vs the plan's `.gitignore` enabling edit listed in slice 4's intended change set — amend AC2 or the slice-4 gate and the acceptance text disagree.
- Cache discrepancy (for the later inconsistency turn): `.clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json` records `~:filename .../http-kit-2.9.0-beta1.jar:org/httpkit/client.clj` — the stale cache was written from 2.9.0-beta1, not the pinned 2.8.0 (contradicts the earlier "2.9.0-beta1 unused" note). Not blocking: slice 2's rebuild lints the 2.8.0 jar explicitly, rewriting the cache from the pinned version; relevant to R4 (version-bump re-verification).
- Slice-2 `--dependencies` claim verified: clj-kondo 2025.09.19 `--help` documents `--dependencies` as "don't report any findings. Useful for populating cache while linting dependencies" — invocation-wide, so the combined alias-paths + jar command prints no findings; the plan's "findings suppressed" is accurate.
- implementation.md's earlier design-review note claiming `:namespaces {ns {var {:defined-by ...}}}` is "supported in config.edn" is superseded by plan.md decision 1 (verified above); the later inconsistency turn need not re-litigate it.
- inconsistency review added 1 new design step

## Plan review context (turn 2 — inconsistency)

- Filed design-step 8 (actionable): stale-cache provenance contradiction — implementation.md's "2.9.0-beta1 ... unused" note vs the stale cache plan.md R3 cites as the warning source (records `http-kit-2.9.0-beta1.jar`, built 2026-06-29). Remedy: record actual provenance in design.md Context / plan.md R3 and add a slice-2 provenance check (cache is ns-keyed, not version-keyed; grep `~:filename` for 2.8.0 after rebuild) — closes the R4 version-mismatch gap.
- Not re-filed (consumed by ambiguity turn): facility mismatch (`:lint-as` vs design.md Mechanism "or", design-steps 6) and AC2-vs-`.gitignore` (design-steps 7).
- Verified consistent this pass: scratch test proves plan.md decision 1 end-to-end (imports-config lint-as → jar analysis registers `~$get`/`~$post` in cache → cache-driven lint clean → bogus var still warns); local import dir byte-identical to jar's clj-kondo.exports; gitignore negation pattern behaves as steps.md slice 1 expects (http-kit unignored, malli sibling still ignored — scratch git repo); test lines 572/737 are get/post; CI "Lint" step = `bb lint`; task 251 design.md has no `.gitignore`/`.clj-kondo` refs (R6 disjointness holds); root config `:lint-as` mirror convention exists (malli/promesa) — plan's no-mirror choice is justified since defreq is never invoked in-repo.

## Plan review → design-steps amendment slice (turn 3)

## Plan review → design-steps amendment slice (design-step 9 — CI-enforceability gap)

CI facts verified this pass (`.github/workflows/ci.yml`):
- "Cache Maven + Clojure deps" caches `~/.m2/repository` (keyed on deps.edn/bb.edn) → the http-kit 2.8.0 jar is present in CI at the standard m2 path. `.clj-kondo/.cache` is **not** cached/persisted → option (a) must populate the cache fresh each run, before the "Lint" step.
- Option (a) concrete shape (AC2-compatible — a workflow step is not lint config; the confined set stays the import dir): add before "Lint": `clojure -M:lint --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar --dependencies` — identical to steps.md slice 2, writes workspace `.clj-kondo/.cache`, findings suppressed; then `bb lint` consumes it. Use the pinned JVM clj-kondo via `clojure -M:lint`, **not** the CI-installed native binary (latest, only `--version`-checked — its analysis could differ from 2025.09.19).
- Option (b) trade-off to record in the amendment: committed config still keeps local dev lint clean and guards future dev-cache regressions, but CI cannot detect a broken registration (config typo, key mismatch); if (b), the earlier "AC1 proof surface is CI-enforced" implementation.md note (design-review context) is superseded — correct or strike it.
- Principle for either option: never degrade to suppression; the negative-control (bogus var) probe stays the proof of analysis-level resolution, and AC2's root-config invariant (`:unresolved-symbol :exclude [(malli.core/=>)]`) is untouched.

## Plan review context (turn 1 re-pass — ambiguity)

- New actionable item: design-step 9 — AC1's executable proof surface is local-cache-dependent. plan.md decision 3 already states CI is trivially clean (no cache, no `--dependencies` → http-kit jar never analyzed). Re-confirmed empirically this pass: `clojure -M:lint --cache false` → `errors: 0, warnings: 0` (the two http-client warnings disappear without the cache). Consequence for later turns/steps: the committed registration (slice 1) can never be exercised by CI; AC1's "repo-wide lint" gate is meaningful only locally after the slice-2 cache rebuild, and implementation.md's design-review note ("AC1 proof surface is CI-enforced") overstates CI's role. The inconsistency turn should treat the "CI-enforced" note vs decision 3's trivially-clean claim as consumed by design-step 9 (do not re-file).
- Not re-filed (consumed): facility mismatch (design-step 6), AC2-vs-`.gitignore` (design-step 7), stale-cache provenance (design-step 8, inconsistency turn). Steps.md remains untouched (read-only this pass); no new steps.md items — the slice-2 provenance grep belongs to the design-steps amendment slice per turn 3.
- Everything else in plan.md/steps.md re-checked: slice order/gates sequential and consistent; decision 1's `:lint-as` primary + `:macroexpand` fallback trigger ("if lint-as misbehaves") is a recorded contingency, not a new ambiguity; slice-3 `warnings: 0` consistent with the 2-warning baseline; gitignore negation verified; no other fresh actionable items.

Design-steps 6-8 resolve by **doc amendment only — no code**: design.md Mechanism (name `:lint-as` as the single chosen facility; drop the `:namespaces` alternative — provably nonexistent in 2025.09.19, so removing it is factual correction, not narrowing), design.md AC2 (explicitly permit the `.gitignore` enabling edit while keeping the lint-config mechanism confined to the import dir), design.md Context + plan.md R3 (record stale-cache provenance: 2.9.0-beta1, not "unused") + steps.md slice 2 (add `~:filename` provenance grep after rebuild).

Principles to hold:
- Amend design.md first, then re-check plan.md/steps.md coherence against the amended text (AGENTS.md coherence) — plan.md/steps.md were written against the pre-amendment design and currently conflict with it (that is the finding).
- Single-facility discipline: after amendment, Mechanism must name exactly one chosen facility; do not reopen a new "or".
- AC2 text and the slice-4 gate list must agree verbatim on the intended change set (`.gitignore` + the two import files); disagreement between acceptance text and gate is what step 7 files.
- Keep plan.md decision 1's empirically verified claims intact (imports-config lint-as → jar analysis registers `get`/`post`; negative control warns); only the design text reconciles to them.
- Cache provenance grep is the R4 guard: cache entries are ns-keyed, not version-keyed — a version-mismatched cache lint is silently clean.

Durable verification recipe (scratch dir `/tmp/ck-scratch` was ephemeral; do not rely on it):
```
clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2025.09.19"}}}' \
  -M -m clj-kondo.main --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar \
  --dependencies --cache-dir <scratch>/cache
# then lint a test ns against the same --cache-dir: get/post resolve, bogus var warns
```

Additional non-task paths (beyond the slice list above): `.gitignore` line 4 (`**/.clj-kondo/imports/` — the exact negation target), `.clj-kondo/.cache/v1/clj/org.httpkit.client.transit.json` (provenance check target), `~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar` (slice-2 lint source; other m2 http-kit versions present: 2.5.3, 2.8.0-beta3, 2.9.0-beta1).

## Plan-review follow-up (steps) — executed 2026-08-15

- Batch = f10e76415 (ambiguity) + 2c16bc0c7 (inconsistency), back-to-back; e52befd39 is the turn-3 notes commit, not a pass.
- Baseline = ffbff6258 (parent of f10e76415, the plan/steps creation commit).
- `git diff ffbff6258..HEAD -- munera/open/252-unresolved-var-lint-warning/steps.md` is **empty** → the batch added zero checklist lines to steps.md (it only touched design-steps.md + implementation.md) → candidate follow-up set = ∅ → nothing executed; all steps.md items predate the batch and were left unchecked per protocol (do not execute pre-batch items).
- The batch's actionable findings are tracked as design-steps.md 6–8 (unchecked, doc-only) — the design-steps amendment slice per turn 3 above; they are design-steps items, not steps.md items, and are out of scope for this steps-follow-up.
- What the next step needs to know: run the design-steps amendment slice *before* any implementation slice — amend design.md (Mechanism → `:lint-as` single facility; AC2 → permit the `.gitignore` enabling edit; Context → 2.9.0-beta1 stale-cache provenance), then plan.md R3, then add the steps.md slice-2 `~:filename` provenance grep; re-check plan.md/steps.md coherence against amended design.md.

## Plan-review follow-up (steps) — executed 2026-08-15 (batch 2)

- Batch = 810ab8c16 (ambiguity re-pass — CI-enforceability gap) + 7feb835de (inconsistency re-pass — no new feedback), back-to-back; 5d4d72448 is the turn-3 notes commit (design-step 9 amendment-slice notes), not a review pass.
- Baseline = 53b11d943 (parent of 810ab8c16 = the previous steps-follow-up completion).
- `git diff 53b11d943..HEAD -- munera/open/252-unresolved-var-lint-warning/steps.md` is **empty** → the batch added zero checklist lines to steps.md (touched only design-steps.md + implementation.md) → candidate follow-up set = ∅ → nothing executed; all steps.md items predate the batch and were left unchecked per protocol (do not execute pre-batch items).
- The batch's actionable findings are tracked as design-steps.md 9 (AC1 CI-enforceability gap, added this batch) plus pre-existing unchecked design-steps 6–8 — a doc-only design-steps amendment slice, out of scope for this steps-follow-up.
- What the next step needs to know: run the design-steps amendment slice *before* any implementation slice — amend design.md (Mechanism → `:lint-as` single facility; AC2 → permit the `.gitignore` enabling edit; Context → 2.9.0-beta1 stale-cache provenance; AC1 → decide CI option (a) jar `--dependencies` step vs (b) local-only scope, correcting the "CI-enforced" note), then plan.md R3 + decision 3, then add the steps.md slice-2 `~:filename` provenance grep; then re-check plan.md/steps.md coherence against amended design.md. CI facts for option (a): `.clj-kondo/.cache` not cached/persisted; m2 cache present; use pinned JVM clj-kondo via `clojure -M:lint`, not the CI native binary.
- ambiguity review (plan-review session, 2026-08-15): no new actionable ambiguity feedback — re-verified plan decision 1 end-to-end in a fresh scratch (imports-config `:lint-as {org.httpkit.client/defreq clojure.core/def}` → jar `--dependencies` analysis registers `~$get`/`~$post`/`~$request` + full verb set in cache → cache-driven lint of a get/post probe clean → bogus var still warns; jar `defreq` @378, `(defreq get)` @388, `(defreq post)` @391). Non-obvious discovery for later turns: with NO cache (decision 3's CI case) the http-kit ns is never analyzed, so both the two warnings AND the steps.md slice-3 negative control are silent — the negative control's validity depends on the slice-2 rebuild having run; CI can exercise neither the fix nor the negative control (reinforces design-step 9 option (a)/(b); do not re-file). All other candidates consumed by design-steps 6-9 — no new design-steps added.
- inconsistency review (plan-review session, 2026-08-15): no new actionable inconsistency feedback — targeted re-reads confirmed remaining cross-file claims (jar `request` defn @231 / `defreq` calls @388-402; promesa/malli imports configs carry the `:lint-as` precedent plan decision 1 relies on; cache mtime Jun 29 2026 = plan R3's "built 2026-06-29"; http-kit 2.8.0 pinned in root deps.edn:14 + extensions/dev-http/deps.edn:4). All cross-file candidates already consumed by unchecked design-steps 6-9 — no new design-steps added.
## Plan-review session (2026-08-15, turns 1-2) → design-steps amendment slice (turn-3 notes)

- This session added NO new design-steps (ambiguity + inconsistency turns both no-new-feedback). Amendment target set unchanged: pre-existing unchecked design-steps 6-9. Per-turn notes above record each turn's verified facts; do not re-derive.
- Design-step 9 amendment nuance (not in design.md/plan.md/steps.md): the negative-control (analysis-level proof) is local-only under BOTH CI options — option (a)'s CI jar `--dependencies` step proves fix presence (warnings absent) but cannot prove non-suppression (bogus-var probe is a temporary source edit, inherently local); option (b) leaves fix + control both local. Draft AC1's amendment so "analysis-level, not suppression" is attributed to the local negative control, never to CI, whichever option is chosen.
- When amending steps.md in the same slice: make the slice-3 negative-control precondition explicit — it must run AFTER the slice-2 rebuild, else it silently passes (no-cache ⇒ http-kit ns never analyzed ⇒ bogus var un-flagged) and proves nothing. Currently only implicit via checklist order.
- Principles already recorded in the earlier "Principles to hold" entry (amend design.md first, then plan.md/steps.md; single-facility discipline; AC2 text ↔ slice-4 gate verbatim agreement) still govern; this session adds no new principles.
- Project paths: no new ones beyond "Additional non-task paths" above (.gitignore, import config.edn, cache transit, m2 jar, promesa/malli configs, bb.edn/deps.edn/ci.yml).

## Implementation slice — executed 2026-08-15 (slices 0-4 complete)

- **Facility (design-step 6):** `.clj-kondo/imports/http-kit/http-kit/config.edn`
  now carries `:lint-as {org.httpkit.client/defreq clojure.core/def}` alongside the
  existing server-side `:hooks {:analyze-call {org.httpkit.server/with-channel …}}`.
  Confirmed end-to-end in-repo: jar `--dependencies` analysis registers the full
  `defreq` verb set (`~$get`/`~$post`/`~$request` + others) in the ns cache.
- **Gitignore (design-step 7):** `.gitignore` replaces `**/.clj-kondo/imports/` with
  `**/.clj-kondo/imports/*` + `!.clj-kondo/imports/http-kit/` +
  `!.clj-kondo/imports/http-kit/**`, making the http-kit import dir tracked
  (config.edn + `httpkit/with_channel.clj`); sibling imports (e.g. metosin/malli)
  stay ignored — verified via `git check-ignore -v`.
- **Cache rebuild (design-step 8):** regenerated from the pinned 2.8.0 jar via
  `clojure -M:lint --lint ~/.m2/repository/http-kit/http-kit/2.8.0/http-kit-2.8.0.jar
  --dependencies` (findings suppressed; mtime refreshed; `get`/`post`/`request` now in
  cache). Note: the transit cache stores the internal path `org/httpkit/client.clj`,
  not the jar path — provenance is guaranteed by the explicit 2.8.0 rebuild command.
- **AC1 (design-step 9; lint surface itself local/cache-dependent — regression surface
  CI-enforceable via committed `^:integration` tests, corrected slice 19, see the
  Design-steps 6-9 note below):** `bb lint` and `clojure -M:lint` both report
  `errors: 0, warnings: 0`; both pre-fix warnings (572 `get`, 737 `post`) gone, no new
  warnings anywhere. Negative control passed: a temporary probe `(defn- bogus []
  @(http-client/definitely-not-a-var))` is flagged `Unresolved var` at line 572,
  proving analysis-level resolution rather than suppression; probe removed, lint clean
  again.
- **AC2:** root `.clj-kondo/config.edn` has no diff; `:unresolved-symbol :exclude`
  remains exactly `[(malli.core/=>)]`. Change set = `.gitignore` +
  `.clj-kondo/imports/http-kit/http-kit/config.edn` +
  `.clj-kondo/imports/http-kit/http-kit/httpkit/with_channel.clj`.
- **Design-steps 6-9** resolved by doc amendment: design.md Mechanism names `:lint-as`
  (dropping the nonexistent `:namespaces` alternative), AC1 scoped local-only (option b —
  no CI workflow change; committed config + local verification accepted) — **corrected
  (slice 19, 2026-08-15)**: design-step 9 option (a) was realized via a test vehicle —
  the committed `^:integration` tests (`http-kit-defreq-analysis-level-resolution-test`
  + `gitignore-http-kit-tracking-ground-truth-test` +
  `with-channel-hook-semantics-guard-test`, slices 7-18, run via
  `bb clojure:test:integration` in CI) make the regression surface CI-enforceable;
  local-only scope remains only for the cache-dependent `bb lint` surface itself. AC2 explicitly
  permits the `.gitignore` enabling edit, Context records the 2.9.0-beta1 stale-cache
  provenance. plan.md R3 updated accordingly.
- **No CHANGELOG entry** (tooling/lint config, not user-facing per AGENTS.md).
- Sibling task 251's `munera/open/251-…/` untracked dir left untouched (no coupling).

- implementation review 2026-08-15: added 3 steps to be addressed

## Implementation-review follow-ups — addressed 3 review steps (2026-08-15)

- design-step 8 provenance-grep closure recorded (design.md Context guard + design-steps.md item 8 closure note): cache records internal path only, no jar path → guard = pinned-jar rebuild command + verb-set grep proxy
- with_channel.clj drift recorded as **intentional** (follow-up item's "or" branch): re-copying from the 2.8.0 jar `clj-kondo.exports` is byte-identical at copy time (cmp verified), but the `cljfmt-fix` pre-commit hook unconditionally reformats `.clj` files to repo style (2-space continuation) — the committed copy therefore differs from the jar's 3-space export in indentation only, exactly as at the original slice-4 commit (6c56d1b32). Byte-fidelity cannot survive the repo's own commit hygiene hook; plan.md decision 2's "identical, verified" holds for the local pre-commit copy, and R5's pinned-2.8.0 stability holds across `--copy-configs` regenerations (same jar → same export → same indentation-only diff vs the cljfmt-formatted committed copy)
- pre-commit surface added to design.md AC1 exercise-capability inventory (verified: clean with and without lint-as; native binary, --cache false, no --dependencies)

- implementation review 2026-08-15: added 2 steps to be addressed

- addressed 2 review steps (slice-2 skip-marker hardening + slice-3 negative-control precondition); hardened rebuild validated end-to-end (rm skip+transit → rebuild → verbs present → `bb lint` errors: 0, warnings: 0)

- implementation review 2026-08-15: no new actionable follow-ups
- task-test-review 2026-08-15: added 2 steps to be addressed

- addressed 2 task-test-review steps (slice 7): committed regression tests
  - item 1 (config-invariant test) — placement decision: `components/shared-config/test/psi/shared_config/lint_config_test.clj` (no component owns lint config; shared-config closest semantic home; its test dir already in `:unit` suite → no tests.edn change; assertion code outside `.clj-kondo/imports/` → AC2 confinement). Pure EDN reads of both config.edn files (no jar analysis, no cache → runs anywhere/CI): asserts import `:lint-as {org.httpkit.client/defreq clojure.core/def}` retained + `:hooks :analyze-call` preserved, and root `:unresolved-symbol :exclude` exactly `[(malli.core/=>)]`. Verified `bb test --focus psi.shared-config.lint-config-test` → 2 tests / 3 assertions pass; `bb lint` errors: 0, warnings: 0
  - item 2 (analysis-level proof) — decision: ACCEPT, as `^:integration` test `http-kit-defreq-analysis-level-resolution-test` in the same ns. Runs pinned JVM clj-kondo 2025.09.19 via `clojure -Sdeps` (same analyzer as `:lint` gate): jar `--dependencies --cache-dir <tmp>` populates hermetic temp cache → probe ns lint against that cache resolves get/post and flags `definitely-not-a-var`; skips (passing) when 2.8.0 jar absent from m2. `^:integration` → skipped by `bb test` (unit/extensions `:skip-meta`), runs in `bb clojure:test:integration` (CI runs both); hermetic `--cache-dir` never touches repo `.clj-kondo/.cache`. Verified integration run passes. Realizes design-step 9 option (a) via a test vehicle
  - gotcha: Clojure compiler resolves `org.httpkit.client/defreq` as a class in unquoted map literals (ClassNotFoundException) → expected maps must be quoted (`'{org.httpkit.client/defreq clojure.core/def}`); probe file must be written with a single `str` (spit takes one content arg)

- task-test-review 2026-08-15: added 4 steps to be addressed
- addressed 4 task-test-review steps (slice 8): hook exact-match assertion (quoted map, unit-suite verified), root `:lint-as` no-mirror guard (`org.httpkit.client/defreq` ∉ keys), subprocess hardening (`clojure`-absent skip + ProcessBuilder 120s timeout replacing timeout-less shell/sh), clj-kondo version derived from deps.edn `:aliases :lint :extra-deps` (drift fails loudly). Verified: `bb test --focus psi.shared-config.lint-config-test` → 3 tests / 6 assertions pass; integration test isolated → 1 test / 6 assertions pass; `clojure -M:lint` errors: 0, warnings: 0; cljfmt clean
- task-test-review 2026-08-15: added 4 steps to be addressed
- addressed 4 task-test-review steps (slice 9): http-kit jar pin derived from deps.edn `[:deps 'http-kit/http-kit :mvn/version]` (2.8.0) + `http-kit-pin-sourced-from-deps-edn-test` unit guard; AC2 root-config guard widened to a full EDN walk (`http-client-entries` — no `org.httpkit.client` symbol/keyword anywhere in root config, covering `:lint-as`/`:hooks`/`:namespaces`/…); `^:integration` skip made visible via `(println "SKIP task-252 analysis-level proof:" reason)` before the truthy assert; `repo-root` injectable/nullable — walk up from user.dir to the deps.edn+bb.edn pair (components/extensions carry their own deps.edn, so plain deps.edn is not a root marker — verified failing case), overridable via `psi.lint-config-test.repo-root`, clear ex-info when unfound. Verified: unit focused → 4 tests / 9 assertions; integration suite → 31 tests / 157 assertions (no SKIP line — proof ran); `bb lint` errors: 0, warnings: 0; cljfmt clean; nested-CWD load + property-override load both resolve the repo root and the 2.8.0 jar

- task-test-review 2026-08-15: added 2 steps to be addressed
- addressed 2 task-test-review steps (slice 10): real-file AC1 coverage + .gitignore tracking guard
  - item 1 (real-file AC1 surface in `^:integration` proof): `http-kit-defreq-analysis-level-resolution-test` now lints `extensions/dev-http/test/extensions/dev_http_test.clj` (AC1's literal acceptance surface, lines 572/737) against the registration cache → exit 0, no get/post unresolved; and builds a discriminating no-reg cache from the same jar with `--config-dir <empty-tmp-dir>` (clj-kondo 2025.09.19 NPEs `config_dir is null` without it) → transit carries `~$request` but not `~$get`/`~$post` (slice-2 verb-set proxy) → real-file lint against it reports both warnings + `errors: 0, warnings: 2` (exact baseline shape), proving the clean arm is registration-driven, not trivially clean. Recipe validated 2026-08-15 (both arms), then committed; focused integration run → 1 test / 17 assertions pass
  - item 2 (.gitignore tracking guard): `gitignore-http-kit-import-tracking-test` added — reads `.gitignore` as text (no subprocess, runs anywhere), asserts the three negation lines (`**/.clj-kondo/imports/*`, `!.clj-kondo/imports/http-kit/`, `!.clj-kondo/imports/http-kit/**`) verbatim, so removing them (restoring `**/.clj-kondo/imports/`) fails loudly in `:unit` instead of silently dropping the registration out of future commits
  - verified: `bb test --focus psi.shared-config.lint-config-test` → 5 tests / 12 assertions; integration focused → 1 test / 17 assertions; `bb lint` errors: 0, warnings: 0; `bb fmt:check` all formatted
- task-test-review 2026-08-15: added 3 steps to be addressed
- addressed 3 review steps (slice 11): with-channel hook impl file guard (`with-channel-hook-impl-guard-test` — file exists + `(ns httpkit.with-channel)` + `(defn with-channel …)` match config.edn `:analyze-call` ref), extension http-kit pin guard (`http-kit-pin-sourced-from-deps-edn-test` now asserts `extensions/dev-http/deps.edn` pin == root-derived `http-kit-version`), clojure CLI binary injectable (`psi.lint-config-test.clojure-bin` property override mirroring repo-root + ProcessBuilder uses the same derived `clojure-bin` that feeds the skip guard + defensive nil guard). Verified: unit focused 6 tests / 18 assertions; integration 31 / 168 (no SKIP — proof ran); override-with-real-binary integration run passes; override/nil-guard behavior verified via ns-load print + alter-var-root (note: `ns-unmap`+`intern` creates a NEW var — the compiled fn keeps the old one, so only alter-var-root validates the nil path); `bb lint` errors: 0, warnings: 0; `bb fmt:check` clean. Pre-existing unrelated full-suite failure: `delegate-review-task-implementation-completes-with-nullable-local-model-test` fails on base too (live-model environment; verified with change stashed); `version.edn` mutated by a packaging-smoke test during full-suite runs (restored).

- task-test-review 2026-08-15: added 3 steps to be addressed
- addressed 3 review steps (slice 12): http-kit jar path injectable (`psi.lint-config-test.http-kit-jar` property override, mirror of repo-root/clojure-bin; skip guard unchanged — verified: nonexistent override skips, real override runs full 17-assertion proof); .gitignore negation ORDER asserted (ignore-all line 4 precedes negations 5-6 — last-match-wins; 6 unit tests / 23 assertions); integration temp dir cleaned via `delete-recursively!` in try/finally (no /tmp/ck252* leak from the run; 9 pre-existing leaks from before the fix remain). Integration suite 31 tests / 168 assertions pass, no SKIP; `bb lint` errors: 0, warnings: 0; cljfmt clean.
- task-test-review 2026-08-15: added 3 steps to be addressed
- addressed 3 review steps (slice 13): lint-alias acceptance-surface guard (`lint-alias-lints-extensions-test` — deps.edn `[:aliases :lint :main-opts]` contains "extensions" + "bb.edn"; 7 unit tests / 26 assertions), git check-ignore ground-truth guard (`^:integration` `gitignore-http-kit-tracking-ground-truth-test` — `git check-ignore -v` from repo root: http-kit config.edn not ignored / exit 1, malli sibling ignored / exit 0 with `.gitignore:` match; git-absent → visible SKIP), real-file-arm claim corrected in the integration testing string + the identical slice-10 steps.md note (delete is in the defreq-registered set — `~$delete` verified in cache transit — so an added delete call resolves and does NOT fail; the arm guards require/alias changes and out-of-set vars). Integration focused → 2 tests / 20 assertions pass, no SKIP; `bb lint` errors: 0, warnings: 0; cljfmt clean.
- task-test-review 2026-08-15: added 4 steps to be addressed

- addressed 4 review steps (slice 14): git-bin injectable+guard/exec-agreed (property override, same resolved value in guard+exec, shared bounded run-bounded runner), clj-kondo jar nullable+injectable (property override + derived m2 path + skip-guard existence check; override skips proof — 171→155 assertions), bb.edn lint wrapper guard (exact `(shell "clojure -M:lint")`; task key is symbol `lint`), tests.edn suite-wiring guard (`#kaocha/v1` tag reader; shared-config path in :unit/:integration test-paths, :integration focus-meta [:integration], :unit skip-meta [:integration]). Verified: unit focused 9 tests / 34 assertions; integration suite 32 tests / 171 assertions, no SKIP (both proofs ran); clj-kondo-jar nonexistent override → 155 (proof skipped, 17→1 assertions); git-bin real override → 171; `bb lint` errors: 0, warnings: 0; cljfmt clean

- task-test-review 2026-08-15: added 2 steps to be addressed

- task-test-review 2026-08-15: added 2 steps to be addressed
- addressed 2 review steps (slice 15): (1) visible-skip restored — tests.edn top-level `:capture-output? false` (kaocha honors it root-only) + two additional discoveries required for the primary scry runner path: scry's in-process adapter binds *out* to a discarding writer around api/run (scry/kaocha.clj), so the skip reasons now write to System/out via `report-skip!` (reaches runner stdout on scry + fallback paths); git ground-truth guard gained a nonexistent-binary skip arm (mirror of jar arms). Verified: forced-skip scry run prints both SKIP lines; integration 32/171 exit 0, no SKIP (proofs ran); unit 2693 pass (1 pre-existing environmental deepseek-model failure, fails identically with capture on); extensions 364 pass (1 pre-existing scry "unknown" artifact); scry .scry-results still recorded; guard: tests-edn-suite-wiring-test asserts top-level `:capture-output?` false. (2) `lint-alias-lints-extensions-test` now asserts `:main-opts` contains none of `--cache`/`--config`/`--config-dir`/`--dependencies`. Unit 9 tests / 39 assertions; `bb lint` errors: 0, warnings: 0; `bb fmt:check` clean.

- task-test-review 2026-08-15: added 2 steps to be addressed
- addressed 2 review steps (slice 16): (1) clj-kondo jar guard/exec agreement
  — `clj-kondo-deps` now emits `:mvn/local-repo` derived from the guarded
  `clj-kondo-jar` path (suffix-strip; `psi.lint-config-test.clj-kondo-local-repo`
  property escape hatch; non-m2-layout jar path without override → loud ex-info,
  no silent wrong-artifact/download); the `^:integration` proof gained a `-Spath`
  arm asserting the resolved classpath contains the guarded jar. (2) http-kit
  import files TRACKED-in-index guard — `git ls-files --error-unmatch` on
  config.edn + with_channel.clj added to the git ground-truth test (a
  `git rm --cached` now fails loudly). Verified: unit 9 tests / 39 assertions;
  integration 32 tests / 174 assertions (was 171 — +2 -Spath, +1 ls-files), no
  SKIP; custom-path jar override → 174 no SKIP (-Spath arm would fail on
  disagreement); local-repo property override → 174; non-m2-layout jar →
  clear ex-info error; `bb lint` errors: 0, warnings: 0; `bb fmt:check` clean.

- implementation review 2026-08-15: added 2 steps to be addressed

- addressed 2 review steps (slice 17): (1) design.md AC1 CI-scope framing
  amended — dropped "local-only" header, scoped cache-dependence to the
  `bb lint`/pre-commit surfaces, corrected "never run in CI"/"cannot exercise"
  with the committed `^:integration` test vehicle as the CI-enforceable
  regression surface; (2) plan.md decision 3 + R3 reconciled — same
  test-vehicle option (a) recorded while keeping the `bb lint`-surface claim.

- implementation review 2026-08-15: added 2 steps to be addressed

- addressed 2 review steps (slice 18): design.md "exactly one repo file" claim
  scoped to call sites/runtime usage (lint_config_test references
  org.httpkit.client symbols as config-assertion data only); new
  ^:integration with-channel-hook-semantics-guard-test compares the tracked
  hook impl against the pinned 2.8.0 jar's clj-kondo.exports export
  whitespace-normalized (slice-5 cljfmt indentation drift stays green; any
  semantic change fails loudly; jar-absent → visible SKIP). Verified:
  integration 33 tests / 177 assertions (was 32/174), no SKIP; unit 9 / 39;
  negative no-op-rewrite check discriminates; bb lint errors: 0, warnings: 0;
  bb fmt:check clean
- implementation review 2026-08-15: added 2 steps to be addressed

- addressed 2 review steps (slice 19): (1) implementation.md's two stale AC1
  CI-framing records corrected in place — the architectural-fit note's "AC1 proof
  surface is CI-enforced" claim struck/corrected (CI `bb lint` is trivially clean —
  no cache, no `--dependencies` → jar never analyzed; the CI-enforceable surface is
  the committed `^:integration` test vehicle), and the "Implementation slice —
  executed" record's "AC1 scoped local-only (option b …)" corrected to the realized
  option (a)-via-test-vehicle (three named `^:integration` tests, run via
  `bb clojure:test:integration` in CI), with local-only scope retained only for the
  cache-dependent `bb lint` surface itself; (2) design.md AC1's "slices 7-16" range /
  two-test enumeration re-scoped to "slices 7-18; non-exhaustive" with
  `with-channel-hook-semantics-guard-test` named, mirrored in design.md Context and
  plan.md decision 3 / R3. Doc-only reconciliation; no code/test changes, no lint
  surface touched.
- implementation review 2026-08-15: added 3 steps to be addressed
- addressed 3 review steps (slice 20): (1) semantics guard's whitespace blind spot closed — `normalize-whitespace` (collapsed whitespace INSIDE string literals, so a literal-spacing change passed) replaced by `parse-forms` (read-string both sides, vector-wrapped — indentation/whitespace vanishes by construction, string-literal contents survive exactly); verified indentation-insensitive / literal-spacing-sensitive / token-change-sensitive. (2) missing-jar-entry NPE fixed — the equality is nil-guarded (`when (some? jar-export)` after the outer `some?` assertion), so a jar without the clj-kondo.exports entry fails as a plain assertion FAIL, not an ERROR (verified with a fabricated entry-less jar via `psi.lint-config-test.http-kit-jar`). (3) `run-bounded` timeout ex-info now carries partial output — destroy process first (closes pipes so drain futures complete), then bounded 500 ms deref of `@out-f`/`@err-f` into `{:cmd … :out … :err …}` (verified: `echo partial-output-here; sleep 30` → ex-data `:out "partial-output-here\n"`). Integration 33 tests / 176 assertions (was 177 — redundant inner `some?` assert dropped), no SKIP; unit 9 / 39; `bb lint` errors: 0, warnings: 0; `bb fmt:check` clean.
- implementation review 2026-08-15: added 2 steps to be addressed
- addressed 2 review steps (slice 21): (1) run-bounded success-path + finally
  stream drains bounded (bounded 3-arg deref, loud ex-info with :unavailable
  markers when a pipe-holding descendant keeps the streams open past the
  bound — previously the success branch's unbounded @out-f/@err-f hung the
  suite indefinitely on the grandchild scenario); verified: pipe-holding
  grandchild (`sh -c "sleep 300 & echo done"`) fails loudly in ~2s (no hang),
  timeout path still captures partial output, normal path unchanged. (2)
  *read-eval* false bound in both read-string compare sites (parse-forms +
  with-channel-hook-impl-guard-test) — `#=` throws instead of evaluating; +
  `:read-cond :preserve` (over the follow-up's `:allow`: :allow drops
  non-platform branches from the structural compare, a guard blind spot;
  :preserve compares all branches). Verified: `#=(+ 1 2)` throws
  "EvalReader not allowed"; `#?(:clj 1 :cljs 2)` parses structurally.
  Focused unit+integration run 12 tests / 64 assertions; full integration
  suite 33 tests / 176 assertions, no SKIP; `bb lint` errors: 0, warnings: 0;
  `bb fmt:check` clean.

- implementation review 2026-08-15: added 2 steps to be addressed
- addressed 1 review step (slice 23, commit-check fix): `bb
  commit-check:file-lengths` failed at the slice-21 committed state —
  `components/shared-config/test/psi/shared_config/lint_config_test.clj` was
  804 lines, over the gate's 800-line default for src/test files under
  components/ (the pre-commit hooks and ci.yml never run the length check, so
  the failure surfaced only at the committed state). Fixed by splitting into
  three logically consistent files, per the explicit "break into multiple
  logically consistent files; no forwarding vars" instruction:
  `lint_config_test.clj` (9 unit invariants, 244 lines), the new
  `lint_config_test_support.clj` (shared fixtures — repo-root, read-edn, pins,
  jar paths, run-bounded, clj-kondo-main, report-skip!, parse-forms,
  delete-recursively! — 375 lines; ns ends in -support, so kaocha's .*-test$
  ns-pattern never runs it), and the new `lint_config_integration_test.clj`
  (3 ^:integration proofs, 246 lines). No forwarding vars: every shared fixture
  is DEFINED once in the support ns and :refer'd from the two test namespaces;
  the eight cross-ns fixtures changed defn- → defn (they were private only
  because they were file-local). tests.edn comment updated to name the new
  integration ns. All 12 tests preserved and green (unit 9/39 + integration
  3/25, 64 assertions), full :unit suite 2694 tests with only the 3 pre-existing
  deepseek model-config failures (unrelated — HEAD 27bc10378), `bb lint`
  errors: 0 warnings: 0, `bb fmt:check` clean, `bb commit-check:file-lengths`
  exit 0. Sibling 251 not double-implemented: it remains design-only (limit-raise
  policy question, separate human-reviewed decision); the second slice-22 review
  step (reuse psi.test-support.repo-root) left OPEN — deliberate divergence: the
  local copy carries the deps.edn+bb.edn root marker AND the
  psi.lint-config-test.repo-root property override (slice-9 injectability), a
  contract the shared helper (doc/custom-providers.md marker, no override) does
  not provide; extending the shared ns is a separate refactor outside this
  commit-check fix's minimal-change scope.
- addressed 1 review step (slice 22 item 2, repo-root reuse — REUSE branch,
  supersedes the slice-23 deliberate-divergence note): extended the shared
  `psi.test-support.repo-root` helper (bases/main/test/psi/test_support/repo_root.clj)
  with three backward-compatible opts — `:markers` (default `[["doc"
  "custom-providers.md"]]`, the no-arg call is byte-identical behavior),
  `:prop` (system property overriding the walk — injectability per the skill
  infra-dep criterion), `:required?` (fail-loud ex-info when the walk exhausts
  without all markers, instead of silently returning the fs root); the
  component-local `find-repo-root`/`repo-root` copy in
  `lint_config_test_support.clj` is DELETED, `repo-root` now delegating to the
  shared helper with the deps.edn+bb.edn marker set + the
  `psi.lint-config-test.repo-root` override + `:required?` — the override/root
  logic lands in one place for all consumers (applies to the support ns AND the
  current file, per the follow-up note). Also fixed a latent walk bug the
  extension surfaced: the old terminal `(= dir (.getParentFile dir))` never
  triggers on macOS (parent of `/` is nil), so a never-found walk recursed into
  nil — the new loop returns the fs root at the nil-parent boundary and lets
  `:required?` throw there. Verified: unit 9/39, integration 33/176 (both
  proofs ran, no SKIP), ai user-models-test 20/139 + agent-session
  workflow-async-path-test 9/53 (the two existing shared-helper consumers,
  unchanged contract), nested-CWD + property-override + fail-loud exercised via
  clojure -e; `bb lint` errors: 0 warnings: 0; `bb fmt:check` clean;
  `bb commit-check:file-lengths` exit 0 (slice-23 split still under the gate).

- implementation review 2026-08-15: added 2 steps to be addressed

- addressed 2 review steps (slice 24): impl-guard slurp-before-exists ERROR → clean FAIL via `when (.exists …)` guard after the exists assertion (mirror of slice-20 jar-entry nil-guard; verified 1 passed/1 failed/0 errored on deleted impl, no ERROR); gitignore order assertion now uses the LAST ignore-all occurrence (last-index-of) so a duplicate ignore-all below the negations fails the unit guard (verified 2 failed pre-fix-blind-spot case, 8 passed clean). Deliberately NOT hardened: `http-kit-import-registration-test`'s read-edn whole-file-deletion ERROR — reviewer-flagged lower priority, covered by the ^:integration `git ls-files --error-unmatch` index arm (gitignore-http-kit-tracking-ground-truth-test). Verified: shared-config unit 9/39, integration 33/176 (no SKIP), `bb lint` errors: 0 warnings: 0, `bb fmt:check` clean; full `bb test` failures (2) are pre-existing live delegate tests (reproduced on pristine tree, unrelated namespaces).

- implementation review 2026-08-15: added 2 steps to be addressed

- addressed 2 review steps (slice 25): doc-only reconciliation of post-slice-23-split file references + slice-20 mechanism wording
  - design.md Context: single pre-split `lint_config_test.clj` reference → names the split file set (unit invariants / support fixtures incl. `http-client-entries` EDN-walk predicate / `^:integration` proofs incl. probe ns)
  - design.md AC1 + plan.md decision 3: the three `^:integration` tests now point at `lint_config_integration_test.clj`; AC1's non-exhaustive note disambiguated ("the integration test file's `^:integration` set")
  - design.md AC1 `with-channel-hook-semantics-guard-test` mechanism: "whitespace-normalized" → "parsed-form structural compare" (slice-20 `parse-forms`; literal-spacing changes now fail loudly)
  - no code/test changes; verified `bb lint` errors: 0, warnings: 0; shared-config tests untouched (doc-only)

- implementation review 2026-08-15: added 2 steps to be addressed
- addressed 2 review steps (slice 26): (1) run-bounded drain hardened against
  an exceptionally-completed slurp future — `drain` now catches
  ExecutionException (deref does not distinguish exceptional completion from
  timeout) and returns a `{::drain-error "label: message"}` marker carrying the
  exception message; the success path's failure check uses a `drain-failed?`
  predicate (::unavailable ∨ map) so a read error on a forcibly-killed
  process's stream throws the loud no-hang ex-info (message generalized to
  cover the read-error case) instead of bypassing the designed failure shapes
  with no captured output; the ex-info passes the marker through in :out/:err
  (diagnostic shows WHY the drain failed). Verified: normal path unchanged;
  throwing-slurp future → marker + drain-failed? true; timeout path (2s bound
  vs sleep 300) still kills + captures partial :out. (2) dead typo'd
  `.gitignore` line 3 (`**/.clj-konde/imports.claude/`) DELETED — intent
  confirmed via history: d15150a5c's `.clj-konde/imports` (clj-kondo-imports
  ignore, typo'd from the start — now realized by lines 4-6) was mangled by
  0bf814fd ("exclude .claude/") into `.clj-konde/imports.claude/`; the
  `.claude` intent is realized elsewhere (settings.local.json excluded by the
  user's global gitignore; CLAUDE.md intentionally tracked), and the line
  matches nothing — deletion is behavior-preserving, no new repo policy.
  Verified: shared-config unit 9/39, integration 33/176 (both proofs ran, no
  SKIP), `bb lint` errors: 0 warnings: 0, `bb fmt:check` clean.

- implementation review 2026-08-15: added 3 steps to be addressed
- addressed 3 review steps (slice 27): (1) `parse-forms` reuse in
  `with-channel-hook-impl-guard-test` — the shared fixture is now :refer'd and
  the inline binding/read-string replaced with `(parse-forms (slurp
  impl-file))` (identical semantics — the fixture's body was byte-for-byte the
  inline copy); the unit suite now exercises the same parse-forms the
  ^:integration semantics guard relies on. (2) `delete-recursively!`
  consolidation — new shared `bases/main/test/psi/test_support/fs.clj`
  (`psi.test-support.fs/delete-recursively!`, reachable from every suite via
  the `:test-paths` alias like psi.test-support.repo-root); all SEVEN local
  copies migrated (tui tmux_rehydration, history git_test + git_worktree_test
  incl. the with-null-context macro template, work-on work_on_command_test,
  agent-session test_support [public fixture now delegates — external callers
  query_graph_test/task_artifact_content_resolver_test unchanged], agent-session
  tool_output_integration_test, shared-config lint_config_test_support [delegates,
  mirroring the slice-22 repo-root delegation]). The shared implementation is
  the behavioral superset of the seven copies (nil-safe; String/File via
  io/file; .exists guard; delete-children-first walk returning nil).
  **Caveat recorded**: the work-on extension's STANDALONE deps.edn `:test`
  alias (extensions/work-on/deps.edn) does not include bases/main/test, so
  work_on_command_test's new psi.test-support.fs require resolves only under
  the repo-level test commands (bb clojure:test:extensions → `-M:test-paths`,
  which includes bases/main/test); CI never uses the standalone alias. (3)
  interrupted-drain marker — `drain`'s InterruptedException catch now returns
  the same `{::drain-error "label: interrupted"}` map marker as
  ExecutionException (was the `"<label interrupted>"` STRING that
  `drain-failed?` — ::unavailable ∨ map? — did not match), so the two
  exceptional-drain paths are symmetric under slice-26's designed invariant;
  verified `drain-failed?` old-string → false (the gap), new-map → true
  (closed). Verified: focused unit runs — shared-config 9/39, history 57/169,
  agent-session 16/94, work-on 11/61; integration 33/176 no SKIP (both proofs
  ran); tui harness + shared ns compile; `bb lint` errors: 0 warnings: 0;
  `bb fmt:check` clean; `bb commit-check:file-lengths` exit 0.

- implementation review 2026-08-15: added 2 steps to be addressed
- addressed 2 review steps (slice 28): (1) CI execution chain guard — new
  `ci-execution-chain-guard-test` + private `ci-run-steps` helper in
  lint_config_test.clj: parses ci.yml lines into a step-name → run-command map
  (line-paired like the .gitignore test, no YAML parser/subprocess) and asserts
  the Lint step runs `bb lint` (ci.yml:89) and the Run Clojure integration
  tests step runs `bb clojure:test:integration` (ci.yml:166) — the outer links
  that execute the lint gate and the three ^:integration proofs; then asserts
  bb.edn's clojure:test:integration task structurally
  (`System/exit`-wrapped `(run-scry-kaocha-suite! "integration"
  ["--focus" "integration"])`, suite id + focus preserved) so a dropped/renamed
  CI step or a task drift to another suite/focus fails loudly in `:unit`
  instead of silently disabling the whole CI regression surface. (2) `which-*`
  single-sourcing — private `which-bin` extracted in lint_config_test_support.clj
  (the which → trim → nil-on-nonzero contract defined once; the two
  byte-identical copies removed), with which-clojure-bin/which-git-bin
  delegating (`(which-bin "clojure")` / `(which-bin "git")`); behavior
  unchanged. Verified: unit suite 10 tests / 48 assertions pass (was 9/39);
  integration suite — shared-config proofs all ran, no SKIP (git/clojure/jar
  resolved through the single which-bin); `bb lint` errors: 0 warnings: 0;
  `bb fmt:check` clean; `bb commit-check:file-lengths` exit 0. (Full unit
  suite: 2694 passed / 1 failed — pre-existing environmental
  workflow-delegate-review-step-live-test, unchanged; integration suite: the
  flaky tmux harness :startup-timeout scenario — environmental, varies between
  runs, unrelated to the shared-config change.)

- implementation review 2026-08-15: added 3 steps to be addressed
- addressed 3 review steps (slice 29, ERROR→clean-FAIL class): (1)
  `ci-execution-chain-guard-test` — `(nth call 2)` → `(nth call 2 nil)` so a
  drift dropping the `["--focus" "integration"]` args fails as a plain FAIL,
  not an IndexOutOfBoundsException ERROR (verified: two-element task call →
  1 failed, 0 errored); (2) `gitignore-http-kit-import-tracking-test` — ordering
  assertion `(is (< ignore-idx neg-idx))` → `(is (and neg-idx (< ignore-idx
  neg-idx)))` with a nil-guarded message, so a missing negation line is clean
  FAILs, not FAIL + NPE ERROR (verified: negation removed → 3 failed, 0
  errored); (3) `with-channel-hook-semantics-guard-test` — tracked-side slurp
  moved out of the let binding into an exists assertion + `when (.exists …)`
  compare guard (mirror of slice-24), so a deleted worktree impl (index entry
  intact → ls-files arm stays green) fails as a clean exists FAIL, not a
  FileNotFoundException ERROR (verified: impl moved aside → 1 failed, 0
  errored). All mutations restored after verification. Verified: unit 10/48,
  integration 33/177 no SKIP (proofs ran), `bb lint` errors: 0, warnings: 0,
  `bb fmt:check` clean, `bb commit-check:file-lengths` exit 0.
