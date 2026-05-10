# Steps — 137 reload-code mutation propagation

## Checklist

- [x] 1. Add `all-mutations-atom` and `all-mutations-in` to `context.clj`
- [x] 2. Migrate callers: `psi_tool.clj`, `tool_plan.clj`, `runtime_eql.clj`
      - Note: `context` cannot be required by any of these (cycles via
        `context → ext-rt → runtime-fns → runtime-eql` etc.)
      - Used local `ctx-all-mutations` private helper in each file instead
- [x] 3. Implement real `:mutation-registration-refresh` step in `execute-psi-tool-reload-report`
      - Added `refresh-all-mutations!` — resolves `psi.agent-session.mutations/all-mutations`
        var and resets `:all-mutations-atom`
- [x] 4. Remove/fix throwaway `qctx` in `refresh-query-runtime!`
      - Rewrote to no-op report; resolvers are derived per-request, no snapshot to refresh
- [x] 5. Add test: reload propagates new mutations to `all-mutations-in`
      - Also fixed pre-existing missing `)` in second existing test
- [x] 6. Run existing reload tests; verify no regressions
      - 15 integration tests, 4 pre-existing TUI-tmux failures (require tmux), 0 new failures
- [x] 7. Lint: `clj-kondo --lint src` — 0 errors, 0 warnings
- [ ] 8. Commit and push
