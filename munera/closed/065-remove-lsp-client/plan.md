Approach:
- Treat this as an extract-and-remove change: preserve independently valuable generic sub-process lifecycle/registry infrastructure, then remove the LSP-specific capability completely.
- Prefer vertical coherence over piecemeal deletion: capability registration, commands, tests, docs, and task surfaces should be removed or updated together.
- Keep preservation decisions explicit: if a piece remains after LSP removal, it should stand on its own as general infrastructure rather than as a latent LSP seam.
- Keep extraction minimal: refactor only as much as needed to preserve clearly justified generic infrastructure.

Likely steps:
1. inventory the current LSP surface
   - extension implementation
   - extension registration/loading points
   - command and capability exposure
   - tests, fixtures, helpers, and docs/task/meta references
   - generic sub-process lifecycle/registry pieces currently exercised by LSP
2. separate generic sub-process infrastructure from LSP-specific logic where needed
   - keep only generic process lifecycle/registry infrastructure with clear non-LSP value
   - do not preserve JSON-RPC, diagnostics, workspace-sync, or other LSP-shaped protocol/client behavior unless it already has a current non-LSP consumer
   - remove LSP-only helper seams, telemetry, fixtures, and naming
3. remove the LSP client capability
   - delete extension/runtime wiring
   - remove commands and any capability advertisements
   - remove LSP-specific namespaces/files/tests/fixtures/helpers
   - rewrite any retained infrastructure tests so they prove generic behavior without LSP fixtures or terminology
4. update declared project surfaces
   - stop advertising LSP support in active docs/meta
   - replace active-task references to LSP enhancement with the removal task
   - leave historical/archive references alone unless they misstate current support
5. manage task supersession and verification
   - mark task `004` superseded by `065` during implementation
   - close task `004` as superseded when `065` lands
   - ensure no active LSP capability surface or misleading active references remain
   - ensure the repository is green

Decisions:
- Full removal, not deactivation.
- Preserve only generic sub-process lifecycle/registry infrastructure where it remains independently justified.
- Do not preserve LSP-shaped protocol/client behavior unless it already has a current non-LSP consumer.
- Replace task `004` directionally with `065`; active orchestration should point at removal rather than enhancement, with `004` marked superseded during implementation and closed when `065` lands.
- Docs should simply stop advertising LSP support; no explicit removal announcement is required.

Risks:
- deleting general sub-process infrastructure that still has non-LSP value
- leaving behind dead registration/tests/docs that imply LSP remains supported
- keeping too many LSP-shaped seams under the banner of preserved infrastructure

Completion notes:
- During implementation, task `004` should be explicitly marked superseded by `065` rather than left as a parallel active direction.
- When the removal is complete, close `004` as superseded and close `065` as completed so the task surface reflects the architectural change cleanly.
