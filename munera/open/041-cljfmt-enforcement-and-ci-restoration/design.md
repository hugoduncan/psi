Goal: restore formatting enforcement so Clojure formatting is automatically corrected locally, blocked at commit time when staged files remain misformatted, and verified again in CI.

Context:
- the repo already has `bb fmt:check` and documentation for `pre-commit`-based formatting hooks
- `.pre-commit-config.yaml` already defines a `cljfmt-fix` hook for staged Clojure files
- the current CI workflow no longer runs `bb fmt:check`; the `check` job only runs `bb lint`
- recent work appears to have left the repo or workflow in a state where formatting enforcement is only partial
- the user expected commit-time blocking on staged-file formatting failures to already exist, so part of this task is to verify the current hook behavior and restore the intended guarantee if it has drifted

Problem:
- formatting enforcement is inconsistent across the developer workflow
- CI currently allows formatting drift because the formatting check is disabled
- it is unclear whether the current pre-commit hook behavior reliably blocks commits when staged files need formatting, despite the documented intent
- without consistent local and CI enforcement, formatting drift can accumulate and create noisy follow-on diffs

Intent:
- make `cljfmt` enforcement explicit, working, and consistent across local commit flow and CI
- normalize the repository formatting baseline with a dedicated formatting pass and commit
- ensure the staged-file developer workflow is trustworthy and documented by reality rather than expectation

Scope:
- run `cljfmt fix` across the repo’s canonical formatting surface and commit the resulting formatting-only changes
- verify the existing pre-commit formatting hook behavior for staged Clojure files
- adjust the pre-commit hook setup only as needed so commits are blocked when staged files fail the intended `cljfmt` check/fix contract
- re-enable `bb fmt:check` in the GitHub Actions `check` job
- update any user-facing developer documentation that no longer matches the actual formatting workflow

Non-goals:
- changing non-formatting behavior in source files
- redesigning the project’s broader lint/test/commit-check strategy
- replacing `pre-commit` with a different hook manager
- broad formatting-tool migration away from `cljfmt`

Acceptance:
- the repo has a dedicated formatting commit produced by a project-wide `cljfmt fix` pass
- the local pre-commit flow reliably blocks commit completion when staged Clojure files are reformatted or fail formatting expectations, requiring the user to re-run the commit after reviewing/restaging as intended
- the effective hook behavior is proven against staged-file scenarios rather than assumed from docs alone
- `.github/workflows/ci.yml` runs `bb fmt:check` again in the `check` job before downstream test jobs
- documentation describing formatting and pre-commit behavior matches the implemented workflow

Minimum concepts:
- canonical formatting surface (`bb fmt:check` / `cljfmt fix` targets)
- staged-file formatting enforcement via `pre-commit`
- CI formatting gate in the shared `check` job
- formatting-only baseline commit to reduce future noise

Architecture alignment:
- follow the existing repo conventions around `bb` tasks, `pre-commit`, and GitHub Actions rather than introducing new enforcement paths
- prefer one canonical formatting contract used consistently in docs, local hooks, and CI
- keep the change narrow and tooling-focused
