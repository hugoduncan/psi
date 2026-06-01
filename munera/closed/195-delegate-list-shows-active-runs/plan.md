# Plan

## Approach

Implement `delegate list` as a projection over the same delegate background-job ownership surface that `delegate run` and `continue` write, joined to canonical workflow runs for management identity and workflow status.

Key decisions:

- Treat same-session eligible delegate background jobs as the authoritative visibility/ownership marker for list rows.
- Treat canonical workflow runs as authoritative for management id, primary workflow status, definition/current-step metadata, and `continue`/`remove` compatibility.
- Keep delegate/background status separate from canonical workflow status in the list projection and text output.
- Make malformed non-terminal delegate workflow jobs actionable errors rather than misleading empty lists.
- Keep retained terminal history visible only when it can join to an existing canonical workflow run and can be reduced to one management row.
- Use attempt-specific delegate background-job `tool-call-id`s where retained jobs may coexist with resumed/continued attempts.
- Make `delegate remove` clean up or terminalize active delegate background jobs before/with canonical run removal so list cannot observe a non-terminal missing-canonical corruption after a successful remove.
- Add focused unit-level coverage around the projection rules first, then integration coverage through the actual delegate tool path.
- Keep the pure delegate-list projection boundary canonical and unqualified: callers normalize any namespaced `:psi.background-job/*` query maps into the unqualified delegate background-job shape before invoking the projection. The projection may defensively reject malformed or mixed shapes with actionable errors, but it should not contain a second broad query-shape interpretation layer.

## Risks

- The existing list path currently mixes canonical workflow-run listing with best-effort background-job query data; replacing it with strict background-job authority may expose latent registry/query failures as errors.
- Background-job query result shapes use namespaced keys while canonical workflow mutations return unqualified run maps; projection code must normalize carefully without broad API changes.
- Active remove cleanup may require coordination with runtime-owned background futures; the minimal acceptable slice may be terminalizing/removing the registry entry when cancellation is not directly available.
- Attempt-specific `tool-call-id` changes must preserve existing background-job diagnostics and not break unrelated background-job tests.
- Text output changes may be user-visible and therefore may require README/doc/changelog updates in the implementation slice.

## Slice order

1. **Characterize current delegate-list data paths and failure** — add/confirm a focused failing regression around active same-session delegate jobs being absent from `delegate list`.
2. **Extract delegate-list projection** — implement a pure projection that filters eligible background jobs, validates malformed cases, joins canonical runs, de-duplicates duplicate jobs, preserves terminal-retained visibility, and sorts rows deterministically.
3. **Wire projection into delegate list output** — make `delegate-list` use the invoking session's background-job query result plus canonical workflow runs, surface background-job read failures as tool errors, and render primary workflow status plus delegate/background status.
4. **Make attempt identity and blocked status coherent** — update delegate background-job start/completion handling so attempts can coexist with retained history and blocked workflow runs mark wrapper jobs completed while listing canonical `:blocked`.
5. **Make remove cleanup coherent** — update `delegate remove` for listed active/non-terminal jobs so canonical removal cannot leave a same-session non-terminal background job pointing at a missing run.
6. **Regression and boundary coverage** — add tests for same-session active visibility, unrelated-session exclusion, malformed/non-terminal errors, terminal retained hiding/listing, duplicate reduction/errors, deterministic ordering, and continue/remove compatibility with listed ids.
7. **Docs and coherence pass** — update user-facing docs/changelog only if output shape or behavior is visible, run focused tests/lint, and record verification in implementation notes.
