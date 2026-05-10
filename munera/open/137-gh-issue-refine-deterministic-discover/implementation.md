# Implementation notes

## 2026-05-10 — Follow-up execution: II resolved

**II resolved**: Added `(is (= "run-github-find-issue" (:workflow-run-id invocation)))` to the CC block in `find_issue_integration_test.clj`. The stub already captured the full invocation map; the assertion now checks all three fields (`:args`, `:step-id`, `:workflow-run-id`) matching the reference pattern in `workflow_invoke_runtime_test.clj`. Test: 1 test, 13 assertions, 0 failures. Lint clean.

## 2026-05-10 — Implementation review pass 12

**II. Integration test CC invocation-shape assertion is incomplete.** The CC assertion checks `(:args invocation)` and `(:step-id invocation)` but not `(:run-id invocation)` (i.e. `:workflow-run-id` captured as `:run-id` in the stub). The reference pattern (`workflow_invoke_runtime_test.clj` line 91) explicitly asserts `{:args args :run-id workflow-run-id :step-id step-id}`. The integration test stub captures the full invocation but only asserts two of the three fields — the run-id proof is missing. Fix: capture `(:workflow-run-id invocation)` as `:run-id` in the stub (matching the reference pattern) and assert `(= "run-github-find-issue" (:run-id invocation))`.

## 2026-05-10 — Design review pass 11

**HH. Integration test comment contradicts corrected design.** `find_issue_integration_test.clj` lines 48–51 still say "no `:workflow-execution-adapter` is wired in the test ctx. The test passing at `:completed` is the proof." This is the pre-GG description that design.md and design-steps.md item X explicitly corrected: `create-session-context` calls `session/create-context` which always wires `:workflow-execution-adapter` via `create-context*`. The adapter IS present. The correct proof is `@calls*` count = 1 + `:completed` status + `:invoke` step bypasses `create-step-attempt-session!`. The comment in the test file was never updated when GG resolved the design. Fix: replace the stale comment with the accurate proof description.

## 2026-05-10 — Design review pass 10 follow-up (design-step GG resolved)

**GG resolved**: `design.md` integration test proof mechanism corrected. Removed the incorrect claim that the test ctx lacks `:workflow-execution-adapter` — `create-session-context` always wires it via `create-context*`. The correct proof description is now: (a) `@calls*` count = 1, (b) `:completed` status, and (c) `:invoke` step calls `invoke-step-runtime-result` directly without `create-step-attempt-session!`. Design-steps.md item X also corrected with the same fix and a note marking it was updated by GG.

## 2026-05-10 — Design review pass 10

**GG. `design.md` integration test proof mechanism is inaccurate.** Line 114 says "test ctx that has the `github/find-issue` operation registered but no `:workflow-execution-adapter` wired." This is wrong: `session/create-context` (called by `create-session-context` in both the reference test and the integration test) always adds `:workflow-execution-adapter` via `create-context*`. The adapter IS present in the test ctx. The actual proof that no session is spawned is: (a) `@calls*` count = 1 (handler invoked exactly once), (b) `:completed` status (no error from missing session infrastructure), and (c) the `:invoke` step never calls `create-step-attempt-session!` — it calls `invoke-step-runtime-result` directly. The test implementation is correct; only the design's description of the proof mechanism is wrong.

## 2026-05-10 — Design review pass 9 follow-up (design-steps DD–FF resolved)

**DD resolved**: `design.md` slug derivation line changed from "using word-boundary truncation:" to "using hard truncation:" — label now matches the rule and design-steps M.

**EE resolved**: `plan.md` Phase 1 second step 7 ("Lint clean") renumbered to 8. No duplicate step numbers remain.

**FF resolved**: `steps.md` smoke-test and downstream-verify items annotated with `<!-- blocked: requires real GitHub repo with labeled issues -->` inline HTML comments so readers know they are environment-blocked, not merely pending.

## 2026-05-10 — Design review pass 9

**DD. `design.md` slug description uses contradictory label.** Line 94 says "using word-boundary truncation:" but then describes hard truncation. `design-steps M` explicitly says "Word-boundary truncation is NOT used." The introductory label is wrong and contradicts both the rule and design-steps M.

**EE. `plan.md` Phase 1 has duplicate step number 7.** Two items are numbered `7.` — "Confirm `extensions/tests.edn` requires no change" and "Lint clean". One should be renumbered to `8.`.

**FF. `steps.md` open smoke-test and downstream-verify items have no blocked annotation.** `implementation.md` records these as "blocked — requires a real GitHub repo". `steps.md` shows them as plain unchecked `[ ]` with no note. A reader of `steps.md` cannot tell they are environment-blocked rather than simply not-yet-started.

## 2026-05-10 — Design review pass 8

**AA. Integration test missing `effective-args` assertion.** The reference pattern (`workflow_invoke_runtime_test.clj`) asserts `(get-in run [:step-runs "discover" :attempts 0 :effective-args])` to prove that `{:from :workflow-input :path [:input]}` was resolved to `nil` through `resolve-invoke-args`. The integration test in `find_issue_integration_test.clj` does not assert this. Without it, the test does not prove arg resolution works correctly — only that the handler was called.

**BB. Integration test missing `issue_url` assertion in `:summary`.** The handoff Markdown includes `issue_url:` but the integration test only checks `issue_number`, `issue_title`, and `worktree_description` in `:summary`. The design states the handoff format should be structurally verified.

**CC. Integration test `calls*` invocation shape not asserted.** The test asserts `(count @calls*)` = 1 but does not assert the invocation shape (`{:args {:labels [...] :input nil} :run-id "..." :step-id "discover"}`). The reference test asserts this shape. Without it, the test does not prove the operation was invoked with the correct resolved args.

## 2026-05-10 — Phase 1 + Phase 2 execution complete

Phase 1 (psi/github extension) and Phase 2 (gh-issue-refine.md update) are fully implemented.

**Files created:**
- `extensions/github/deps.edn` — cheshire 5.13.0 dep, :test alias with kaocha + extension-test-helpers + agent-session
- `extensions/github/src/psi/github/find_issue.clj` — deterministic operation handler; `:github-shell-fn` seam; cheshire string-key JSON; narrowing (integer, URL, text); slug derivation; `result->handoff-md` serializer; shell error handling
- `extensions/github/src/psi/github/extension.clj` — `init` registering `github/find-issue` via `(:register-operation api)`
- `extensions/github/test/psi/github/find_issue_test.clj` — 11 unit tests, 38 assertions, all passing
- `extensions/github/test/psi/github/extension_test.clj` — init registration test using `create-extension-api` with captured `register-deterministic-operation-fn`
- `extensions/github/test/psi/github/find_issue_integration_test.clj` — `^:integration`-tagged integration test; proves no session spawned; 1 test, 8 assertions, passing

**Files modified:**
- `.psi/extensions.edn` — added `psi/github {}`
- `deps.edn` — added `extensions/github/src` to `:run`, `:psi`, `:tui-demo`, `:test` aliases; added `extensions/github/test` to `:test-paths` and `:test` aliases
- `tests.edn` — added `extensions/github/src` to `:unit` `:source-paths`; added `extensions/github/test` + `extensions/github/src` to `:extensions` suite; added both to `:integration` suite
- `.psi/workflows/gh-issue-refine.md` — replaced `discover` `:delegate` step with `:invoke` step using `github/find-issue`
- `CHANGELOG.md` — added `[Unreleased]` entry

**Smoke test (steps.md)**: blocked — requires a real GitHub repo with issues labeled `enhancement` + `refine`. Cannot be executed in this environment.

**Downstream verify (steps.md)**: blocked — same reason; the handoff format is structurally identical to the previous builder output so no downstream change is expected.



## 2026-05-10 — Design review pass 7

**Y. `root deps.edn :deps` wiring instruction is wrong.** Design.md and steps.md both say add `psi/github {:local/root "extensions/github"}` under root `deps.edn` `:deps`. No existing extension has a `:local/root` entry in root `deps.edn` `:deps` — extensions are wired exclusively via `:extra-paths` in aliases (`:run`, `:psi`, `:tui-demo`, `:test`). The `:local/root` instruction contradicts the actual codebase pattern and must be removed; only the `:extra-paths` wiring is needed.

**Z. `steps.md` unit test list missing 3 cases.** Design (V, W, T resolved) specifies these unit test cases but they are absent from `steps.md`: (a) text substring narrowing with case-folding assertion; (b) non-zero `gh` CLI exit → `{:status :error :reason :psi.github/shell-error ...}`; (c) invalid URL (no `/issues/NNN` segment) → `{:status :error :reason :psi.github/invalid-url-input ...}`. All three must be added to the steps.md unit test checklist.

## 2026-05-10 — Design review pass 6 follow-up (design-steps S–X resolved)

**S resolved**: `(cheshire.core/parse-string json)` — string keys, no keywordize flag. `gh` CLI returns camelCase JSON (`"number"`, `"title"`, `"url"`, `"state"`); labels are objects with `"name"` subfield. Confirmed by running `gh issue list --json number,title,url,labels,state` against live repo. All field access uses string keys: `(get issue "number")`, `(map #(get % "name") (get issue "labels"))`.

**T resolved**: URL detection: `(str/starts-with? input "https://")`. Extraction: `(re-find #"/issues/(\d+)" input)`. No match → `{:status :error :reason :psi.github/invalid-url-input :message "Cannot extract issue number from URL: <input>"}`. No text-match fallback for malformed URLs.

**U resolved**: `(re-matches #"^\d+$" input)` detects integer strings; `(Long/parseLong input)` parses. `"007"` → 7, `"0"` → 0, `"-1"` → no match. Leading zeros accepted by regex and parsed as decimal by `Long/parseLong`.

**V resolved**: Case-insensitive text match: `(str/includes? (str/lower-case title) (str/lower-case input))`. Unit tests must include a case-folding case.

**W resolved**: Non-zero exit → `{:status :error :reason :psi.github/shell-error :message (:err result)}`. Unit test stub: `{:exit 1 :out "" :err "gh: not authenticated"}`.

**X resolved**: Integration test "no session spawned" assertion: run `execute-run!` without `:workflow-execution-adapter` in ctx. Assert `:completed`. If session spawned, `execution-adapter/adapter` throws. Pattern: `invoke-step-executes-through-deterministic-operation-registry-test` in `workflow_invoke_runtime_test.clj`. Also assert handler `calls*` count = 1.

## 2026-05-10 — Design review pass 6

**S. `cheshire/parse-string` keywordize flag unspecified.** Design says use `cheshire.core/parse-string` but does not say whether to call `(parse-string json true)` (keyword keys) or `(parse-string json)` (string keys). The field access code (`"number"` vs `:number`) depends on this. Must be specified before implementation.

**T. URL detection regex unspecified.** Design says "if it looks like a URL → extract number from URL" but gives no detection pattern. GitHub issue URLs are `https://github.com/owner/repo/issues/NNN`. No regex for detection or number extraction is specified. Must be specified.

**U. Integer parsing rule unspecified.** Design says "if `input` parses as an integer → filter by issue number" but does not specify the parsing mechanism (e.g. `re-matches #"^\d+$"`, `Long/parseLong`, `Integer/parseInt`). Edge cases (negative numbers, zero, leading zeros) are unaddressed. Must be specified.

**V. Text substring match case sensitivity unspecified.** Design says "text substring match on title" but does not specify case-insensitive vs case-sensitive matching. Unit tests for text narrowing cannot be written deterministically without this. Must be specified.

**W. `gh` CLI non-zero exit code handling unspecified.** `clojure.java.shell/sh` returns `{:exit N :out "..." :err "..."}`. Design does not say what to do when `:exit` is non-zero (e.g. `gh` not authenticated, network error). Should return `{:status :error :reason :psi.github/shell-error ...}` or throw? Must be specified.

**X. Integration test "no session spawned" assertion mechanism unspecified.** Design says the Phase 2 integration test "proves no session is spawned" but does not specify how to assert this (e.g. check session count in registry, verify step type is `:invoke` in compiled IR, or simply confirm workflow completes without session allocation). Must be specified before Phase 2 execution.

## 2026-05-10 — Design review pass 5 follow-up (design-steps Q–R resolved)

**Q resolved**: `deps.edn` `:test-paths` alias wiring corrected. `extensions/github/src` goes to `:run`, `:psi`, `:tui-demo`, and `:test` aliases only — NOT `:test-paths`. `:test-paths` contains only test paths (e.g. `extensions/work-on/test`); extension `src` paths never appear there. `extensions/github/test` goes to both `:test-paths` and `:test`. Confirmed against actual `deps.edn`. Design.md, plan.md, and steps.md updated.

**R resolved**: Phase 2 integration test lives in `extensions/github/test`, tagged `^:integration`. Rationale: extension tests belong in the extension's own test dir; `^:integration` meta ensures the `:integration` kaocha suite (`:focus-meta [:integration]`) picks it up and the `:extensions` suite (`:skip-meta [:integration]`) skips it. Requires adding `extensions/github/test` to `:integration` suite `:test-paths` and `extensions/github/src` to `:integration` suite `:source-paths` in root `tests.edn`. Design.md, plan.md, and steps.md updated.

## 2026-05-10 — Design review pass 5

**Q. `deps.edn` `:test-paths` wiring instruction is wrong.** Design and plan say add `extensions/github/src` to `:test-paths` alias. The actual `:test-paths` alias contains only test paths for extensions (e.g. `extensions/work-on/test`), never extension `src` paths. Extension `src` paths appear in `:test` alias only. Instruction should be: add `extensions/github/src` to `:test` alias (not `:test-paths`). Add `extensions/github/test` to both `:test-paths` and `:test` aliases (consistent with work-on pattern).

**R. Phase 2 integration test placement unspecified.** Design and steps call for a "focused workflow-runtime integration test" for the `:invoke` + `github/find-issue` step, but neither specifies where the test file lives (`extensions/github/test` vs `components/workflow-runtime/test`). This determines which Kaocha suite runs it and whether `extensions/github/src` must be in the `:unit` suite source-paths. Must be decided before Phase 2 execution.

## 2026-05-10 — Design review pass 4 (design-steps L–P resolved)

**L resolved**: Two-layer test approach. `psi.github.find-issue/invoke` unit tests call the fn directly with stub ctx. `psi.github.extension/init` registration test uses `create-extension-api` with captured `register-deterministic-operation-fn` override (pattern from `extensions_test.clj`). Nullable API cannot be used for `init` — no `:register-operation` key. Design.md and steps.md updated.

**M resolved**: Slug rule: lower-case title → extract `[a-z0-9]+` words → join with `-` → hard-truncate at 40 chars → strip trailing `-`. Hard truncation on joined string (not word-boundary truncation). Simple and deterministic. Design.md updated with precise rule and example.

**N resolved**: Schema changed to `[:maybe :string]`. `resolve-invoke-args` resolves absent `:input` to `nil`; `[:maybe :string]` accepts `nil` as "no narrowing". Authored step keeps `:input {:from :workflow-input :path [:input]}` in `:args` — no conditional omission. Design.md updated.

**O resolved**: `extensions/github/src` must be added to `:unit` suite `:source-paths` in root `tests.edn`. All other extension `src` paths are listed there. Plan.md and steps.md updated.

**P resolved**: `extensions/tests.edn` requires no change. It is a standalone relative-path kaocha config for running within `extensions/`; root `tests.edn` is authoritative. No existing extension is listed by absolute path there. `psi/github` is fully covered by root `tests.edn` `:extensions` suite.

## 2026-05-10 — Design review pass 3

**L. `nullable_api` does not expose `:register-operation`.** The design says the extension `init` test uses a "nullable shell stub", but `psi.extension-test-helpers.nullable-api/create-nullable-extension-api` has no `:register-operation` key. Testing `psi.github.extension/init` requires either (a) a real extension registry + `create-extension-api` with `{:register-deterministic-operation-fn ...}` override (matching the pattern in `extensions_test.clj`), or (b) a direct call to `psi.github.find-issue/invoke` without going through `init`. The design must specify which approach is used for the `init` registration test.

**M. Slug truncation rule underspecified.** The design says `worktree-description` is "≤ 40 chars, `[a-z0-9-]`" but does not specify: word-boundary truncation vs hard-truncate, or whether trailing hyphens must be stripped. The unit test for slug derivation cannot be written deterministically without this rule.

**N. `:input` nil vs absent — operation schema gap.** When `:input` is absent from `workflow-input`, `resolve-invoke-args` with `{:from :workflow-input :path [:input]}` resolves to `nil`. The operation's malli schema shows `:input {:optional true} :string` — but `nil` is not a `:string`. The schema must either use `[:maybe :string]` or the authored step must omit `:input` from `:args` entirely when no narrowing is provided. Decide and document.

**O. `tests.edn` `:unit` suite source-paths gap.** All other extensions' `src` paths appear in the `:unit` suite `:source-paths` (for compilation of component tests that import extension code). `extensions/github/src` must also be added to `:unit` `:source-paths` to maintain parity. The plan only mentions the `:extensions` suite.

**P. `extensions/tests.edn` not addressed.** A separate `extensions/tests.edn` exists. No existing extension is listed there. Plan should explicitly confirm `extensions/tests.edn` requires no change (or update it if needed).

## 2026-05-10 — Task created

Motivation: The `gh-issue-refine` `discover` step is a full builder-delegate AI invocation for what is a deterministic shell-command + selection-rule operation. Replace with a `:tool` step that executes synchronously via a registered psi extension tool.

### Key decisions recorded at design time

**`:tool` step type vs `:script` step type**: chose `:tool` because it integrates with the existing capability catalog, permission model, and execution-adapter seam — rather than adding an ad-hoc subprocess step type that bypasses all of that.

**Output serialization format**: `:tool` steps serialize their result map into the canonical `## Handoff Data` Markdown bullet format so all downstream steps require zero changes.

**Shell seam**: `gh` CLI invocation is behind a `:github-shell-fn` ctx key (defaulting to `clojure.java.shell/sh`) so the extension is testable without spawning real `gh` processes.

**Error handling**: a tool step that throws `:psi.github/no-matching-issue` drives the workflow to a terminal error state (not a retry/loop). This matches the existing builder behavior ("stop and report nothing to process").

**Component placement**: new `components/github/` component. Does not live inside `components/workflow-runtime/` because it is domain-specific (GitHub) not workflow-generic.

### Open questions at design time

- Does the execution-adapter's `execute-tool-fn` receive the full `ctx` or a stripped capability map? Decide during Phase 2 based on what the tool fn needs (likely just `ctx` with `:github-shell-fn` threaded through).
- Is `clojure.data.json` the right JSON parser or should this use `cheshire` (check transitive deps in the github component)?
- Does the target-IR compiler need changes, or does the step-execution branch consuming `:tool` steps short-circuit before IR compilation? Clarify during Phase 2.

## 2026-05-10 — Design review pass 2

**G. `implementation.md` initial entry is stale.** The "Task created" section still describes `:tool` as the chosen step type and poses open questions about tool registration and `clojure.data.json`. These were superseded by pass 1 resolutions. The stale entry is misleading noise but is historical record — no edit needed; noted here for clarity.

**H. `:outputs` partial override drops compiler defaults.** The design's authored step specifies `{:outputs {:summary {:source :invoke/summary}}}`. `target-ir-compiler/compile-common-step-fields` applies `(or outputs (step-default-outputs type))` — full replacement, not merge. The compiled IR will have only `{:summary ...}`, losing `:data` and `:result`. Design must explicitly confirm no downstream step consumes `{:output :data}` or `{:output :result}` from the discover step, OR recommend omitting `:outputs` to keep all three defaults.

**I. `components/github/` placement contradicts extension pattern.** All domain-specific extensions live in `extensions/` and are listed in `.psi/extensions.edn`. The design says `components/github/` ("parallel to `components/workflow-runtime/`") but the extension registration path (`(:register-operation api)`) and `.psi/extensions.edn` entry are extension patterns, not component patterns. The placement must be resolved: `extensions/github/` (extension) or `components/github/` (core infrastructure with no `.psi/extensions.edn` entry).

**J. `deps.edn` wiring not specified.** The design mentions `.psi/extensions.edn` registration but omits the required root `deps.edn` changes: `psi/github {:local/root "..."}` under `:deps`, and source paths added to `:run`/`:psi` aliases. Without this the extension code is not on the classpath at runtime.

**K. `tests.edn` suite target depends on placement.** Plan says "Wire `components/github/` into Kaocha `tests.edn`". If placed in `extensions/`, the test paths belong in the `:extensions` suite; if in `components/`, the `:unit` suite. Must align with the placement decision (issue I).

## 2026-05-10 — Design review pass 1 follow-up (design-steps A–F resolved)

**A resolved**: `:invoke` + deterministic-operation-registry. `gh-find-issue` is workflow-internal, never AI-callable. Zero new step types. Phase 2 (`:tool` step type) removed from scope.

**B resolved**: No IR schema changes. `:invoke` step type handles this directly. Target-IR compiler already supports `:invoke`.

**C resolved**: Moot — not a tool. Extension uses `(:register-operation api)` with `{:id :handler :description?}` matching `operation-definition-schema`.

**D resolved**: `cheshire/cheshire "5.13.0"` in `components/github/deps.edn`. `clojure.data.json` removed from design.

**E resolved**: `{:outputs {:summary {:source :invoke/summary}} :yields {:type :text :text :summary}}`. Operation handler returns Markdown handoff as `:summary`. `step-yield-field-value :text` → `:summary` output → Markdown string. Downstream steps unchanged.

**F resolved**: `plan.md` written.

## 2026-05-10 — Design review pass 1

**A. `:tool` step type vs `:invoke` step type — architectural overlap unresolved.**
The codebase already has a `:invoke` step type that calls deterministic operations registered in `deterministic-operation-registry`. The proposed `:tool` step type is structurally identical in purpose (synchronous, deterministic, no session). The design does not justify why `gh-find-issue` should be a *tool* (in the tool catalog, used by AI agents) rather than a *deterministic operation* (in the operation registry, used by workflow steps). Using `:invoke` + a new `github/find-issue` operation would require zero new step types, zero IR schema changes, and zero new execution-adapter keys. This is the primary architectural ambiguity.

**B. `:tool` IR schema not specified.**
The design says "thread `:tool` through the IR" but the IR only supports `:invoke`, `:session`, `:delegate`. No `:tool` IR step schema is defined. If `:tool` is a distinct IR step type, its malli schema, source-ref traversal, and semantic validation rules must all be specified. If `:tool` compiles to `:invoke` in the IR (which would be clean), the design should say so explicitly.

**C. Tool registration contract mismatch.**
The design shows `{:name "gh-find-issue" :fn ... :schema ...}` in the extension manifest, but `normalize-tool-def` (tool-registry) uses `{:name :description :parameters :execute}`. The keys `:fn` and `:schema` do not exist in the tool-def contract. The design must either use the existing tool-def shape or explicitly extend it.

**D. `clojure.data.json` not present — use `cheshire`.**
The design states `clojure.data.json` is "already available" but the project uses `cheshire/cheshire` for JSON parsing throughout (tool-runtime, provider-auth, tool-registry, etc.). `clojure.data.json` appears in no `deps.edn`. The github component must declare `cheshire` as its JSON dependency.

**E. `:yield :text` compatibility for downstream steps not fully specified.**
The `worktree` step consumes `{:from {:step "discover" :yield :text}}`. For a `:delegate` step this resolves via `:delegated` yields. A `:tool` step needs its own `:yields` spec — the design proposes serializing the result to Markdown and storing it as `:yield :text`, but does not specify the IR `:yields` shape for `:tool` steps (e.g. `{:type :text :text :final-llm-reply}`) or where the Markdown string is stored in the result envelope.

**F. No `plan.md` — Munera protocol requires it before execution.**
The task has `design.md`, `steps.md`, and `implementation.md` but no `plan.md`. Per the Munera protocol, `plan.md` must exist and be written before execution begins.

## 2026-05-10 — Design review pass 2 follow-up (design-steps H–K resolved)

**H resolved**: `:outputs {:summary {:source :invoke/summary}}` is correct and intentional. No downstream step in `gh-issue-refine.md` references `:data` or `:result` from `discover` — all consume `{:yield :text}` only. Compiler replaces defaults entirely (no merge), so the explicit single-key `:outputs` is the right shape. Design.md updated with override-behavior note.

**I resolved**: `extensions/github/` is the correct placement. `psi/github` is domain-specific (GitHub-only) and uses the extension registration pattern (`(:register-operation api)` + `.psi/extensions.edn`). Components are reusable core infrastructure; this is not. All references to `components/github/` in design.md, plan.md, and steps.md corrected to `extensions/github/`.

**J resolved**: Root `deps.edn` wiring specified explicitly. Add `psi/github {:local/root "extensions/github"}` under `:deps`. Add `extensions/github/src` to `:run`, `:psi`, `:tui-demo`, `:test-paths`, `:test` alias `:extra-paths`. Add `extensions/github/test` to `:test-paths` and `:test` aliases. Design.md, plan.md, and steps.md updated.

**K resolved**: `tests.edn` `:extensions` suite. Add `extensions/github/test` to `:test-paths` and `extensions/github/src` to `:source-paths`. Plan.md and steps.md updated.
