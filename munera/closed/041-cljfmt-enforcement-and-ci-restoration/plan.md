Approach:
- Treat this as a tooling-consistency task spanning formatting baseline, local commit enforcement, and CI.
- First inspect the current formatting targets, hook scripts, and workflow state to identify the exact drift.
- Restore the formatting baseline with an explicit `cljfmt fix` pass and isolate that change in its own commit.
- Prove the staged-file hook behavior with a realistic staged-file scenario instead of trusting documentation alone.
- Make the smallest hook/workflow/doc changes needed so the local and CI contracts converge on the same formatting expectations.

Likely steps:
1. Inspect `bb fmt:check`, the `cljfmt-fix` hook script, and the current CI workflow to confirm the present enforcement gap.
2. Run `cljfmt fix` over the canonical repo targets and review the formatting-only diff.
3. Verify whether the existing pre-commit hook reformats and blocks commits for staged Clojure-file changes as intended.
4. If the hook behavior has drifted, update the hook/config so staged-file formatting violations block commit completion in a clear, reviewable way.
5. Re-enable `bb fmt:check` in `.github/workflows/ci.yml`.
6. Update `doc/develop.md` or adjacent docs if implementation details or commands changed.
7. Run the relevant formatting/hook verification commands and record the results.

Risks / watchpoints:
- accidentally mixing semantic code changes into the formatting baseline commit
- changing hook behavior in a way that surprises developers beyond the documented fix-and-retry flow
- re-enabling CI formatting checks before the repo formatting baseline is clean
- letting docs continue to describe a hook contract that is not actually verified
